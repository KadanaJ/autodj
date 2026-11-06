package com.autodj.engine

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/** Supplies songs to the engine. Both calls happen on the audio thread and must return quickly. */
interface TrackFeed {
    /** The song to mix into after [current], or null to stop when it ends. */
    fun next(current: TrackInfo): TrackInfo?

    /** Opens a source at the engine's sample rate. Must not block (decode in the background). */
    fun open(track: TrackInfo): PcmSource
}

data class EngineStatus(
    val current: TrackInfo? = null,
    val next: TrackInfo? = null,
    val positionSec: Double = 0.0,
    val plan: TransitionPlan? = null,
    /** -1 when not blending, else 0..1. */
    val blend: Double = -1.0,
    /** Current playback tempo of the main song. */
    val tempoBpm: Double? = null,
    val alignmentMs: Double = 0.0,
    val underruns: Int = 0,
)

/**
 * Two-deck automatic DJ mixer. Plays the current song, plans the transition into the next
 * one, and blends them: tempo matched, beat-locked, bass swapped half way, volumes crossfaded.
 */
class MixEngine(val sampleRate: Int, private val feed: TrackFeed) {
    private sealed interface Command {
        data class Play(val track: TrackInfo, val atSec: Double) : Command
        data object Skip : Command
        data object JumpToMix : Command
    }

    private val commands = ConcurrentLinkedQueue<Command>()

    private var a: Deck? = null
    private var b: Deck? = null
    private var plan: TransitionPlan? = null
    private var blending = false
    private var alignOffset = 0.0
    private var glide: Glide? = null
    private var bufA = FloatArray(0)
    private var bufB = FloatArray(0)
    private val limiter = Limiter(sampleRate)
    private val aligner = OnsetAligner(sampleRate)
    private var aligned = false

    private class Glide(val fromRate: Double, val startPos: Double, val length: Double)

    @Volatile
    var status = EngineStatus()
        private set

    /** Called (on the audio thread) whenever a new song becomes the main one. */
    var onTrackStarted: ((TrackInfo) -> Unit)? = null

    fun play(track: TrackInfo, atSec: Double = 0.0) = commands.add(Command.Play(track, atSec))

    /** Mix into the next song as soon as musically possible. */
    fun skip() = commands.add(Command.Skip)

    /** Testing aid: jump to 10 seconds before the planned transition. */
    fun jumpToMix() = commands.add(Command.JumpToMix)

    val isIdle: Boolean get() = a == null && commands.isEmpty()

