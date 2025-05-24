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

    private val teleportOffset by floatValue("Offset", 0.0f, -1.0f..5.0f) // Expanded range
    private val debugMode by booleanValue("Debug Mode", false) // Enable debug logs

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        val localPlayer = session.localPlayer ?: return

        if (packet is PlayerActionPacket && packet.action == PlayerActionType.START_BREAK) {
            val blockPosition = packet.blockPosition
            val face = packet.face

            val playerY = localPlayer.vec3Position.y
            val eyeHeight = localPlayer.eyeHeight ?: 1.6f // Default eye height if not available

            val targetX = blockPosition.x.toFloat() + 0.5f
            val targetZ = blockPosition.z.toFloat() + 0.5f
            var teleportToY = when (face) {
                1 -> blockPosition.y.toFloat() + 1.0f // Top face
                0 -> blockPosition.y.toFloat() - eyeHeight - 0.1f // Bottom face (with safety check)
                else -> blockPosition.y.toFloat() + 1.0f // Side faces
            }.let { if (it < playerY - 2f) playerY else it } // Prevent teleporting too low

            teleportToY += teleportOffset.value

            val newPosition = Vector3f.from(targetX, teleportToY, targetZ)

            val movePlayerPacket = MovePlayerPacket().apply {
                runtimeEntityId = localPlayer.uniqueEntityId
                position = newPosition
                rotation = localPlayer.vec3Rotation
                mode = MovePlayerPacket.Mode.TELEPORT
                onGround = true
                // Уберите tick или уточните, как получить актуальный тик
            }

            session.serverBound(movePlayerPacket)
            if (debugMode) {
                session.displayClientMessage("§l§b[TapTeleport] §r§aTeleported to $newPosition, face: $face, offset: $teleportOffset")
            }

            interceptablePacket.intercept()
        }
    }
}
