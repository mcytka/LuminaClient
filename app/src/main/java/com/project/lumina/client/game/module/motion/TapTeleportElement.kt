package com.project.lumina.client.game.module.motion

import com.project.lumina.client.R
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType

class TapTeleportElement(iconResId: Int = R.drawable.teleport) : Element(
    name = "TapTeleport",
    category = CheatCategory.Motion,
    iconResId,
    displayNameResId = R.string.module_tap_teleport_display_name
) {

    private var lastTapPosition: Vector3f? = null

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet

        if (packet is PlayerAuthInputPacket) {
            // Parse playerActions to find block interaction
            val blockAction = packet.playerActions.firstOrNull { action ->
                action.action == PlayerActionType.BLOCK_INTERACT || 
                action.action == PlayerActionType.START_BREAK
            }

            blockAction?.let { action ->
                val pos = action.blockPosition
                val x = pos.x.toFloat()
                val y = pos.y.toFloat()
                val z = pos.z.toFloat()

                // Face values: 0=bottom, 1=top, 2=north, 3=south, 4=west, 5=east
                val offset = when (action.face) {
                    0 -> Vector3f.from(0f, 1f, 0f)  // bottom - teleport on top of block
                    1 -> Vector3f.from(0f, 0.1f, 0f)  // top - teleport slightly above block position
                    2 -> Vector3f.from(0f, 0f, -1f) // north
                    3 -> Vector3f.from(0f, 0f, 1f)  // south
                    4 -> Vector3f.from(-1f, 0f, 0f) // west
                    5 -> Vector3f.from(1f, 0f, 0f)  // east
                    else -> Vector3f.from(0f, 1f, 0f) // default top
                }

                val targetPos = Vector3f.from(x + offset.x, y + offset.y, z + offset.z)
                lastTapPosition = targetPos
                teleportTo(lastTapPosition!!)
                lastTapPosition = null
            }
        }
    }

    private fun teleportTo(position: Vector3f) {
        val movePlayerPacket = MovePlayerPacket().apply {
            runtimeEntityId = session.localPlayer.runtimeEntityId
            this.position = position
            this.rotation = session.localPlayer.vec3Rotation
            mode = MovePlayerPacket.Mode.NORMAL
            onGround = true
            ridingRuntimeEntityId = 0
            tick = session.localPlayer.tickExists
        }

        session.clientBound(movePlayerPacket)
    }
}
