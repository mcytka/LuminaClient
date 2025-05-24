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
                action.actionType == PlayerActionType.BLOCK_INTERACT
            }

            blockAction?.let { action ->
                val pos = action.blockPosition
                lastTapPosition = Vector3f.from(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat())
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
