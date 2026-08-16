package com.omnieditor.app

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick-settings tile scaffold for "Compare Clipboard" (F-16).
 *
 * Tile state is always INACTIVE (scaffold — the full clipboard-diff flow
 * is implemented in the editor session). Tapping the tile launches the app
 * with ACTION_COMPARE_CLIPBOARD so the NavGraph can route to the correct
 * entry point when that flow is wired.
 *
 * Requires API 24+. The service element in the manifest is gated by the
 * QS_TILE intent filter so it is invisible to older launchers.
 */
@RequiresApi(Build.VERSION_CODES.N)
class CompareClipboardTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_compare_clipboard)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(AppShortcuts.ACTION_COMPARE_CLIPBOARD, null, this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivityAndCollapse(intent)
    }
}
