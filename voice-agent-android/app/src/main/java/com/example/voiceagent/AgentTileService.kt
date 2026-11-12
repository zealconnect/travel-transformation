package com.example.voiceagent

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile (swipe down from the top): tap to talk. Green while always-listening is enabled. */
class AgentTileService : TileService() {

    companion object {
        fun refresh(ctx: Context) {
            try { requestListeningState(ctx, ComponentName(ctx, AgentTileService::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onStartListening() = update()

    override fun onClick() = talk()

    /** Tap the tile = talk now. */
    private fun talk() {
        val pi = android.app.PendingIntent.getActivity(
            this, 1, android.content.Intent(this, TalkActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        startActivityAndCollapse(pi)
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
            !serviceOn -> "Tap to talk"
            Prefs.enabled(this) -> "Listening · tap to talk"
            else -> "Tap to talk"
        }
        tile.updateTile()
    }
}
