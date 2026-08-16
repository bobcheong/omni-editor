package com.omnieditor.app

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Registers dynamic app shortcuts (F-07 / R-33).
 *
 * Dynamic shortcuts are created at runtime (call from Application.onCreate or
 * first MainActivity.onCreate). They supplement the static shortcuts in
 * res/xml/shortcuts.xml and can be updated or removed programmatically.
 *
 * Two shortcuts:
 *  - "New Compare"  → com.omnieditor.action.NEW_COMPARE
 *  - "Open Editor"  → com.omnieditor.action.OPEN_EDITOR
 */
object AppShortcuts {

    const val ACTION_NEW_COMPARE = "com.omnieditor.action.NEW_COMPARE"
    const val ACTION_OPEN_EDITOR = "com.omnieditor.action.OPEN_EDITOR"
    const val ACTION_COMPARE_CLIPBOARD = "com.omnieditor.action.COMPARE_CLIPBOARD"

    fun register(context: Context) {
        val newCompare = ShortcutInfoCompat.Builder(context, "dynamic_new_compare")
            .setShortLabel(context.getString(R.string.shortcut_new_compare))
            .setLongLabel(context.getString(R.string.shortcut_new_compare_long))
            .setIcon(IconCompat.createWithResource(context, android.R.drawable.ic_menu_view))
            .setIntent(
                Intent(ACTION_NEW_COMPARE, null, context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            .build()

        val openEditor = ShortcutInfoCompat.Builder(context, "dynamic_open_editor")
            .setShortLabel(context.getString(R.string.shortcut_open_file))
            .setLongLabel(context.getString(R.string.shortcut_open_file_long))
            .setIcon(IconCompat.createWithResource(context, android.R.drawable.ic_menu_edit))
            .setIntent(
                Intent(ACTION_OPEN_EDITOR, null, context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            .build()

        ShortcutManagerCompat.setDynamicShortcuts(context, listOf(newCompare, openEditor))
    }
}
