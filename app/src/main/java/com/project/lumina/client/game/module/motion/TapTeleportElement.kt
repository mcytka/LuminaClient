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
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

class TapTeleportElement(iconResId: Int = R.drawable.ic_feather_black_24dp) : Element(
    name = "TapTeleport",
    category = CheatCategory.Motion,
    iconResId,
    displayNameResId = R.string.module_tapteleport_display_name
) {

    private val teleportOffset by floatValue("Offset", 0.0f, -1.0f..5.0f)
    private val debugMode by boolValue("Debug Mode", false)

    override fun onEnabled() {
        super.onEnabled()
        if (isSessionCreated) {
            session.displayClientMessage("TapTeleport: Enabled")
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        if (isSessionCreated) {
            session.displayClientMessage("TapTeleport: Disabled")
        }
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return

        val packet = interceptablePacket.packet
        val localPlayer = session.localPlayer as? LocalPlayer ?: run {
            session.displayClientMessage("Error: No local player!")
            return
        }

        if (debugMode) {
            session.displayClientMessage("Packet: ${packet.javaClass.simpleName}, Player pos: ${localPlayer.vec3Position}")
        }
        if (localPlayer.movementServerAuthoritative) {
            session.displayClientMessage("Warning: Server controls movement!")
        }

        when (packet) {
            is PlayerAuthInputPacket -> {
                if (debugMode) {
                    session.displayClientMessage("PlayerAuthInputPacket: inputs=${packet.inputData}, pos=${packet.position}")
                }
                if (packet.inputData.contains(PlayerAuthInputData.PERFORM_ITEM_INTERACTION)) {
                    val blockPos = Vector3i.from(packet.position.x.toInt(), packet.position.y.toInt() - 1, packet.position.z.toInt())
                    teleportToBlock(localPlayer, blockPos)
                }
                // Обновление позиции игрока
                session.updatePlayerPosition(packet.position.x, packet.position.z)
                val yawRadians = (packet.rotation.y * Math.PI / 180).toFloat()
                session.updatePlayerRotation(yawRadians)
            }
            is InteractPacket -> {
                if (debugMode) {
                    session.displayClientMessage("InteractPacket: action=${packet.action}, pos=${packet.position}, targetId=${packet.targetRuntimeEntityId}")
                }
                if (packet.action == InteractPacket.Action.INTERACT) {
                    val blockPos = Vector3i.from(packet.position.x.toInt(), packet.position.y.toInt() - 1, packet.position.z.toInt())
                    teleportToBlock(localPlayer, blockPos)
                }
            }
            is MoveEntityAbsolutePacket -> {
                if (debugMode) {
                    session.displayClientMessage("MoveEntityAbsolutePacket: runtimeId=${packet.runtimeEntityId}, pos=${packet.position}")
                }
                if (packet.runtimeEntityId == localPlayer.runtimeEntityId) {
                    val newPos = Vector3i.from(packet.position.x.toInt(), packet.position.y.toInt(), packet.position.z.toInt())
                    teleportToBlock(localPlayer, newPos)
                }
            }
            is MovePlayerPacket -> {
                if (debugMode) {
                    session.displayClientMessage("MovePlayerPacket: runtimeId=${packet.runtimeEntityId}, pos=${packet.position}")
                }
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

        val newPosition = Vector3f.from(targetX, teleportToY, targetZ)
        // Проверка валидности позиции через World
        val blockId = session.world.getBlockIdAt(blockPosition)
        if (blockId == 0) { // Воздух
            localPlayer.move(newPosition) // Клиентская симуляция
            if (debugMode) {
                session.displayClientMessage("Client moved to $newPosition")
            }

            val movePacket = MovePlayerPacket().apply {
                runtimeEntityId = localPlayer.runtimeEntityId
                position = newPosition
                rotation = localPlayer.vec3Rotation
                mode = if (localPlayer.movementServerAuthoritative) MovePlayerPacket.Mode.NORMAL else MovePlayerPacket.Mode.TELEPORT
                onGround = true
            }

            session.serverBound(movePacket)
            if (debugMode) {
                session.displayClientMessage("Server teleport sent to $newPosition, mode=${movePacket.mode}")
            }
        } else {
            if (debugMode) {
                session.displayClientMessage("Invalid teleport position: blockId=$blockId at $blockPosition")
            }
        }
    }
}
