package com.project.lumina.client.game.module.motion

import com.project.lumina.client.R
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket

class TapTeleportElement(iconResId: Int = R.drawable.teleport) : Element(
    name = "TapTeleport",
    category = CheatCategory.Motion,
    iconResId,
    displayNameResId = R.string.module_tap_teleport_display_name
) {

    private var noClipEnabled = false
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private var noClipJob: Job? = null

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

        if (packet is PlayerAuthInputPacket) {
            // Check for inputData flags indicating interaction
            if (!packet.inputData.contains(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS) &&
                !packet.inputData.contains(PlayerAuthInputData.PERFORM_ITEM_INTERACTION)) {
                return
            }

            val blockAction = packet.playerActions.firstOrNull { action ->
                action.action == PlayerActionType.BLOCK_INTERACT ||
                action.action == PlayerActionType.START_BREAK
            }

            blockAction?.let { action ->
                val pos = action.blockPosition
                val face = action.face

                // Calculate base position centered on block
                var x = pos.x.toFloat() + 0.5f
                var y = pos.y.toFloat()
                var z = pos.z.toFloat() + 0.5f

                // Adjust position based on face
                when (face) {
                    0 -> y += 1f // bottom face, move up (teleport on top of block)
                    1 -> y += 1f // top face, move down (teleport below block)
                    2 -> z -= 1f // north face
                    3 -> z += 1f // south face
                    4 -> x -= 1f // west face
                    5 -> x += 1f // east face
                }

                // Check if block above target position is air (empty)
                val blockAboveIsAir = isBlockAir(pos.x, pos.y + 1, pos.z)

                // If block above is not air, adjust y to avoid getting stuck
                if (!blockAboveIsAir) {
                    y += 1f
                }

                val targetPos = Vector3f.from(x, y + 2f, z) // add 2 to y for player height offset

                // Temporarily disable noclip for testing
                // enableNoClip()
                teleportTo(targetPos)
                // scheduleDisableNoClip()
            }
        }
    }

    private fun isBlockAir(x: Int, y: Int, z: Int): Boolean {
        // Placeholder: Implement actual block check logic here
        // Return true if block at (x, y, z) is air/empty, false otherwise
        return true
    }

    private fun enableNoClip() {
        if (!noClipEnabled) {
            enableNoClipAbilitiesPacket.uniqueEntityId = session.localPlayer.uniqueEntityId
            session.clientBound(enableNoClipAbilitiesPacket)
            noClipEnabled = true
        }
    }

    private fun disableNoClip() {
        if (noClipEnabled) {
            disableNoClipAbilitiesPacket.uniqueEntityId = session.localPlayer.uniqueEntityId
            session.clientBound(disableNoClipAbilitiesPacket)
            noClipEnabled = false
        }
    }

    private fun scheduleDisableNoClip() {
        noClipJob?.cancel()
        noClipJob = coroutineScope.launch {
            delay(500) // disable noclip after 0.5 seconds
            disableNoClip()
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
