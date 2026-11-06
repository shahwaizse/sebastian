package dev.callstream.probe.shell

import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack

/**
 * How much audio is waiting in one hop of the WhatsApp relay, and trimming of any queue that never empties.
 *
 * Why trim: both ends of a hop run at the same speed, so a queue that builds up once (a hiccup, a burst, audio left
 * over from the last call) never drains by itself. It's pure delay for the rest of the call. Each 1 s window keeps
 * the smallest queue seen; if even that is above [targetMs], the excess is standing and gets dropped, preferring
 * quiet 10 ms blocks so nobody hears a cut. Bursty readers (WhatsApp takes the mic in windows) empty the queue now
 * and then, so their minimum stays low and they're left alone.
 */
internal class HopGauge(val name: String, private val rate: Int, private val targetMs: Double, private val trim: Boolean) {
    private var winMin = Double.MAX_VALUE
    private var winBlocks = 0
    private var owedFrames = 0L
    private var owedBlocks = 0
    private var repMin = Double.MAX_VALUE
    private var repMax = 0.0
    private var repSum = 0.0
    private var repN = 0
    private var trimmedFrames = 0L

    /** One reading of the queue, in frames, once per 10 ms block. */
    @Synchronized fun observe(queuedFrames: Long) {
        val ms = queuedFrames.coerceAtLeast(0) * 1000.0 / rate
        repMin = minOf(repMin, ms); repMax = maxOf(repMax, ms); repSum += ms; repN++
        winMin = minOf(winMin, ms)
        if (++winBlocks >= 100) {
            // Trim down to the target, not below it: a bursty reader (WhatsApp's mic) needs that much ready or it runs dry.
            owedFrames = if (trim && winMin > targetMs) ((winMin - targetMs) * rate / 1000).toLong() else 0
            owedBlocks = 0
            winMin = Double.MAX_VALUE; winBlocks = 0
        }
    }

    /** Whether to drop this block of [frames] to pay down a standing queue. Quiet blocks first; loud ones after 0.5 s. */
    @Synchronized fun shouldDrop(frames: Int, quiet: Boolean): Boolean {
        if (owedFrames <= 0) return false
        if (!quiet && ++owedBlocks < 50) return false
        owedFrames -= frames; trimmedFrames += frames
        return true
    }

    /** "up=12/30/96ms cut140" (min/avg/max queue since the last report, total trimmed), then resets the window. */
    @Synchronized fun report(): String {
        val s = if (repN == 0) "$name=n/a" else
            "$name=%.0f/%.0f/%.0fms".format(repMin, repSum / repN, repMax)
        repMin = Double.MAX_VALUE; repMax = 0.0; repSum = 0.0; repN = 0
        return if (trimmedFrames > 0) "$s cut${trimmedFrames * 1000 / rate}" else s
    }

    companion object {
        /** [t]'s play position as an unsigned count. */
        fun head(t: AudioTrack): Long = t.playbackHeadPosition.toLong() and 0xffff_ffffL

        /**
         * Frames written to [t] but not yet played (taken by its reader), or null when the count can't be right: more
         * than the track holds, or negative. That happens if the play position jumps (a flush that resets it late):
         * the caller rebases its written count. Without this, a jump would read as a huge queue and the trimmer would
         * drop every block, silencing that direction for the rest of the call.
         */
        fun trackQueued(t: AudioTrack, written: Long): Long? {
            val q = written - head(t)
            return if (q < 0 || q > t.bufferSizeInFrames * 2L) null else q
        }

        /** Reads and discards whatever [r] already holds: audio from before this call (nobody reads it between calls). */
        fun drain(r: AudioRecord, frame: ShortArray): Int {
            var dropped = 0
            repeat(500) {
                val n = r.read(frame, 0, frame.size, AudioRecord.READ_NON_BLOCKING)
                if (n <= 0) return dropped
                dropped += n
            }
            return dropped
        }
    }
}

/** Captured-but-unread frames in an AudioRecord, from its timestamp. Zero is assumed at the first reading after [reset]. */
internal class RecordBacklog(private val rate: Int) {
    private val ts = AudioTimestamp()
    private var baseline = -1L
    var read = 0L

    fun reset() { baseline = -1; read = 0 }

    /** Null where the recorder gives no timestamp. */
    fun frames(r: AudioRecord): Long? {
        if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return null
        val pos = ts.framePosition + (System.nanoTime() - ts.nanoTime) * rate / 1_000_000_000L
        if (baseline < 0) baseline = pos - read
        return pos - baseline - read
    }
}
