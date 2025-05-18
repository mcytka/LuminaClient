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
import kotlin.random.Random

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

    private val TICK_UPDATE_INTERVAL = 2

    // Timer to track airborne time
    private var airborneTicks = 0
    private val MAX_AIRBORNE_TICKS = 20 * 5 // 5 seconds at 20 ticks per second

    // Helper function to create UpdateAbilitiesPacket with given fly speed
    private fun createUpdateAbilitiesPacket(flySpeedValue: Float): UpdateAbilitiesPacket {
        return UpdateAbilitiesPacket().apply {
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
                flySpeed = flySpeedValue
            })
        }
    }

    private val enableFlyAbilitiesPacket = createUpdateAbilitiesPacket(flySpeed)
    private val disableFlyAbilitiesPacket = createUpdateAbilitiesPacket(0.0f).apply {
        abilityLayers[0].abilityValues.remove(Ability.MAY_FLY) // Remove fly ability when disabling
    }

    // Converts degrees to radians
    private fun toRadians(degrees: Double): Double = degrees * (PI / 180.0)

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        val packet = interceptablePacket.packet

        // Intercept RequestAbilityPacket for flying to prevent default handling
        if (packet is RequestAbilityPacket && packet.ability == Ability.FLYING) {
            interceptablePacket.intercept()
            return
        }

        // Intercept UpdateAbilitiesPacket to control flying abilities
        if (packet is UpdateAbilitiesPacket) {
            interceptablePacket.intercept()
            return
        }

        if (packet is PlayerAuthInputPacket) {
            // Enable or disable flying abilities based on module state
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
                // Intercept start/stop flying input packets to prevent default behavior
                if (packet.inputData.contains(PlayerAuthInputData.START_FLYING) ||
                    packet.inputData.contains(PlayerAuthInputData.STOP_FLYING)) {
                    interceptablePacket.intercept()
                }

                val isFlying = packet.inputData.contains(PlayerAuthInputData.JUMPING) ||
                        packet.inputData.contains(PlayerAuthInputData.SNEAKING)

                // Calculate vertical motion based on jump/sneak input with subtle randomization
                var verticalMotion = 0f
                if (isFlying) {
                    if (packet.inputData.contains(PlayerAuthInputData.JUMPING)) {
                        verticalMotion = verticalSpeed * (0.85f + Random.nextFloat() * 0.3f) // 85% to 115% vertical speed
                    } else if (packet.inputData.contains(PlayerAuthInputData.SNEAKING)) {
                        verticalMotion = -verticalSpeed * (0.85f + Random.nextFloat() * 0.3f)
                    }
                }

                // Calculate horizontal input motion ignoring vertical component with slight speed variation
                val inputMotion = packet.motion?.let {
                    Vector3f.from(it.x, 0f, it.y)
                } ?: Vector3f.ZERO

                val yaw = packet.rotation?.y?.toDouble()?.let { toRadians(it) } ?: 0.0
                val horizontalMotion = if (inputMotion != Vector3f.ZERO && isFlying) {
                    val speed = flySpeed * (0.9f + Random.nextFloat() * 0.2f) // 90% to 110% fly speed
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

                // Manage airborne time to avoid long continuous air time
                if (isFlying && combinedMotion != Vector3f.ZERO) {
                    airborneTicks++
                    if (airborneTicks > MAX_AIRBORNE_TICKS) {
                        // Simulate brief ground contact by sending zero vertical motion for a tick
                        val stopMotionPacket = SetEntityMotionPacket().apply {
                            runtimeEntityId = session.localPlayer.runtimeEntityId
                            motion = Vector3f.from(horizontalMotion.x, 0f, horizontalMotion.z)
                        }
                        session.clientBound(stopMotionPacket)
                        airborneTicks = 0
                    } else {
                        val motionPacket = SetEntityMotionPacket().apply {
                            runtimeEntityId = session.localPlayer.runtimeEntityId
                            motion = combinedMotion
                        }
                        session.clientBound(motionPacket)
                    }
                } else if (isFlying) {
                    airborneTicks = 0
                    val stopMotionPacket = SetEntityMotionPacket().apply {
                        runtimeEntityId = session.localPlayer.runtimeEntityId
                        motion = Vector3f.ZERO
                    }
                    session.clientBound(stopMotionPacket)
                } else {
                    airborneTicks = 0
                }

                tickCounter++
                val playerPosition = packet.position?.let { Vector3f.from(it.x, it.y, it.z) } ?: Vector3f.ZERO
                if (playerPosition != Vector3f.ZERO && tickCounter % TICK_UPDATE_INTERVAL == 0) {
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
