package com.xhermes

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class SettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val context = context ?: return null
        val path = uri.path ?: ""

        // Hooked packages status query
        if (path.contains("hooked_status")) {
            val prefs = context.getSharedPreferences("com.xhermes_hooked", Context.MODE_PRIVATE)
            val packages = prefs.getStringSet("hooked_packages", emptySet()) ?: emptySet()
            val cursor = MatrixCursor(arrayOf("package_name", "last_seen"))
            for (pkg in packages) {
                val ts = prefs.getLong("ts_$pkg", 0L)
                cursor.addRow(arrayOf(pkg, ts.toString()))
            }
            return cursor
        }

        // Default: return configs
        val prefs = context.getSharedPreferences("com.xhermes_preferences", Context.MODE_PRIVATE)
        val configsJson = prefs.getString("configs_json", "[]") ?: "[]"
        val cursor = MatrixCursor(arrayOf("configs_json"))
        cursor.addRow(arrayOf(configsJson))
        return cursor
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.dir/vnd.com.xhermes.provider.settings"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val context = context ?: return null
        val path = uri.path ?: ""

        // Register a hooked package
        if (path.contains("register_hook") && values != null) {
            val pkg = values.getAsString("package_name") ?: return null
            val prefs = context.getSharedPreferences("com.xhermes_hooked", Context.MODE_PRIVATE)
            val existing = prefs.getStringSet("hooked_packages", mutableSetOf()) ?: mutableSetOf()
            val updated = existing.toMutableSet()
            updated.add(pkg)
            prefs.edit()
                .putStringSet("hooked_packages", updated)
                .putLong("ts_$pkg", System.currentTimeMillis())
                .putBoolean("module_active", true)
                .commit()
            return Uri.parse("content://com.xhermes.provider/hooked_status/$pkg")
        }

        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
