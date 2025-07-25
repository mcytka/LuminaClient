package com.project.lumina.client.game.module.misc

import android.util.Log
import com.project.lumina.client.R
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.game.entity.*
import com.project.lumina.client.overlay.ESPOverlay
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket // Этот импорт остается, если он используется для других частей модуля Fly/ESP, но не для логики обновления ESP.

class ESPElement : Element(
    name = "esp_module",
    category = CheatCategory.Misc,
    displayNameResId = R.string.module_esp_display_name
) {
    private var playersOnly by boolValue("Players", true)
    private var rangeValue by floatValue("Range", 10f, 2f..100f)
    private var multiTarget = true
    private var maxTargets = 100

    override fun onEnabled() {
        super.onEnabled()
        try {
            ESPOverlay.showOverlay()
            Log.d("ESPModule", "ESP Overlay enabled")
        } catch (e: Exception) {
            Log.e("ESPModule", "Enable error: ${e.message}")
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        ESPOverlay.dismissOverlay()
        Log.d("ESPModule", "ESP Overlay disabled")
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        // Убрано условие `interceptablePacket.packet !is PlayerAuthInputPacket`
        // Теперь ESP будет обновляться при каждом проходящем пакете.
        if (!isEnabled || !isSessionCreated) return

        val currentLocalPlayer = session.localPlayer
        if (currentLocalPlayer != null) {
            // Используем актуальные позиции и ротации из session.localPlayer
            val position = currentLocalPlayer.vec3Position
            val rotationYaw = currentLocalPlayer.yaw
            val rotationPitch = currentLocalPlayer.pitch

            ESPOverlay.updatePlayerData(position, rotationPitch, rotationYaw)
            ESPOverlay.updateEntities(searchForClosestEntities())
            ESPOverlay.setFov(60.0f) // Используйте ваше точное значение FOV, если оно фиксировано.
        }
    }

    private fun searchForClosestEntities(): List<Entity> {
        val entities = session.level.entityMap.values
            .mapNotNull {
                val distance = it.distance(session.localPlayer)
                if (distance < rangeValue && it.isTarget()) Pair(it, distance) else null
            }
            .sortedBy { it.second }
            .map { it.first }

        return if (multiTarget) entities.take(maxTargets) else entities.take(1)
    }

    private fun Entity.isTarget(): Boolean {
        return when (this) {
            is LocalPlayer -> false
            is Player -> playersOnly && !isBot()
            else -> false
        }
    }

    private fun Player.isBot(): Boolean {
        if (this is LocalPlayer) return false
        val playerList = session.level.playerMap[this.uuid] ?: return true
        return playerList.name.isBlank()
    }
}
