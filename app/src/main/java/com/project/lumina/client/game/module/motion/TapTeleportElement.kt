package com.project.lumina.client.game.module.motion

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket

class TapTeleportElement(iconResId: Int = R.drawable.ic_feather_black_24dp) : Element(
    name = "TapTeleport",
    category = CheatCategory.Motion,
    iconResId,
    displayNameResId = R.string.module_tapteleport_display_name
) {

    private val teleportOffset by floatValue("Offset", 0.0f, -1.0f..5.0f)
    private val debugMode by boolValue("Debug Mode", false)

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        val localPlayer = session.localPlayer ?: run {
            if (debugMode) session.displayClientMessage("§l§b[TapTeleport] §r§cNo local player!")
            return
        }

        if (packet is PlayerActionPacket) {
            if (debugMode) {
                session.displayClientMessage("§l§b[TapTeleport] §r§aDetected action: ${packet.action}, pos: ${packet.blockPosition}, face: ${packet.face}")
            }

            // Триггер на взаимодействие с блоком или использование предмета
            if (packet.action == PlayerActionType.BLOCK_INTERACT || packet.action == PlayerActionType.START_ITEM_USE_ON) {
                val blockPosition = packet.blockPosition
                val face = packet.face

                val targetX = blockPosition.x.toFloat() + 0.5f
                val targetZ = blockPosition.z.toFloat() + 0.5f
                val baseY = blockPosition.y.toFloat()
                val teleportToY = when (face) {
                    1 -> baseY + 1.0f + teleportOffset // Верх
                    0 -> baseY - 1.0f + teleportOffset // Низ
                    else -> baseY + 1.0f + teleportOffset // Боковые
                }.coerceAtLeast(localPlayer.vec3Position.y - 2f) // Защита от падения

                val newPosition = Vector3f.from(targetX, teleportToY, targetZ)

                val movePacket = MovePlayerPacket().apply {
                    runtimeEntityId = localPlayer.uniqueEntityId
                    position = newPosition
                    rotation = localPlayer.vec3Rotation
                    mode = MovePlayerPacket.Mode.TELEPORT
                    onGround = true
                }

                session.serverBound(movePacket)
                if (debugMode) {
                    session.displayClientMessage("§l§b[TapTeleport] §r§aTeleported to $newPosition")
                }
            }
        }
    }

    override fun onEnabled() {
        super.onEnabled()
        if (debugMode) {
            session.displayClientMessage("§l§b[TapTeleport] §r§aModule enabled")
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        if (debugMode) {
            session.displayClientMessage("§l§b[TapTeleport] §r§aModule disabled")
        }
    }
}
