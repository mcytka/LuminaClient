// ESPElement.kt
package com.project.lumina.client.game.module.misc

import android.util.Log
import com.project.lumina.client.R
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.game.entity.*
import com.project.lumina.client.overlay.ESPOverlay
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

class ESPElement : Element(
    name = "esp_module",
    category = CheatCategory.Misc,
    displayNameResId = R.string.module_esp_display_name
) {
    private var playersOnly by boolValue("Players", true)
    private var rangeValue by floatValue("Range", 25f, 2f..500f)
    private var multiTarget = true
    private var maxTargets = 100
    private var use3dBoxes by boolValue("Use 3D Boxes", false)
    private var showPlayerInfo by boolValue("Show Player Info", true)

    private var showDisappearedPlayers by boolValue("Show Disappeared Players", true)

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
        // <<< ИЗМЕНЕНИЕ: Используем session (т.е. NetBound) для очистки позиций >>>
        if (isSessionCreated) { // Проверяем, инициализирована ли сессия
            session.clearLastKnownPositions()
        }
        // <<< КОНЕЦ ИЗМЕНЕНИЯ >>>
        Log.d("ESPModule", "ESP Overlay disabled")
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return

        val currentLocalPlayer = session.localPlayer
        if (currentLocalPlayer != null) {
            val position = currentLocalPlayer.vec3Position
            val rotationYaw = currentLocalPlayer.rotationYaw
            val rotationPitch = currentLocalPlayer.rotationPitch

            ESPOverlay.updatePlayerData(position, rotationPitch, rotationYaw)
            ESPOverlay.updateEntities(getEntitiesToRender())
            ESPOverlay.setFov(60.0f)
            ESPOverlay.setUse3dBoxes(use3dBoxes)
            ESPOverlay.setShowPlayerInfo(showPlayerInfo)
        }
    }

    private fun getEntitiesToRender(): List<Entity> {
        // <<< ИЗМЕНЕНИЕ: Получаем сущности через session (NetBound) >>>
        if (!isSessionCreated) return emptyList() // Добавлена проверка на инициализацию session
        val allEntities = session.getAllEntitiesForEsp()
        // <<< КОНЕЦ ИЗМЕНЕНИЯ >>>

        return allEntities
            .filter { entity ->
                if (entity is LocalPlayer && entity.uniqueEntityId == session.localPlayer.uniqueEntityId) {
                    false
                } else {
                    if (!showDisappearedPlayers && entity.isDisappeared) {
                        false
                    } else {
                        val distance = if (entity.isDisappeared) {
                            entity.lastKnownPosition.distance(session.localPlayer.vec3Position)
                        } else {
                            entity.distance(session.localPlayer)
                        }
                        distance < rangeValue && entity.isTarget()
                    }
                }
            }
            .sortedBy { entity ->
                if (entity.isDisappeared) {
                    entity.lastKnownPosition.distance(session.localPlayer.vec3Position)
                } else {
                    entity.distance(session.localPlayer)
                }
            }
            .take(maxTargets)
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
