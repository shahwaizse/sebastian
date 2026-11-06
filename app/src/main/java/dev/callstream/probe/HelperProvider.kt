package dev.callstream.probe

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Receives the shell helper's binder (see shell/HelperDaemon). Only the shell or root uid may attach. */
class HelperProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != 0) throw SecurityException("helper attach is shell-only (uid $uid)")
        if (method == "attach") extras?.getBinder("binder")?.let {
            HelperConnector.attach(it)
            context?.let(HelperRevive::scheduleKeepAlive) // from now on, a helper that dies is started again
        }
        return Bundle()
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object { const val SHELL_UID = 2000 }
}
