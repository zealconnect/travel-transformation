package com.example.voiceagent

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile (swipe down from the top): Enable / Disable always-listening. */
class AgentTileService : TileService() {

    companion object {
        fun refresh(ctx: Context) {
            try { requestListeningState(ctx, ComponentName(ctx, AgentTileService::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onStartListening() = update()

    override fun onClick() {
        if (AgentService.instance == null) {
            // Not running yet: open the app, which starts it.
            val pi = android.app.PendingIntent.getActivity(
                this, 0, android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
            return
        }
        Prefs.setEnabled(this, !Prefs.enabled(this))
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val serviceOn = AgentService.instance != null
        tile.state = when {
            !serviceOn -> Tile.STATE_INACTIVE
            Prefs.enabled(this) -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when {
            !serviceOn -> "Tap to start"
            Prefs.enabled(this) -> "Listening"
            else -> "Disabled"
        }
        tile.updateTile()
    }
}
