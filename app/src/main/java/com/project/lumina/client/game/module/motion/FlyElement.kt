package com.project.lumina.client.game.module.motion

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.RequestAbilityPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket
import org.cloudburstmc.math.vector.Vector2f 
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin

class FlyElement(iconResId: Int = R.drawable.ic_feather_black_24dp) : Element(
    name = "Fly",
    category = CheatCategory.Motion,
    iconResId,
    displayNameResId = R.string.module_fly_display_name
) {

    private var flySpeed by floatValue("Speed", 0.3f, 0.05f..1.0f)
    private var verticalSpeed by floatValue("Vertical Speed", 0.3f, 0.1f..1.0f)

    private var currentVelocity: Vector3f = Vector3f.ZERO
    private var isFlyingActive: Boolean = false 
    private val FRICTION_FACTOR = 0.98f 
    private val ACCELERATION_FACTOR = 0.1f 

    private val MAX_HORIZONTAL_SPEED: Float = 0.5f
    private val MAX_VERTICAL_SPEED: Float = 0.5f

    private val enableFlyAbilitiesPacket = UpdateAbilitiesPacket().apply {
        playerPermission = PlayerPermission.OPERATOR
        commandPermission = CommandPermission.OWNER
        abilityLayers.add(AbilityLayer().apply {
            layerType = AbilityLayer.Type.BASE
            abilitiesSet.addAll(Ability.entries.toTypedArray())
            abilityValues.addAll(
                listOf(
                    Ability.BUILD, Ability.MINE, Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS, Ability.ATTACK_PLAYERS, Ability.ATTACK_MOBS,
                    Ability.OPERATOR_COMMANDS, Ability.MAY_FLY,
                    Ability.FLY_SPEED, Ability.WALK_SPEED
                )
            )
            walkSpeed = 0.1f
            flySpeed = this@FlyElement.flySpeed
        })
    }

    private val disableFlyAbilitiesPacket = UpdateAbilitiesPacket().apply {
        playerPermission = PlayerPermission.OPERATOR
        commandPermission = CommandPermission.OWNER
        abilityLayers.add(AbilityLayer().apply {
            layerType = AbilityLayer.Type.BASE
            abilitiesSet.addAll(Ability.entries.toTypedArray())
            abilityValues.addAll(
                listOf(
                    Ability.BUILD, Ability.MINE, Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS, Ability.ATTACK_PLAYERS, Ability.ATTACK_MOBS,
                    Ability.OPERATOR_COMMANDS, Ability.WALK_SPEED
                )
            )
            walkSpeed = 0.1f
            flySpeed = 0.0f
        })
    }

    override fun onEnabled() {
        super.onEnabled()
        if (!isSessionCreated) { 
            return
        }
        session.localPlayer?.let { localPlayer -> 
            enableFlyAbilitiesPacket.uniqueEntityId = localPlayer.uniqueEntityId
            session.clientBound(enableFlyAbilitiesPacket) 
        }
        currentVelocity = Vector3f.ZERO
        isFlyingActive = false
    }

    override fun onDisabled() {
        super.onDisabled()
        if (!isSessionCreated) { 
            return
        }
        session.localPlayer?.let { localPlayer -> 
            disableFlyAbilitiesPacket.uniqueEntityId = localPlayer.uniqueEntityId
            session.clientBound(disableFlyAbilitiesPacket) 
        }
        landPlayer()
        currentVelocity = Vector3f.ZERO
        isFlyingActive = false
    }

    private fun landPlayer() {
        if (!isSessionCreated) { 
            return
        }
        session.localPlayer?.let { localPlayer ->
            val landingPosition: Vector3f = localPlayer.vec3Position
            val movePacket = MovePlayerPacket().apply {
                runtimeEntityId = localPlayer.uniqueEntityId
                position = landingPosition
                rotation = localPlayer.vec3Rotation
                mode = MovePlayerPacket.Mode.NORMAL
                isOnGround = true
                tick = localPlayer.tickExists
            }
            repeat(5) {
                session.serverBound(movePacket) 
            }
        }
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        val packet = interceptablePacket.packet

        if (packet is RequestAbilityPacket && packet.ability == Ability.FLYING) {
            interceptablePacket.intercept()
            return
        }
        if (packet is UpdateAbilitiesPacket) {
            interceptablePacket.intercept()
            return
        }

        if (packet is PlayerAuthInputPacket && isEnabled) {
            if (!isSessionCreated) { 
                return 
            }

            if (packet.inputData.contains(PlayerAuthInputData.START_FLYING)) {
                isFlyingActive = true
                interceptablePacket.intercept()
                return
            }
            if (packet.inputData.contains(PlayerAuthInputData.STOP_FLYING)) {
                isFlyingActive = false
                landPlayer()
                interceptablePacket.intercept()
                return
            }

            if (isFlyingActive) {
                interceptablePacket.intercept()

                var targetVerticalMotion = 0f
                if (packet.inputData.contains(PlayerAuthInputData.JUMPING)) {
                    targetVerticalMotion = verticalSpeed
                } else if (packet.inputData.contains(PlayerAuthInputData.SNEAKING)) {
                    targetVerticalMotion = -verticalSpeed
                }

                val inputMotionX: Float = packet.motion?.x ?: 0f
                val inputMotionZ: Float = packet.motion?.y ?: 0f

                val yaw: Double = packet.rotation?.y?.toDouble()?.let { it * (PI / 180.0) } ?: 0.0
                
                val targetHorizontalMotionX: Float = (-sin(yaw) * inputMotionZ.toDouble() + cos(yaw) * inputMotionX.toDouble()).toFloat() * flySpeed
                val targetHorizontalMotionZ: Float = (cos(yaw) * inputMotionZ.toDouble() + sin(yaw) * inputMotionX.toDouble()).toFloat() * flySpeed

                var newVx: Float = currentVelocity.x + (targetHorizontalMotionX - currentVelocity.x) * ACCELERATION_FACTOR
                var newVy: Float = currentVelocity.y + (targetVerticalMotion - currentVelocity.y) * ACCELERATION_FACTOR
                var newVz: Float = currentVelocity.z + (targetHorizontalMotionZ - currentVelocity.z) * ACCELERATION_FACTOR

                if (inputMotionX == 0f && inputMotionZ == 0f) {
                    newVx *= FRICTION_FACTOR
                    newVz *= FRICTION_FACTOR
                }
                if (!packet.inputData.contains(PlayerAuthInputData.JUMPING) && !packet.inputData.contains(PlayerAuthInputData.SNEAKING)) {
                    newVy *= FRICTION_FACTOR
                }

                newVx = newVx.coerceIn(-MAX_HORIZONTAL_SPEED, MAX_HORIZONTAL_SPEED)
                newVy = newVy.coerceIn(-MAX_VERTICAL_SPEED, MAX_VERTICAL_SPEED)
                newVz = newVz.coerceIn(-MAX_HORIZONTAL_SPEED, MAX_HORIZONTAL_SPEED)

                currentVelocity = Vector3f.from(newVx, newVy, newVz)

                session.localPlayer.move(session.localPlayer.vec3Position.add(currentVelocity))

                val newPlayerAuthInputPacket = PlayerAuthInputPacket().apply {
                    position = session.localPlayer.vec3Position
                    rotation = packet.rotation ?: Vector3f.ZERO 
                    motion = Vector2f.from(currentVelocity.x, currentVelocity.z) 
                    tick = packet.tick
                    inputData.addAll(packet.inputData)
                }
                session.serverBound(newPlayerAuthInputPacket)
            }
        }
    }
}
