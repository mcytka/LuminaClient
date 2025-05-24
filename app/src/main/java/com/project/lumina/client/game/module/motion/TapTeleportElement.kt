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
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket

class TapTeleportElement(iconResId: Int = R.drawable.teleport) : Element(
    name = "TapTeleport",
    category = CheatCategory.Motion,
    iconResId,
    displayNameResId = R.string.module_tap_teleport_display_name
) {

    private var lastTapPosition: Vector3f? = null
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
                    0 -> Vector3f.from(0f, 2f, 0f)  // bottom - teleport on top of block with offset 2
                    1 -> Vector3f.from(0f, 2f, 0f)  // top - teleport on top of block with offset 2
                    2 -> Vector3f.from(0f, 0f, -1f) // north
                    3 -> Vector3f.from(0f, 0f, 1f)  // south
                    4 -> Vector3f.from(-1f, 0f, 0f) // west
                    5 -> Vector3f.from(1f, 0f, 0f)  // east
                    else -> Vector3f.from(0f, 2f, 0f) // default top
                }

                val targetPos = Vector3f.from(x + offset.x, y + offset.y, z + offset.z)
                lastTapPosition = targetPos
                enableNoClip()
                teleportTo(lastTapPosition!!)
                lastTapPosition = null
                scheduleDisableNoClip()
            }
        }
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
            delay(500) // disable noclip after 0.5 seconds to better match teleportation duration
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