    /** Renders [frames] stereo frames into [out] (overwrites). */
    fun render(out: FloatArray, frames: Int) {
        handleCommands()
        out.fill(0f, 0, frames * 2)
        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK, frames - done)
            renderBlock(out, done, n)
            done += n
        }
        limiter.process(out, frames)
    }

    private fun handleCommands() {
        while (true) {
            when (val c = commands.poll() ?: return) {
                is Command.Play -> {
                    closeAll()
                    startMain(c.track, feed.open(c.track), c.atSec, rate = 1.0)
                }
                Command.Skip -> {
                    val deckA = a ?: continue
                    if (blending) continue
                    val next = b?.track ?: continue
                    plan = TransitionPlanner.plan(deckA.track, next, deckA.positionSec + 0.3, quick = true)
                }
                Command.JumpToMix -> {
                    val deckA = a ?: continue
                    val p = plan ?: continue
                    if (blending) continue
                    val target = (p.aStart - 10).coerceAtLeast(0.0)
                    if (target > deckA.positionSec + 1) {
                        val rate = deckA.rate
                        deckA.close()
                        val fresh = Deck(deckA.track, sampleRate, feed.open(deckA.track))
                        fresh.rate = rate
                        fresh.start(target)
                        a = fresh
                    }
                }
            }
        }
    }

    private fun startMain(track: TrackInfo, source: PcmSource, atSec: Double, rate: Double) {
        val d = Deck(track, sampleRate, source)
        d.rate = rate
        d.start(atSec)
        a = d
        onTrackStarted?.invoke(track)
        prepareNext()
    }

    /** Picks and pre-loads the next song and plans the transition. */
    private fun prepareNext() {
        val deckA = a ?: return
        b?.close()
        b = null
        plan = null
        val next = feed.next(deckA.track) ?: return
        val p = TransitionPlanner.plan(deckA.track, next, deckA.positionSec + 5)
        val deckB = Deck(next, sampleRate, feed.open(next))
        deckB.start(p.bStart)
        b = deckB
        plan = p
    }

    private fun ensureBuffers(n: Int) {
        if (bufA.size < n * 2) {
            bufA = FloatArray(n * 2)
            bufB = FloatArray(n * 2)
        }
    }

    private fun renderBlock(out: FloatArray, offset: Int, n: Int) {
        val deckA = a ?: return
        ensureBuffers(n)
        val p = plan
        val deckB = b

        if (!blending && p != null && deckB != null && deckA.positionSec >= p.aStart) {
            beginBlend(deckA, deckB, p)
        }

        if (!blending) {
            // Wait (silently, without losing music) until a just-opened song has decoded.
            if (!deckA.ready) {
                publish(-1.0)
                return
            }
            applyGlide(deckA)
            deckA.lowGain = 1f
            deckA.render(bufA, n)
            mix(out, offset, bufA, n, 1f, 1f)
            if (deckA.finished) handleEndWithoutBlend()
            publish(-1.0)
            return
        }

        // Blending.
        p!!
        deckB!!
        val progress = ((deckA.positionSec - p.aStart) / p.lengthSec).coerceIn(0.0, 1.0)
        if (p.type == TransitionType.BEATMATCH) {
            val target = p.bStart + (deckA.positionSec - p.aStart) * p.bRateRelative + alignOffset
            val err = target - deckB.positionSec
            deckB.rate = deckA.rate * p.bRateRelative * (1 + (err * 2).coerceIn(-0.03, 0.03))
            // Bass swap around the middle (over ~1 beat).
            val swap = ((progress - 0.5) * p.beats).coerceIn(-0.5, 0.5) + 0.5
            deckA.lowGain = (1 - swap).toFloat()
            deckB.lowGain = swap.toFloat()
        } else {
            deckA.lowGain = 1f
            deckB.lowGain = 1f
        }
        val (ga0, gb0) = gains(p, progress)
        deckA.render(bufA, n)
        deckB.render(bufB, n)
        if (p.type == TransitionType.BEATMATCH && !aligned) {
            aligner.feed(bufA, bufB, n)
            if (progress > 0.3) {
                val lagSec = aligner.bestLagSec()
                if (lagSec != null) {
                    alignOffset += lagSec * deckB.rate
                    status = status.copy(alignmentMs = lagSec * 1000)
                }
                aligned = true
            }
        }
        val progressEnd = ((deckA.positionSec - p.aStart) / p.lengthSec).coerceIn(0.0, 1.0)
        val (ga1, gb1) = gains(p, progressEnd)
        mixRamp(out, offset, bufA, n, ga0, ga1)
        mixRamp(out, offset, bufB, n, gb0, gb1)

        if (progressEnd >= 1.0 || deckA.finished) finishBlend(deckA, deckB, p)
        publish(progressEnd)
    }

    private fun gains(p: TransitionPlan, x: Double): Pair<Float, Float> =
        if (p.type == TransitionType.BEATMATCH) {
            // B rises over the first half, A falls over the second (equal power).
            val gb = if (x < 0.5) sin(x / 0.5 * PI / 2) else 1.0
            val ga = if (x < 0.5) 1.0 else cos((x - 0.5) / 0.5 * PI / 2)
            ga.toFloat() to gb.toFloat()
        } else {
            cos(x * PI / 2).toFloat() to sin(x * PI / 2).toFloat()
        }

    private fun beginBlend(deckA: Deck, deckB: Deck, p: TransitionPlan) {
        blending = true
        aligned = p.type != TransitionType.BEATMATCH
        alignOffset = 0.0
        aligner.reset()
        glide = null
        deckB.rate = if (p.type == TransitionType.BEATMATCH) deckA.rate * p.bRateRelative else 1.0
        deckB.lowGain = if (p.type == TransitionType.BEATMATCH) 0f else 1f
    }

    private fun finishBlend(deckA: Deck, deckB: Deck, p: TransitionPlan) {
        deckA.close()
        blending = false
        a = deckB
        b = null
        deckB.lowGain = 1f
        // Glide back to the song's own tempo over 32 of its beats.
        glide = if (p.type == TransitionType.BEATMATCH && abs(deckB.rate - 1) > 0.001) {
            Glide(deckB.rate, deckB.positionSec, 32 * deckB.track.beatLength)
        } else {
            null
        }
        onTrackStarted?.invoke(deckB.track)
        prepareNext()
    }

    private fun applyGlide(deck: Deck) {
        val g = glide ?: return
        val x = ((deck.positionSec - g.startPos) / g.length).coerceIn(0.0, 1.0)
        deck.rate = g.fromRate + (1 - g.fromRate) * x
        if (x >= 1.0) glide = null
    }

    /** A ran out before a planned blend (e.g. the plan failed): start the next song right away. */
    private fun handleEndWithoutBlend() {
        val deckA = a ?: return
        deckA.close()
        val deckB = b
        a = null
        if (deckB != null) {
            b = null
            a = deckB
            glide = null
            deckB.rate = 1.0
            deckB.lowGain = 1f
            onTrackStarted?.invoke(deckB.track)
            prepareNext()
        }
    }

    private fun mix(out: FloatArray, offset: Int, src: FloatArray, n: Int, g0: Float, g1: Float) = mixRamp(out, offset, src, n, g0, g1)

    private fun mixRamp(out: FloatArray, offset: Int, src: FloatArray, n: Int, g0: Float, g1: Float) {
        for (i in 0 until n) {
            val g = g0 + (g1 - g0) * i / n
            out[2 * (offset + i)] += src[2 * i] * g
            out[2 * (offset + i) + 1] += src[2 * i + 1] * g
        }
    }

    private fun publish(blend: Double) {
        val deckA = a
        status = EngineStatus(
            current = deckA?.track,
            next = b?.track,
            positionSec = deckA?.positionSec ?: 0.0,
            plan = plan,
            blend = blend,
            tempoBpm = deckA?.track?.bpm?.let { it * deckA.rate },
            alignmentMs = status.alignmentMs,
            underruns = (deckA?.underruns ?: 0) + (b?.underruns ?: 0),
        )
    }

    private fun closeAll() {
        a?.close()
        b?.close()
        a = null
        b = null
        plan = null
        blending = false
        glide = null
    }

    fun release() = closeAll()

    companion object {
        /** Parameters (gains, rates) update every 256 frames (~6 ms). */
        const val BLOCK = 256
    }
}

