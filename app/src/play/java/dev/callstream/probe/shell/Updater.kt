package dev.callstream.probe.shell

/** Google Play build: Play updates the app and the app never installs itself (the real one is in src/direct). */
internal object Updater {
    @Suppress("UNUSED_PARAMETER")
    fun install(url: String, sha256: String, callActive: Boolean): String = "Call Stream updates through Google Play."
}
