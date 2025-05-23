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

    // Motion variables for smooth, vanilla-like movement
    private var currentVerticalMotion = 0f
    private var currentHorizontalMotion = Vector3f.ZERO
    private var targetVerticalMotion = 0f
    private var targetHorizontalMotion = Vector3f.ZERO

    private val GRAVITY = 0.04f // Minecraft creative mode gravity
    private val ACCELERATION_RATE = 0.15f // How quickly speed changes
    private val FRICTION_RATE = 0.91f // How quickly motion decays without input (closer to 1.0f means less decay)

    // Converts degrees to radians
    private fun toRadians(degrees: Double): Double = degrees * (PI / 180.0)

    // Helper function to create UpdateAbilitiesPacket with given fly speed
    private fun createUpdateAbilitiesPacket(flySpeedValue: Float): UpdateAbilitiesPacket {
        return UpdateAbilitiesPacket().apply {
            playerPermission = PlayerPermission.MEMBER // Default permission for creative mode flight
            commandPermission = CommandPermission.ANY // Default permission for creative mode flight
            abilityLayers.add(AbilityLayer().apply {
                layerType = AbilityLayer.Type.BASE
                // Only include necessary abilities for creative flight
                abilityValues.addAll(
                    listOf(
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

                // Calculate target vertical motion
                targetVerticalMotion = when {
                    packet.inputData.contains(PlayerAuthInputData.JUMPING) -> verticalSpeed * (0.9f + Random.nextFloat() * 0.2f) // 90% to 110% vertical speed
                    packet.inputData.contains(PlayerAuthInputData.SNEAKING) -> -verticalSpeed * (0.9f + Random.nextFloat() * 0.2f)
                    else -> 0f
                }

                // Smoothly update current vertical motion
                currentVerticalMotion += (targetVerticalMotion - currentVerticalMotion) * ACCELERATION_RATE

                // Apply gravity if not actively jumping up
                if (!packet.inputData.contains(PlayerAuthInputData.JUMPING)) {
                    currentVerticalMotion -= GRAVITY
                }

                // Calculate target horizontal motion
                val inputMotion = packet.motion?.let {
                    Vector3f.from(it.x, 0f, it.y) // Only consider horizontal components
                } ?: Vector3f.ZERO

                val yaw = packet.rotation?.y?.toDouble()?.let { toRadians(it) } ?: 0.0

                targetHorizontalMotion = if (inputMotion != Vector3f.ZERO) {
                    val speed = flySpeed * (0.9f + Random.nextFloat() * 0.2f) // 90% to 110% fly speed
                    Vector3f.from(
                        ((-sin(yaw) * inputMotion.z.toDouble() + cos(yaw) * inputMotion.x.toDouble()) * speed).toFloat(),
                        0f, // Keep y as 0 for horizontal motion
                        ((cos(yaw) * inputMotion.z.toDouble() + sin(yaw) * inputMotion.x.toDouble()) * speed).toFloat()
                    )
                } else {
                    Vector3f.ZERO
                }

                // Smoothly update current horizontal motion
                currentHorizontalMotion = Vector3f.from(
                    currentHorizontalMotion.x + (targetHorizontalMotion.x - currentHorizontalMotion.x) * ACCELERATION_RATE,
                    0f, // Keep y as 0 for horizontal motion
                    currentHorizontalMotion.z + (targetHorizontalMotion.z - currentHorizontalMotion.z) * ACCELERATION_RATE
                )

                // Apply friction only when there's no active horizontal input
                if (targetHorizontalMotion == Vector3f.ZERO) {
                    currentHorizontalMotion = currentHorizontalMotion.mul(FRICTION_RATE)
                }

                val combinedMotion = Vector3f.from(
                    currentHorizontalMotion.x,
                    currentVerticalMotion,
                    currentHorizontalMotion.z
                )

                // Send SetEntityMotionPacket if there is significant motion
                if (combinedMotion.lengthSquared() > 0.0001f) { // Check for non-zero motion
                    val motionPacket = SetEntityMotionPacket().apply {
                        runtimeEntityId = session.localPlayer.runtimeEntityId
                        motion = combinedMotion
                    }
                    session.clientBound(motionPacket)
                } else if (combinedMotion.lengthSquared() <= 0.0001f && lastPosition != null) { // Stop motion if negligible and player is in air
                    val stopMotionPacket = SetEntityMotionPacket().apply {
                        runtimeEntityId = session.localPlayer.runtimeEntityId
                        motion = Vector3f.ZERO
                    }
                    session.clientBound(stopMotionPacket)
                    // Reset lastPosition to avoid sending redundant MovePlayerPacket
                    lastPosition = null
                }

                // Update tick counter and send MovePlayerPacket if position has changed
                tickCounter++
                val playerPosition = packet.position?.let { Vector3f.from(it.x, it.y, it.z) } ?: Vector3f.ZERO

                // Always send MovePlayerPacket if the position changes significantly
                if (playerPosition != Vector3f.ZERO && (lastPosition == null || playerPosition.distance(lastPosition!!) > 0.001f)) {
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