/**
 * Measures how far B's drum hits are from A's during the start of a blend, by
 * cross-correlating short-hop energy-onset curves of the two decks.
 */
class OnsetAligner(private val sampleRate: Int) {
    private val hop = 64
    private val maxLag = (0.035 * sampleRate / hop).toInt()
    private val fa = ArrayList<Float>()
    private val fb = ArrayList<Float>()
    private var accA = 0.0
    private var accB = 0.0
    private var count = 0
    private var prevA = 0.0
    private var prevB = 0.0

    fun reset() {
        fa.clear(); fb.clear()
        accA = 0.0; accB = 0.0; count = 0; prevA = 0.0; prevB = 0.0
    }

    fun feed(a: FloatArray, b: FloatArray, n: Int) {
        for (i in 0 until n) {
            val la = a[2 * i] + a[2 * i + 1]
            val lb = b[2 * i] + b[2 * i + 1]
            accA += la * la
            accB += lb * lb
            if (++count == hop) {
                val ea = ln(1e-6 + accA / hop)
                val eb = ln(1e-6 + accB / hop)
                fa.add(maxOf(0.0, ea - prevA).toFloat())
                fb.add(maxOf(0.0, eb - prevB).toFloat())
                prevA = ea; prevB = eb
                accA = 0.0; accB = 0.0; count = 0
            }
        }
    }

    /** Positive = B is late by that many seconds. Null when there is not enough evidence. */
    fun bestLagSec(): Double? {
        val n = fa.size
        if (n < 400) return null
        val scores = DoubleArray(2 * maxLag + 1)
        for (li in scores.indices) {
            val lag = li - maxLag
            var s = 0.0
            for (t in maxLag until n - maxLag) s += fa[t] * fb[t + lag]
            scores[li] = s
        }
        var best = maxLag
        for (i in scores.indices) if (scores[i] > scores[best]) best = i
        val zero = scores[maxLag]
        if (zero > 0 && scores[best] < zero * 1.1) return 0.0 // already aligned
        if (best == 0 || best == scores.size - 1) return null // at the edge: not trustworthy
        val l = scores[best - 1]
        val c = scores[best]
        val r = scores[best + 1]
        val den = l - 2 * c + r
        val delta = if (den != 0.0) (0.5 * (l - r) / den).coerceIn(-0.5, 0.5) else 0.0
        return (best - maxLag + delta) * hop / sampleRate
    }
}
