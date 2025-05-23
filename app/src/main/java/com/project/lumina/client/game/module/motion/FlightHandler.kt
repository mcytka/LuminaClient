package com.project.lumina.client.game.module.motion

import com.project.lumina.relay.LuminaRelaySession
import com.project.lumina.relay.listener.LuminaRelayPacketListener
import com.project.lumina.client.game.entity.LocalPlayer
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object FlightHandler : LuminaRelayPacketListener {

    private var currentSession: LuminaRelaySession? = null
    private lateinit var player: LocalPlayer // <-- Добавлено: для прямого доступа к игроку

    var isFlyingActive: Boolean = false
        private set

    private var currentFlySpeed: Float = 0.0f
    private var currentVerticalSpeed: Float = 0.0f

    private var currentVelocity: Vector3f = Vector3f.ZERO

    private var tickCounter: Long = 0
    private const val GROUND_SPOOF_INTERVAL: Long = 5
    private const val VERTICAL_SPEED_TOLERANCE_FOR_GROUND: Float = 0.02f
    private const val GROUND_SPOOF_Y_OFFSET: Float = 0.0001f
    private const val ACCELERATION_FACTOR: Float = 0.8f
    private const val FRICTION_FACTOR: Float = 0.9f

    private const val MAX_HORIZONTAL_SPEED: Float = 0.5f
    private const val MAX_VERTICAL_SPEED: Float = 0.5f

    // Изменена сигнатура initialize: теперь принимает LocalPlayer
    fun initialize(luminaRelaySession: LuminaRelaySession, localPlayer: LocalPlayer) {
        if (this.currentSession == null || this.currentSession != luminaRelaySession) {
            this.currentSession?.listeners?.remove(this)
            this.currentSession = luminaRelaySession
            this.player = localPlayer // <-- Сохраняем ссылку на игрока
            luminaRelaySession.listeners.add(this)
            currentVelocity = Vector3f.ZERO
            tickCounter = 0
        }
    }

    fun startFlight() {
        isFlyingActive = true
    }

    fun stopFlight() {
        isFlyingActive = false
        // Используем сохраненную ссылку на игрока
        val landingPosition: Vector3f = Vector3f.from(player.vec3Position.x, player.vec3Position.y, player.vec3Position.z)
        val movePacket = MovePlayerPacket().apply {
            runtimeEntityId = player.uniqueEntityId
            position = landingPosition
            rotation = player.vec3Rotation
            mode = MovePlayerPacket.Mode.NORMAL
            isOnGround = true
            tick = player.tickExists
        }
        repeat(5) {
            currentSession?.serverBound(movePacket)
        }
        currentVelocity = Vector3f.ZERO
    }

    // handlePlayerInput теперь не принимает localPlayer, а использует сохраненный player
    fun handlePlayerInput(inputPacket: PlayerAuthInputPacket, flySpeed: Float, verticalSpeed: Float) {
        currentSession ?: return
        if (!isFlyingActive) return

        this.currentFlySpeed = flySpeed
        this.currentVerticalSpeed = verticalSpeed

        tickCounter++

        // This calculates newVx, newVy, newVz and updates player.vec3Position
        calculateStealthyMotion(inputPacket)

        // Create a NEW PlayerAuthInputPacket to send to the server
        val modifiedInputPacket = PlayerAuthInputPacket().apply {
            // Copy essential data from the original input packet
            position = player.vec3Position // Use our updated position
            rotation = inputPacket.rotation
            motion = currentVelocity // IMPORTANT: Use our calculated velocity here!
            tick = inputPacket.tick
            inputData.addAll(inputPacket.inputData) // Preserve original input data flags (JUMPING, SNEAKING, etc.)
            headYaw = inputPacket.headYaw
            bodyYaw = inputPacket.bodyYaw
            delta = inputPacket.delta // Preserve original delta motion if any, although our motion overrides it conceptually
            // Also copy other fields from the original inputPacket if they are relevant
            // to the server's state management, such as inputMode, interactionModel, etc.
            // For now, let's assume position, rotation, motion, tick, inputData, headYaw, bodyYaw, delta are sufficient.
            inputMode = inputPacket.inputMode
            interactionModel = inputPacket.interactionModel
            playMode = inputPacket.playMode
            vrGazeDirection = inputPacket.vrGazeDirection
            currentTick = inputPacket.currentTick
            // Add other potentially important fields if the server depends on them
            // like positionDelta, etc.
        }

        // Send this MODIFIED PlayerAuthInputPacket to the server
        currentSession?.serverBound(modifiedInputPacket)

        // Removed the MovePlayerPacket sending logic as PlayerAuthInputPacket should handle movement.
    }

    // calculateStealthyMotion теперь не принимает localPlayer, а использует сохраненный player
    private fun calculateStealthyMotion(inputPacket: PlayerAuthInputPacket) {
        val inputMotionX: Float = inputPacket.motion?.x ?: 0f
        val inputMotionZ: Float = inputPacket.motion?.y ?: 0f // Bedrock's PlayerAuthInputPacket motion.y is often Z-axis

        val yaw: Double = inputPacket.rotation?.y?.toDouble()?.let { it * (PI / 180.0) } ?: 0.0
        val targetHorizontalMotionX: Float = (-sin(yaw) * inputMotionZ.toDouble() + cos(yaw) * inputMotionX.toDouble()).toFloat() * currentFlySpeed
        val targetHorizontalMotionZ: Float = (cos(yaw) * inputMotionZ.toDouble() + sin(yaw) * inputMotionX.toDouble()).toFloat() * currentFlySpeed

        var targetVerticalMotion: Float = 0f
        if (inputPacket.inputData.contains(PlayerAuthInputData.JUMPING)) {
            targetVerticalMotion = currentVerticalSpeed
        } else if (inputPacket.inputData.contains(PlayerAuthInputData.SNEAKING)) {
            targetVerticalMotion = -currentVerticalSpeed
        } else {
            targetVerticalMotion = currentVelocity.y * FRICTION_FACTOR * 0.5f // Apply friction to vertical motion if no input
        }

        var newVx: Float = currentVelocity.x + (targetHorizontalMotionX - currentVelocity.x) * ACCELERATION_FACTOR
        var newVy: Float = currentVelocity.y + (targetVerticalMotion - currentVelocity.y) * ACCELERATION_FACTOR
        var newVz: Float = currentVelocity.z + (targetHorizontalMotionZ - currentVelocity.z) * ACCELERATION_FACTOR

        if (inputMotionX == 0f && inputMotionZ == 0f) {
            newVx *= FRICTION_FACTOR
            newVz *= FRICTION_FACTOR
        }
        if (!inputPacket.inputData.contains(PlayerAuthInputData.JUMPING) && !inputPacket.inputData.contains(PlayerAuthInputData.SNEAKING)) {
             newVy *= FRICTION_FACTOR
        }

        newVx = newVx.coerceIn(-MAX_HORIZONTAL_SPEED, MAX_HORIZONTAL_SPEED)
        newVy = newVy.coerceIn(-MAX_VERTICAL_SPEED, MAX_VERTICAL_SPEED)
        newVz = newVz.coerceIn(-MAX_HORIZONTAL_SPEED, MAX_HORIZONTAL_SPEED)

        currentVelocity = Vector3f.from(newVx, newVy, newVz)

        player.move(player.vec3Position.add(currentVelocity))
    }

    // shouldSpoofOnGround is no longer modifying player position.
    private fun shouldSpoofOnGround(): Boolean {
        val isVerticallyStable: Boolean = currentVelocity.y > -VERTICAL_SPEED_TOLERANCE_FOR_GROUND && currentVelocity.y < VERTICAL_SPEED_TOLERANCE_FOR_GROUND
        
        if (tickCounter % GROUND_SPOOF_INTERVAL == 0L || isVerticallyStable) {
            // val yOffset: Float = Random.nextDouble(-GROUND_SPOOF_Y_OFFSET.toDouble(), GROUND_SPOOF_Y_OFFSET.toDouble()).toFloat()
            // Removed player.move(Vector3f.from(player.vec3Position.x, player.vec3Position.y + yOffset, player.vec3Position.z))
            return true
        }
        return false
    }

    override fun beforeServerBound(packet: BedrockPacket): Boolean {
        if (packet is SetEntityMotionPacket && packet.runtimeEntityId == player.uniqueEntityId && isFlyingActive) {
            return true
        }
        return false
    }

    override fun beforeClientBound(packet: BedrockPacket): Boolean {
        if (packet is MovePlayerPacket && packet.runtimeEntityId == player.runtimeEntityId && isFlyingActive) {
            player.move(packet.position)
            return true
        }
        if (packet is SetEntityMotionPacket && packet.runtimeEntityId == player.runtimeEntityId && isFlyingActive) {
            return true
        }
        return false
    }

    override fun afterServerBound(packet: BedrockPacket) {
    }

    override fun afterClientBound(packet: BedrockPacket) {
    }

    override fun onDisconnect(reason: String) {
        isFlyingActive = false
        currentVelocity = Vector3f.ZERO
        tickCounter = 0
    }
}
