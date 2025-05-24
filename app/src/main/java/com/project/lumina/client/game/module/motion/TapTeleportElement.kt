package com.project.lumina.client.game.module.motion

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.Value
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

    // Определяем настройки как экземпляры Value
    private val teleportOffset = Value("Offset", 0.0f, -1.0f..5.0f).also { values.add(it) }
    private val debugMode = Value("Debug Mode", false).also { values.add(it) }

    init {
        // Убедимся, что значения добавлены в список
        if (!values.contains(teleportOffset)) values.add(teleportOffset)
        if (!values.contains(debugMode)) values.add(debugMode)
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        val localPlayer = session.localPlayer ?: return

        if (packet is PlayerActionPacket && packet.action == PlayerActionType.START_BREAK) {
            val blockPosition = packet.blockPosition
            val face = packet.face

            val playerY = localPlayer.vec3Position.y
            val eyeHeight = localPlayer.eyeHeight ?: 1.6f // Значение по умолчанию

            val targetX = blockPosition.x.toFloat() + 0.5f
            val targetZ = blockPosition.z.toFloat() + 0.5f
            var teleportToY = when (face) {
                1 -> blockPosition.y.toFloat() + 1.0f // Top face
                0 -> blockPosition.y.toFloat() - eyeHeight - 0.1f // Bottom face
                else -> blockPosition.y.toFloat() + 1.0f // Side faces
            }.let { if (it < playerY - 2f) playerY else it } // Защита от падения

            teleportToY += teleportOffset.value // Доступ к значению через .value

            val newPosition = Vector3f.from(targetX, teleportToY, targetZ)

            val movePlayerPacket = MovePlayerPacket().apply {
                runtimeEntityId = localPlayer.uniqueEntityId
                position = newPosition
                rotation = localPlayer.vec3Rotation
                mode = MovePlayerPacket.Mode.TELEPORT
                onGround = true
            }

            session.serverBound(movePlayerPacket)
            if (debugMode.value) { // Доступ к значению через .value
                session.displayClientMessage("§l§b[TapTeleport] §r§aTeleported to $newPosition, face: $face, offset: ${teleportOffset.value}")
            }

            interceptablePacket.intercept()
        }
    }
}
