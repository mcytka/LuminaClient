package com.project.lumina.client.game.module.motion

import com.project.lumina.client.R
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket
import org.cloudburstmc.protocol.bedrock.packet.EntityFallPacket
import kotlin.random.Random

class TapTeleportElement(iconResId: Int = R.drawable.teleport) : Element(
    name = "TapTeleport",
    category = CheatCategory.Motion,
    iconResId,
    displayNameResId = R.string.module_tap_teleport_display_name
) {

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private val enableNoClipAbilitiesPacket = UpdateAbilitiesPacket().apply {
        playerPermission = PlayerPermission.OPERATOR
        commandPermission = CommandPermission.OWNER
        abilityLayers.add(AbilityLayer().apply {
            layerType = AbilityLayer.Type.BASE
            abilitiesSet.addAll(Ability.entries.toTypedArray())
            abilityValues.addAll(
                arrayOf(
                    Ability.BUILD,
                    Ability.MINE,
                    Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS,
                    Ability.ATTACK_PLAYERS,
                    Ability.ATTACK_MOBS,
                    Ability.MAY_FLY,
                    Ability.FLY_SPEED,
                    Ability.WALK_SPEED,
                    Ability.OPERATOR_COMMANDS
                )
            )
            abilityValues.add(Ability.NO_CLIP)
            walkSpeed = 0.1f
            flySpeed = 0.15f
        })
    }

    private val disableNoClipAbilitiesPacket = UpdateAbilitiesPacket().apply {
        playerPermission = PlayerPermission.OPERATOR
        commandPermission = CommandPermission.OWNER
        abilityLayers.add(AbilityLayer().apply {
            layerType = AbilityLayer.Type.BASE
            abilitiesSet.addAll(Ability.entries.toTypedArray())
            abilityValues.addAll(
                arrayOf(
                    Ability.BUILD,
                    Ability.MINE,
                    Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS,
                    Ability.ATTACK_PLAYERS,
                    Ability.ATTACK_MOBS,
                    Ability.FLY_SPEED,
                    Ability.WALK_SPEED,
                    Ability.OPERATOR_COMMANDS
                )
            )
            abilityValues.remove(Ability.NO_CLIP)
            walkSpeed = 0.1f
        })
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet

        if (packet is InventoryTransactionPacket) {
            // Handle tap teleportation only for ITEM_USE transaction type
            if (packet.transactionType != org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType.ITEM_USE) {
                return
            }

            val pos = packet.blockPosition
            val face = packet.blockFace

            // Calculate base position centered on block with small random offset for stealth
            var x = pos.x.toFloat() + 0.5f + Random.nextFloat() * 0.1f - 0.05f
            var y = pos.y.toFloat() + Random.nextFloat() * 0.1f - 0.05f
            var z = pos.z.toFloat() + 0.5f + Random.nextFloat() * 0.1f - 0.05f

            // Adjust position based on face
            when (face) {
                0 -> y += 1f // bottom face, move up (teleport on top of block)
                1 -> y += 1f // top face, move up (teleport on top of block)
                2 -> z -= 1f // north face
                3 -> z += 1f // south face
                4 -> x -= 1f // west face
                5 -> x += 1f // east face
            }

            val targetPos = Vector3f.from(x, y + 2f, z) // add 2 to y for player height offset

            // Send benign packets to mask teleport event
            // sendBenignPackets() // Disabled to avoid enabling noclip

            coroutineScope.launch {
                // Simulate intermediate movement packets before teleport
                simulateIntermediateMovement(targetPos)

                // Teleport player
                teleportTo(targetPos)

                // Send fall damage reset packet to avoid fall damage
                sendFallDamageReset()
            }
        }
    }

    private suspend fun simulateIntermediateMovement(targetPos: Vector3f) {
        val currentPos = session.localPlayer.position
        val distance = currentPos.distance(targetPos)
        val steps = kotlin.math.ceil(distance / 1.0).toInt().coerceAtLeast(1) // 1 block per step

        for (i in 1 until steps) {
            val t = i.toFloat() / steps
            val intermediatePos = Vector3f.from(
                currentPos.x + (targetPos.x - currentPos.x) * t,
                currentPos.y + (targetPos.y - currentPos.y) * t,
                currentPos.z + (targetPos.z - currentPos.z) * t
            )
            sendMovePacket(intermediatePos)
            kotlinx.coroutines.delay(10) // minimal delay to simulate movement but keep speed
        }
    }

    private fun sendMovePacket(position: Vector3f) {
        val movePlayerPacket = MovePlayerPacket().apply {
            runtimeEntityId = session.localPlayer.runtimeEntityId
            this.position = position // Используем параметр position, переданный в функцию
            this.rotation = session.localPlayer.vec3Rotation
            mode = MovePlayerPacket.Mode.NORMAL
            onGround = true
            ridingRuntimeEntityId = 0
            tick = session.localPlayer.tickExists
        }
        session.clientBound(movePlayerPacket)
    }

    private fun sendBenignPackets() {
        coroutineScope.launch {
            // Example: send a harmless UpdateAbilitiesPacket to mask teleport
            // Disabled to avoid enabling noclip
            // enableNoClipAbilitiesPacket.uniqueEntityId = session.localPlayer.uniqueEntityId
            // session.clientBound(enableNoClipAbilitiesPacket)
            // Could add more benign packets here if needed
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

    private fun sendFallDamageReset() {
        val fallPacket = EntityFallPacket().apply {
            runtimeEntityId = session.localPlayer.runtimeEntityId
            fallDistance = 0f
            inVoid = false
        }
        session.clientBound(fallPacket)
    }
}
