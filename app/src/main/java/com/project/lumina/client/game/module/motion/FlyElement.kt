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
    private var isFlyingActive: Boolean = false // New: controls when flight logic is active
    private val FRICTION_FACTOR = 0.98f // Closer to vanilla
    private val ACCELERATION_FACTOR = 0.1f // Slower acceleration for vanilla feel

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
                    Ability.OPERATOR_COMMANDS, Ability.MAY_FLY, // Key ability for flight
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
            // Only include abilities not related to flight
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
        session?.localPlayer?.let { localPlayer ->
            enableFlyAbilitiesPacket.uniqueEntityId = localPlayer.uniqueEntityId
            session?.clientBound(enableFlyAbilitiesPacket)
        }
        currentVelocity = Vector3f.ZERO // Reset velocity on module enable
        isFlyingActive = false // Start with flight logic inactive until START_FLYING
    }

    override fun onDisabled() {
        super.onDisabled()
        session?.localPlayer?.let { localPlayer ->
            disableFlyAbilitiesPacket.uniqueEntityId = localPlayer.uniqueEntityId
            session?.clientBound(disableFlyAbilitiesPacket)
        }
        // Ensure player "lands" when module is disabled
        landPlayer()
        currentVelocity = Vector3f.ZERO // Reset velocity on module disable
        isFlyingActive = false
    }

    private fun landPlayer() {
        session?.localPlayer?.let { localPlayer ->
            val landingPosition: Vector3f = localPlayer.vec3Position
            val movePacket = MovePlayerPacket().apply {
                runtimeEntityId = localPlayer.uniqueEntityId
                position = landingPosition
                rotation = localPlayer.vec3Rotation
                mode = MovePlayerPacket.Mode.NORMAL
                isOnGround = true // Crucial for landing
                tick = localPlayer.tickExists
            }
            repeat(5) { // Send multiple times for reliability
                session?.serverBound(movePacket)
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

        if (packet is PlayerAuthInputPacket && isEnabled) { // Only process if module is enabled
            // Handle START_FLYING / STOP_FLYING
            if (packet.inputData.contains(PlayerAuthInputData.START_FLYING)) {
                isFlyingActive = true
                interceptablePacket.intercept() // Intercept to prevent vanilla client from doing its own flight start
                return // Return early as we've handled the flight start
            }
            if (packet.inputData.contains(PlayerAuthInputData.STOP_FLYING)) {
                isFlyingActive = false
                landPlayer() // Immediately land the player
                interceptablePacket.intercept() // Intercept to prevent vanilla client from doing its own flight stop
                return // Return early as we've handled the flight stop
            }

            // Only process flight movement if isFlyingActive
            if (isFlyingActive) {
                interceptablePacket.intercept() // Intercept the original PlayerAuthInputPacket

                // Calculate vertical motion based on input
                var targetVerticalMotion = 0f
                if (packet.inputData.contains(PlayerAuthInputData.JUMPING)) {
                    targetVerticalMotion = verticalSpeed
                } else if (packet.inputData.contains(PlayerAuthInputData.SNEAKING)) {
                    targetVerticalMotion = -verticalSpeed
                }
                // No else branch for vertical friction here, as vanilla flight doesn't "decelerate" verticaly as much

                // Calculate horizontal motion
                val inputMotionX: Float = packet.motion?.x ?: 0f // Forward/Backward
                val inputMotionZ: Float = packet.motion?.y ?: 0f // Strafe (left/right) - assuming PlayerAuthInputPacket.motion is Vector2f (X, Z)

                val yaw: Double = packet.rotation?.y?.toDouble()?.let { it * (PI / 180.0) } ?: 0.0 // Player's current yaw
                
                // Calculate target horizontal motion based on input and yaw
                val targetHorizontalMotionX: Float = (-sin(yaw) * inputMotionZ.toDouble() + cos(yaw) * inputMotionX.toDouble()).toFloat() * flySpeed
                val targetHorizontalMotionZ: Float = (cos(yaw) * inputMotionZ.toDouble() + sin(yaw) * inputMotionX.toDouble()).toFloat() * flySpeed

                // Apply acceleration towards target velocity
                var newVx: Float = currentVelocity.x + (targetHorizontalMotionX - currentVelocity.x) * ACCELERATION_FACTOR
                var newVy: Float = currentVelocity.y + (targetVerticalMotion - currentVelocity.y) * ACCELERATION_FACTOR
                var newVz: Float = currentVelocity.z + (targetHorizontalMotionZ - currentVelocity.z) * ACCELERATION_FACTOR

                // Apply friction if no horizontal input
                if (inputMotionX == 0f && inputMotionZ == 0f) {
                    newVx *= FRICTION_FACTOR
                    newVz *= FRICTION_FACTOR
                }
                // Apply friction to vertical motion if no vertical input (this is more "vanilla" than setting to 0 directly)
                if (!packet.inputData.contains(PlayerAuthInputData.JUMPING) && !packet.inputData.contains(PlayerAuthInputData.SNEAKING)) {
                    newVy *= FRICTION_FACTOR
                }


                // Clamp speeds to reasonable max values (ensure we don't exceed what server expects)
                newVx = newVx.coerceIn(-0.5f, 0.5f) // MAX_HORIZONTAL_SPEED
                newVy = newVy.coerceIn(-0.5f, 0.5f) // MAX_VERTICAL_SPEED
                newVz = newVz.coerceIn(-0.5f, 0.5f) // MAX_HORIZONTAL_SPEED

                currentVelocity = Vector3f.from(newVx, newVy, newVz)

                // Update player's position based on new velocity
                session?.localPlayer?.move(session.localPlayer.vec3Position.add(currentVelocity))

                // Now, create and send the new PlayerAuthInputPacket with updated position and motion
                val newPlayerAuthInputPacket = PlayerAuthInputPacket().apply {
                    position = session.localPlayer.vec3Position // Use our updated position
                    rotation = packet.rotation ?: Vector3f.ZERO // Use the original rotation (Bedrock's rotation is Vector3f: pitch, yaw, headYaw)
                    motion = Vector2f.from(currentVelocity.x, currentVelocity.z) // Motion for PlayerAuthInputPacket is typically horizontal (Vector2f)
                    tick = packet.tick
                    inputData.addAll(packet.inputData) // Preserve original input data flags
                    inputData.add(PlayerAuthInputData.IS_FLYING) // Crucial for servers to recognize client-side flight.
                    
                    // Copy other relevant fields for completeness
                    headYaw = packet.headYaw ?: 0f
                    bodyYaw = packet.bodyYaw ?: 0f
                    delta = packet.delta ?: Vector3f.ZERO // Delta is for positional changes. motion is velocity.
                    inputMode = packet.inputMode
                    interactionModel = packet.interactionModel
                    playMode = packet.playMode
                    vrGazeDirection = packet.vrGazeDirection
                    currentTick = packet.currentTick
                    // We do NOT set isOnGround here. For client-side flight, you are generally NOT on ground.
                }
                session?.serverBound(newPlayerAuthInputPacket)
            }
        }
    }
}
