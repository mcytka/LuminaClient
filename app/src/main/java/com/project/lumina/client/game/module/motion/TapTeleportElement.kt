package com.project.lumina.client.game.module.motion

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.LocalPlayer
import com.project.lumina.client.game.world.World
import com.project.lumina.client.overlay.SessionStatsOverlay
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket

class TapTeleportElement(iconResId: Int = R.drawable.ic_feather_black_24dp) : Element(
    name = "TapTeleport",
    category = CheatCategory.Motion,
    iconResId,
    displayNameResId = R.string.module_tapteleport_display_name
) {

    private val teleportOffset by floatValue("Offset", 0.0f, -1.0f..5.0f)
    private val debugMode by boolValue("Debug Mode", false)

    private var statsOverlay: SessionStatsOverlay? = null
    private val world: World = World(session) // Предполагаем, что World доступен через session

    override fun onEnabled() {
        super.onEnabled()
        if (isSessionCreated) {
            session.launchOnMain {
                statsOverlay = session.showSessionStatsOverlay(listOf("TapTeleport: Enabled"))
                updateDebugDisplay("Module enabled")
            }
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        statsOverlay?.dismiss()
        statsOverlay = null
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        updateDebugDisplay("Packet received: ${interceptablePacket.packet.javaClass.simpleName}")
        if (!isEnabled) {
            updateDebugDisplay("Module disabled")
            return
        }

        val packet = interceptablePacket.packet
        val localPlayer = session.localPlayer as? LocalPlayer ?: run {
            updateDebugDisplay("No local player!")
            return
        }

        updateDebugDisplay("LocalPlayer: runtimeId=${localPlayer.runtimeEntityId}, pos=${localPlayer.vec3Position}")
        if (localPlayer.movementServerAuthoritative) {
            updateDebugDisplay("Server controls movement!")
        }

        when (packet) {
            is PlayerAuthInputPacket -> {
                updateDebugDisplay("PlayerAuthInputPacket: inputs=${packet.inputData}")
                if (packet.inputData.contains(PlayerAuthInputData.PERFORM_ITEM_INTERACTION)) {
                    val blockPos = Vector3i.from(
                        packet.position.x.toInt(),
                        packet.position.y.toInt(),
                        packet.position.z.toInt()
                    )
                    teleportToBlock(localPlayer, blockPos)
                }
            }
            is InteractPacket -> {
                updateDebugDisplay("InteractPacket: action=${packet.action}")
                if (packet.action == InteractPacket.Action.RIGHT_CLICK_BLOCK) {
                    val blockPos = Vector3i.from(packet.x.toInt(), packet.y.toInt(), packet.z.toInt())
                    teleportToBlock(localPlayer, blockPos)
                }
            }
            is MovePlayerPacket -> {
                updateDebugDisplay("MovePlayerPacket detected: runtimeId=${packet.runtimeEntityId}, pos=${packet.position}")
                // Тестовый триггер для проверки собственных пакетов
                if (packet.runtimeEntityId == localPlayer.runtimeEntityId) {
                    val newPos = Vector3i.from(packet.position.x.toInt(), packet.position.y.toInt(), packet.position.z.toInt())
                    teleportToBlock(localPlayer, newPos)
                }
            }
        }
    }

    private fun teleportToBlock(localPlayer: LocalPlayer, blockPosition: Vector3i) {
        val targetX = blockPosition.x.toFloat() + 0.5f
        val targetZ = blockPosition.z.toFloat() + 0.5f
        val teleportToY = blockPosition.y.toFloat() + 1.0f + teleportOffset

        // Проверка валидности позиции через World
        val blockId = world.getBlockId(targetX.toInt(), teleportToY.toInt(), targetZ.toInt())
        if (blockId == 0) { // Воздух
            val newPosition = Vector3f.from(targetX, teleportToY, targetZ)
            localPlayer.move(newPosition) // Клиентская симуляция
            updateDebugDisplay("Client moved to $newPosition")

            val movePacket = MovePlayerPacket().apply {
                runtimeEntityId = localPlayer.runtimeEntityId
                position = newPosition
                rotation = localPlayer.vec3Rotation
                mode = if (localPlayer.movementServerAuthoritative) MovePlayerPacket.Mode.NORMAL else MovePlayerPacket.Mode.TELEPORT
                onGround = true
            }

            session.serverBound(movePacket)
            updateDebugDisplay("Server teleport sent to $newPosition, mode=${movePacket.mode}")
        } else {
            updateDebugDisplay("Invalid teleport position: blockId=$blockId at $blockPosition")
        }
    }

    private fun updateDebugDisplay(message: String) {
        if (debugMode && statsOverlay != null) {
            statsOverlay?.updateStats(listOf(message))
        }
    }

    private fun sendDebugMessage(message: String) {
        val textPacket = TextPacket().apply {
            type = TextPacket.Type.RAW
            message = message
            isNeedsTranslation = false
            sourceName = "TapTeleport"
            xuid = ""
        }
        try {
            session.clientBound(textPacket)
        } catch (e: Exception) {
            // Игнорируем ошибку без вывода
        }
    }
}
