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
    private var canFly = false
    private var tickCounter = 0
    private var lastPosition: Vector3f? = null

    private fun toRadians(degrees: Double): Double = degrees * (PI / 180.0)

    private val enableFlyAbilitiesPacket = UpdateAbilitiesPacket().apply {
        playerPermission = PlayerPermission.OPERATOR
        commandPermission = CommandPermission.OWNER
        abilityLayers.add(AbilityLayer().apply {
            layerType = AbilityLayer.Type.BASE
            abilitiesSet.addAll(Ability.entries.toTypedArray())
            abilityValues.addAll(
                listOf(
                    Ability.BUILD,
                    Ability.MINE,
                    Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS,
                    Ability.ATTACK_PLAYERS,
                    Ability.ATTACK_MOBS,
                    Ability.OPERATOR_COMMANDS,
                    Ability.MAY_FLY,
                    Ability.FLY_SPEED,
                    Ability.WALK_SPEED
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
                    Ability.BUILD,
                    Ability.MINE,
                    Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS,
                    Ability.ATTACK_PLAYERS,
                    Ability.ATTACK_MOBS,
                    Ability.OPERATOR_COMMANDS,
                    Ability.WALK_SPEED
                )
            )
            walkSpeed = 0.1f
            flySpeed = 0.0f
        })
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

        if (packet is PlayerAuthInputPacket) {

            if (!canFly && isEnabled) {
                enableFlyAbilitiesPacket.uniqueEntityId = session.localPlayer.uniqueEntityId
                session.clientBound(enableFlyAbilitiesPacket)
                canFly = true
            } else if (canFly && !isEnabled) {
                disableFlyAbilitiesPacket.uniqueEntityId = session.localPlayer.uniqueEntityId
                session.clientBound(disableFlyAbilitiesPacket)
                canFly = false
            }

            if (isEnabled) {
                if (packet.inputData.contains(PlayerAuthInputData.START_FLYING) ||
                    packet.inputData.contains(PlayerAuthInputData.STOP_FLYING)) {
                    interceptablePacket.intercept()
                }

                val isFlying = packet.inputData.contains(PlayerAuthInputData.JUMPING) ||
                        packet.inputData.contains(PlayerAuthInputData.SNEAKING)

                var verticalMotion = 0f
                if (isFlying) {
                    if (packet.inputData.contains(PlayerAuthInputData.JUMPING)) {
                        verticalMotion = verticalSpeed
                    } else if (packet.inputData.contains(PlayerAuthInputData.SNEAKING)) {
                        verticalMotion = -verticalSpeed
                    }
                }

                val inputMotion = packet.motion?.let {
                    Vector3f.from(it.x, 0f, it.y)
                } ?: Vector3f.ZERO

                val yaw = packet.rotation?.y?.toDouble()?.let { toRadians(it) } ?: 0.0
                val horizontalMotion = if (inputMotion != Vector3f.ZERO) {
                    val speed = flySpeed.toDouble()
                    Vector3f.from(
                        ((-sin(yaw) * inputMotion.z.toDouble() + cos(yaw) * inputMotion.x.toDouble()) * speed).toFloat(),
                        0f,
                        ((cos(yaw) * inputMotion.z.toDouble() + sin(yaw) * inputMotion.x.toDouble()) * speed).toFloat()
                    )
                } else {
                    Vector3f.ZERO
                }

                val combinedMotion = Vector3f.from(
                    horizontalMotion.x,
                    verticalMotion,
                    horizontalMotion.z
                )

                if ((isFlying || verticalMotion != 0f) && combinedMotion != Vector3f.ZERO) {
                    val motionPacket = SetEntityMotionPacket().apply {
                        runtimeEntityId = session.localPlayer.runtimeEntityId
                        motion = combinedMotion
                    }
                    session.clientBound(motionPacket)
                } else if (isFlying || verticalMotion != 0f) {
                    val stopMotionPacket = SetEntityMotionPacket().apply {
                        runtimeEntityId = session.localPlayer.runtimeEntityId
                        motion = Vector3f.ZERO
                    }
                    session.clientBound(stopMotionPacket)
                }

                tickCounter++
                val playerPosition = packet.position?.let { Vector3f.from(it.x, it.y, it.z) } ?: Vector3f.ZERO
                if (playerPosition != Vector3f.ZERO && tickCounter % 2 == 0) {
                    if (lastPosition == null || playerPosition != lastPosition) {
                        val movePacket = MovePlayerPacket().apply {
                            runtimeEntityId = session.localPlayer.uniqueEntityId
                            position = playerPosition
                            rotation = packet.rotation?.let { Vector3f.from(it.x, it.y, it.z) } ?: Vector3f.ZERO
                            mode = MovePlayerPacket.Mode.NORMAL
                            tick = packet.tick
                        }
                        session.serverBound(movePacket)
                        lastPosition = playerPosition
                    }
                }
            }
        }
    }
}
