package dev.callstream.probe.shell

/** Acoustic activity estimates only. This does not control Retell endpointing or audio. */
internal class TurnLatencyTracker(private val emit: (String, Map<String, Any>) -> Unit) {
    private var nextTurn = 0
    private var caller: Turn? = null
    private var pending: Turn? = null
    private var reply: Turn? = null
    private var callerCandidate = -1.0
    private var callerEnergyMs = 0.0
    private var agentLastEnergy = -1.0
    private var agentActive = false

    private data class Turn(val id: Int, val start: Double, var end: Double,
        var sentEnd: Double = -1.0, var overlap: Boolean = false,
        var received: Double = -1.0, var written: Boolean = false) {
        // Caller level split by whether the agent was audible: shows ducking during double-talk.
        var rmsAloneSum = 0.0; var rmsAloneN = 0
        var rmsOverAgentSum = 0.0; var rmsOverAgentN = 0
        var peakRms = 0.0
        fun addLevel(rms: Double, overAgent: Boolean) {
            if (overAgent) { rmsOverAgentSum += rms; rmsOverAgentN++ } else { rmsAloneSum += rms; rmsAloneN++ }
            peakRms = maxOf(peakRms, rms)
        }
    }

    @Synchronized fun callerAudio(rms: Double, durationMs: Double, atMs: Double): Int? {
        if (rms >= CALLER_RMS) {
            if (caller == null) {
                if (callerCandidate < 0) callerCandidate = atMs
                callerEnergyMs += durationMs
                if (callerEnergyMs >= 30.0) {
                    pending?.let { emit("turn_unanswered", mapOf("turn" to it.id, "reason" to "new_caller_segment")) }
                    pending = null
                    caller = Turn(++nextTurn, callerCandidate, atMs, overlap = agentActive)
                    emit("caller_activity_start", mapOf("turn" to nextTurn, "onset_ms" to callerCandidate,
                        "estimate" to "energy_gate", "overlap" to agentActive))
                }
            }
            caller?.apply { end = atMs; overlap = overlap || agentActive; addLevel(rms, agentActive) }
        } else {
            callerCandidate = -1.0; callerEnergyMs = 0.0
            val current = caller
            if (current != null && atMs - current.end >= SILENCE_MS) finishCaller(current)
        }
        return if (rms >= CALLER_RMS) caller?.id else null
    }

    @Synchronized fun sentAudio(rms: Double, atMs: Double, turnId: Int?) {
        if (rms >= CALLER_RMS) {
            (caller ?: pending)?.let { if (it.id == turnId) it.sentEnd = atMs }
        }
    }

    private fun finishCaller(turn: Turn) {
        caller = null; callerCandidate = -1.0; callerEnergyMs = 0.0
        pending = turn
        emit("caller_activity_end", mapOf("turn" to turn.id, "last_energy_ms" to turn.end,
            "duration_ms" to turn.end - turn.start, "overlap" to turn.overlap,
            "confirmation_delay_ms" to SILENCE_MS, "estimate" to "energy_gate",
            "mean_rms_alone" to if (turn.rmsAloneN > 0) turn.rmsAloneSum / turn.rmsAloneN else 0.0,
            "mean_rms_over_agent" to if (turn.rmsOverAgentN > 0) turn.rmsOverAgentSum / turn.rmsOverAgentN else 0.0,
            "frames_over_agent" to turn.rmsOverAgentN, "peak_rms" to turn.peakRms))
    }

    /** Returns the estimated reply ID for propagation through the playback queue. */
    @Synchronized fun agentAudio(rms: Double, atMs: Double): Int? {
        if (rms >= AGENT_RMS) {
            if (!agentActive || atMs - agentLastEnergy >= SILENCE_MS) {
                // A reply can arrive before the next capture frame confirms silence.
                caller?.let { if (atMs - it.end >= SILENCE_MS) finishCaller(it) }
                agentActive = true
                val turn = pending
                pending = null; reply = turn
                turn?.apply {
                    received = atMs
                }
                val fields = mutableMapOf<String, Any>("estimate" to "energy_gate", "matched" to (turn != null))
                if (turn != null) {
                    fields["turn"] = turn.id; fields["caller_end_to_agent_pcm_ms"] = atMs - turn.end
                    fields["overlap"] = turn.overlap
                    if (turn.sentEnd >= 0) fields["last_sent_energy_to_agent_pcm_ms"] = atMs - turn.sentEnd
                }
                emit("agent_activity_start", fields)
            }
            agentLastEnergy = atMs
            caller?.overlap = true
        } else if (agentActive && atMs - agentLastEnergy >= SILENCE_MS) {
            agentActive = false
            emit("agent_activity_end", mapOf("last_energy_ms" to agentLastEnergy, "estimate" to "energy_gate"))
        }
        return if (rms >= AGENT_RMS) reply?.id else null
    }

    @Synchronized fun agentWritten(turnId: Int?, atMs: Double, queuedMs: Double, writeMs: Double) {
        val turn = reply
        if (turnId == null || turn == null || turn.id != turnId || turn.written) return
        turn.written = true
        emit("turn_response", mapOf("turn" to turn.id, "estimate" to "energy_gate",
            "caller_end_to_first_write_ms" to atMs - turn.end,
            "agent_receive_to_write_ms" to atMs - turn.received,
            "output_queue_ms" to queuedMs, "write_ms" to writeMs, "overlap" to turn.overlap,
            "boundary" to "AudioTrack_write_return_not_far_end_audibility"))
    }

    @Synchronized fun finish() {
        caller?.let { emit("turn_unanswered", mapOf("turn" to it.id, "reason" to "session_ended_during_activity")) }
        pending?.let { emit("turn_unanswered", mapOf("turn" to it.id, "reason" to "session_ended_before_reply")) }
        reply?.let { if (!it.written) emit("turn_unwritten", mapOf("turn" to it.id, "reason" to "agent_pcm_not_observed_written")) }
        caller = null; pending = null; reply = null; agentActive = false
    }

    companion object {
        const val CALLER_RMS = 200.0
        const val AGENT_RMS = 120.0
        const val SILENCE_MS = 300.0
    }
}
