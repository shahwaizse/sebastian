package dev.callstream.probe

/**
 * How far this phone is, as the screens see it: the one state they all read, so none shows a loading state that
 * can't end or offers something setup hasn't made possible yet.
 *
 *   SETUP ──pair──▶ CONNECTING ──helper up──▶ LOADING ──first readings──▶ INCOMPLETE ◀──▶ READY
 *                     │    ▲                                                (a required step missing / all done)
 *              failed ▼    │ Reconnect, Wi-Fi back
 *                    OFFLINE
 * The helper dying from LOADING, INCOMPLETE or READY goes back to CONNECTING; forgetting the pairing, to SETUP.
 */
enum class AppStage {
    /** Not paired yet: the setup checklist is all there is to do. */
    SETUP,
    /** Paired; the helper is being started or reached. Short: it ends in LOADING or OFFLINE. */
    CONNECTING,
    /** Paired, but the helper couldn't be started (Wi-Fi off, Wireless debugging off…); the checklist says why. */
    OFFLINE,
    /** The helper is up; its first reading of the agent, key and permissions is on the way (about a second). */
    LOADING,
    /** The helper is up and a required step is missing: permissions, the agent or the key. */
    INCOMPLETE,
    READY;

    /** The helper is up and has answered: its data (leads, agent, key) can be shown. */
    val helperReady get() = this == INCOMPLETE || this == READY
    /** Waiting on something that ends by itself: show progress, not a step to do. */
    val waiting get() = this == CONNECTING || this == LOADING
    /** Something the person has to do in setup. */
    val needsSetup get() = this == SETUP || this == OFFLINE || this == INCOMPLETE
}

fun appStage(paired: Boolean, helperBound: Boolean, connecting: Boolean, connectError: String?, loaded: Boolean, setupComplete: Boolean): AppStage = when {
    helperBound && !loaded -> AppStage.LOADING
    helperBound -> if (setupComplete) AppStage.READY else AppStage.INCOMPLETE
    !paired -> AppStage.SETUP
    connecting || connectError == null -> AppStage.CONNECTING
    else -> AppStage.OFFLINE
}

/** What tapping the big switch does. */
enum class OrbTap { TOGGLE, SETUP_GATE, NOTHING }

/** Turning off always works (a call in progress must be handable back); turning on needs setup done. */
fun orbTap(stage: AppStage, stream: StreamView, busy: Boolean): OrbTap = when {
    busy || stage.waiting -> OrbTap.NOTHING
    stage.helperReady && stream.on -> OrbTap.TOGGLE
    stage == AppStage.READY && stream.known -> OrbTap.TOGGLE
    stage == AppStage.READY -> OrbTap.NOTHING // the first status is a second away
    else -> OrbTap.SETUP_GATE
}

data class HeroText(val title: String, val body: String)

fun heroText(stage: AppStage, view: StreamView, agent: String, everComplete: Boolean, connectError: String?): HeroText = when {
    stage == AppStage.SETUP -> HeroText("Let's set up this phone", "A few switches in Settings, then $agent can answer your calls.")
    stage == AppStage.OFFLINE -> HeroText("Not connected", when {
        connectError != null -> connectError
        everComplete -> "Sebastian stopped on this phone. Settings shows how to bring $agent back."
        else -> "Sebastian couldn't start on this phone yet."
    })
    stage.waiting -> HeroText("Connecting…", if (everComplete) "Reaching this phone's helper." else "Starting Sebastian on this phone.")
    stage == AppStage.INCOMPLETE && !view.on -> HeroText("Almost ready", "Finish setup in Settings, then tap to let $agent answer.")
    else -> streamText(view, agent)
}

private fun streamText(view: StreamView, agent: String): HeroText = when (view.phase) {
    Phase.CONNECTING -> HeroText("Connecting…", "Reaching this phone's helper.")
    Phase.OFF -> HeroText("Agent is off", "Calls ring on your phone as usual. Tap to let $agent answer.")
    Phase.READY -> HeroText("Agent is live", if (view.detail.isNotEmpty()) "$agent answers incoming calls. Next outbound call: ${view.detail}."
        else "$agent answers every incoming call.")
    Phase.RINGING -> HeroText("Picking up…", "A call is coming in. $agent is answering it.")
    Phase.ON_CALL -> HeroText("On a call", if (view.detail == "outbound") "$agent is talking with a lead." else "$agent is talking with a caller.")
    Phase.DIALING -> HeroText("Calling ${view.detail}", "$agent will speak as soon as they answer.")
    Phase.ERROR -> HeroText("Needs attention", view.detail.ifEmpty { "Something stopped the agent. Turn it off and on again." })
}

/** The orb's look: the stream's phase once the helper speaks, a calm off-orb before. */
fun orbPhase(stage: AppStage, view: StreamView): Phase = when {
    stage.waiting -> Phase.CONNECTING
    !stage.helperReady -> Phase.OFF
    else -> view.phase
}

/** The Gemini key card's headline. "Checking…" only while the helper's first reading is on the way. */
fun keyTitle(options: AgentOptions, stage: AppStage): String {
    val ending = if (options.keyHint.isEmpty()) "" else " ••••${options.keyHint}"
    return when {
        stage.waiting -> "Checking…"
        stage == AppStage.OFFLINE -> "Shown once this phone is connected"
        !stage.helperReady -> "Add it once this phone is connected"
        options.keySource == "app" -> "Saved on this phone$ending"
        options.keySource == "provisioned" -> "Set from the laptop$ending"
        else -> "No key yet"
    }
}

/** The setup gate's message: [what] the person tried ("turn the agent on"), and the next step to do. A phone that was
 * connected and lost it (OFFLINE) isn't sent back to setting up: its problem is the connection. */
fun setupGateText(what: String, steps: List<SetupStep>, stage: AppStage): String {
    if (stage == AppStage.OFFLINE) return "Sebastian isn't running on this phone, so you can't $what right now. Settings shows what to fix."
    val next = nextSetupStep(steps)
    return "Finish setting up this phone to $what." + if (next != null) " Next step: ${next.title}." else ""
}
