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

    private val teleportOffset = floatValue("Offset", 0.0f, 0.0f..2.0f) // Offset from target position, 0.0f by default

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return // Module must be enabled to work

        val packet = interceptablePacket.packet
        val localPlayer = session.localPlayer ?: return // Ensure localPlayer exists

        if (packet is PlayerActionPacket) {
            // We are looking for an action that indicates a "tap" on a block
            if (packet.action == PlayerActionType.START_BREAK) {
                val blockPosition = packet.blockPosition
                val face = packet.face

                // Get current player position for relative comparison
                val playerY = localPlayer.vec3Position.y
                val playerEyeHeight = localPlayer.getEyeHeight() // Use the getter method

                // Base teleportation coordinates - center of the block
                val targetX = blockPosition.x.toFloat() + 0.5f
                val targetZ = blockPosition.z.toFloat() + 0.5f

                var teleportToY: Float

                when (face) {
                    // Tap on top face (TOP_FACE) - teleport to the top surface of the block
                    1 -> teleportToY = blockPosition.y.toFloat() + 1.0f
                    // Tap on bottom face (BOTTOM_FACE) - teleport under the block
                    0 -> teleportToY = blockPosition.y.toFloat() - playerEyeHeight - 0.1f
                    else -> { // Tap on side face (SIDE_FACES)
                        // If the block is significantly above the player (above head)
                        if (blockPosition.y.toFloat() > playerY + playerEyeHeight) {
                            // Teleport under the block
                            teleportToY = blockPosition.y.toFloat() - playerEyeHeight - 0.1f
                        } else {
                            // Otherwise, teleport to the same Y-level as the base of the block
                            teleportToY = blockPosition.y.toFloat()
                        }
                    }
                }

                // Apply the offset
                teleportToY += teleportOffset.value

                val newPosition = Vector3f.from(targetX, teleportToY, targetZ)

                // Create a teleportation packet
                val movePlayerPacket = MovePlayerPacket().apply {
                    runtimeEntityId = localPlayer.uniqueEntityId
                    position = newPosition
                    rotation = localPlayer.vec3Rotation // Maintain current rotation
                    mode = MovePlayerPacket.Mode.TELEPORT // Use teleportation mode
                    onGround = true // Simulate landing to bypass fall damage
                    tick = localPlayer.tickExists // Use current tick
                }

                session.serverBound(movePlayerPacket) // Send packet to the server

                interceptablePacket.intercept() // Intercept original action packet
            }
        }
    }
}
