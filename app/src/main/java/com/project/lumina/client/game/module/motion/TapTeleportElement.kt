package com.project.lumina.client.game.module.motion

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.LocalPlayer
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

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        // Тестовая отладка вызова
        sendDebugMessage("§b[TapTeleport] §r§abeforePacketBound called")
        if (!isEnabled) {
            sendDebugMessage("§b[TapTeleport] §r§cModule disabled")
            return
        }

        val packet = interceptablePacket.packet
        val localPlayer = session.localPlayer as? LocalPlayer ?: run {
            sendDebugMessage("§b[TapTeleport] §r§cNo local player!")
            return
        }

        sendDebugMessage("§b[TapTeleport] §r§aLocalPlayer: runtimeId=${localPlayer.runtimeEntityId}, pos=${localPlayer.vec3Position}")
        if (localPlayer.movementServerAuthoritative) {
            sendDebugMessage("§b[TapTeleport] §r§cServer controls movement!")
        }

        when (packet) {
            is PlayerAuthInputPacket -> {
                sendDebugMessage("§b[TapTeleport] §r§aPlayerAuthInputPacket: inputs=${packet.inputData}")
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
                sendDebugMessage("§b[TapTeleport] §r§aInteractPacket: action=${packet.action}")
                if (packet.action == InteractPacket.Action.RIGHT_CLICK_BLOCK) {
                    val blockPos = Vector3i.from(packet.x.toInt(), packet.y.toInt(), packet.z.toInt())
                    teleportToBlock(localPlayer, blockPos)
                }
            }
        }
    }

    private fun teleportToBlock(localPlayer: LocalPlayer, blockPosition: Vector3i) {
        val targetX = blockPosition.x.toFloat() + 0.5f
        val targetZ = blockPosition.z.toFloat() + 0.5f
        val teleportToY = blockPosition.y.toFloat() + 1.0f + teleportOffset // Убрали eyeHeight

        val newPosition = Vector3f.from(targetX, teleportToY, targetZ)
        val movePacket = MovePlayerPacket().apply {
            runtimeEntityId = localPlayer.runtimeEntityId
            position = newPosition
            rotation = localPlayer.vec3Rotation
            mode = if (localPlayer.movementServerAuthoritative) MovePlayerPacket.Mode.NORMAL else MovePlayerPacket.Mode.TELEPORT
            onGround = true
        }

        // Попробуем клиентскую симуляцию
        localPlayer.move(newPosition)
        sendDebugMessage("§b[TapTeleport] §r§aClient moved to $newPosition")

        // Отправка на сервер
        session.serverBound(movePacket)
        sendDebugMessage("§b[TapTeleport] §r§aServer teleport sent to $newPosition, mode=${movePacket.mode}")
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

    override fun onEnabled() {
        super.onEnabled()
        sendDebugMessage("§b[TapTeleport] §r§aModule enabled")
    }

    override fun onDisabled() {
        super.onDisabled()
        sendDebugMessage("§b[TapTeleport] §r§aModule disabled")
    }
}
