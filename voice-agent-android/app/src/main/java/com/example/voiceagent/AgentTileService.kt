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
        Prefs.setEnabled(this, !Prefs.enabled(this))
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val serviceOn = AgentAccessibilityService.instance != null
        tile.state = when {
            !serviceOn -> Tile.STATE_UNAVAILABLE
            Prefs.enabled(this) -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when {
            !serviceOn -> "Turn on in Accessibility"
            Prefs.enabled(this) -> "Listening"
            else -> "Disabled"
        }
        tile.updateTile()
    }
}
