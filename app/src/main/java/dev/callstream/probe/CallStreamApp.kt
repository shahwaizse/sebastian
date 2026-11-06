package dev.callstream.probe

import android.app.Application

/** Nothing at start-up since Shizuku went (build 92): BootReceiver and the screens start or reconnect the helper. */
class CallStreamApp : Application()
