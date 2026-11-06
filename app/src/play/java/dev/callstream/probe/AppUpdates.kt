package dev.callstream.probe

/** Google Play build: Play updates the app, so there's nothing to find (the real one is in src/direct). */
object AppUpdates {
    @Suppress("UNUSED_PARAMETER")
    fun check(beta: Boolean, installed: Int): Release? = null

    @Suppress("UNUSED_PARAMETER")
    fun installerIntent(context: android.content.Context, release: Release): android.content.Intent = error("Call Stream updates through Google Play.")
}
