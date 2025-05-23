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

    // Ссылка на текущую сессию LuminaRelay. Будет установлена при активации.
    private var session: LuminaRelaySession? = null

    // Состояние полета (управляется START_FLYING/STOP_FLYING)
    var isFlyingActive: Boolean = false
        private set

    // Параметры полета
    private var currentFlySpeed: Float = 0.0f
    private var currentVerticalSpeed: Float = 0.0f
    
    // Внутренняя скорость игрока для плавной симуляции
    private var currentVelocity: Vector3f = Vector3f.ZERO
    
    // Переменные для стелс-полета и обхода античитов
    private var tickCounter: Long = 0
    private const val GROUND_SPOOF_INTERVAL = 5 // Имитировать касание земли каждые 5 тиков (чуть реже)
    private const val VERTICAL_SPEED_TOLERANCE_FOR_GROUND = 0.02f // Макс. вертикальная скорость для имитации isOnGround
    private const val GROUND_SPOOF_Y_OFFSET = 0.0001f // Очень маленький Y-оффсет для имитации шага
    private const val ACCELERATION_FACTOR = 0.8f // Как быстро набирается скорость (0.0-1.0)
    private const val FRICTION_FACTOR = 0.9f // Как быстро теряется скорость при отсутствии ввода (0.0-1.0)

    // Максимальные скорости для обхода античитов (в блоках за тик)
    // Ванильная скорость креативного полета примерно 0.1-0.12 блоков/тик
    private const val MAX_HORIZONTAL_SPEED = 0.5f // Установим чуть выше ванили, но не слишком много
    private const val MAX_VERTICAL_SPEED = 0.5f   // Аналогично для вертикальной скорости

    // Метод для инициализации обработчика полета и передачи текущей сессии
    fun initialize(luminaRelaySession: LuminaRelaySession) {
        if (this.session == null || this.session != luminaRelaySession) {
            this.session?.listeners?.remove(this)
            this.session = luminaRelaySession
            luminaRelaySession.listeners.add(this) 
            currentVelocity = Vector3f.ZERO
            tickCounter = 0
        }
    }

    // Активирует функционал полета (вызывается при получении START_FLYING)
    fun startFlight() {
        isFlyingActive = true
    }

    // Деактивирует функционал полета (вызывается при получении STOP_FLYING)
    fun stopFlight() {
        isFlyingActive = false
        session?.localPlayer?.let { player -> 
            val landingPosition = Vector3f.from(player.vec3Position.x, player.vec3Position.y, player.vec3Position.z)
            val movePacket = MovePlayerPacket().apply {
                runtimeEntityId = player.uniqueEntityId
                position = landingPosition
                rotation = player.vec3Rotation
                mode = MovePlayerPacket.Mode.NORMAL
                isOnGround = true 
                tick = player.tickExists
            }
            repeat(5) { 
                session?.serverBound(movePacket)
            }
        }
        currentVelocity = Vector3f.ZERO 
    }

    // Обрабатывает входные данные игрока для движения
    fun handlePlayerInput(localPlayer: LocalPlayer, inputPacket: PlayerAuthInputPacket, flySpeed: Float, verticalSpeed: Float) {
        session ?: return 
        if (!isFlyingActive) return 

        this.currentFlySpeed = flySpeed
        this.currentVerticalSpeed = verticalSpeed

        tickCounter++

        calculateStealthyMotion(localPlayer, inputPacket) 

        val currentPosition = localPlayer.vec3Position

        val spoofedMovePacket = MovePlayerPacket().apply {
            runtimeEntityId = localPlayer.uniqueEntityId 
            position = currentPosition 
            rotation = inputPacket.rotation ?: Vector3f.ZERO 
            mode = MovePlayerPacket.Mode.NORMAL
            isOnGround = shouldSpoofOnGround(localPlayer) 
            tick = inputPacket.tick 
        }
        session?.serverBound(spoofedMovePacket) 
    }

    // Рассчитывает стелс-движение и обновляет localPlayer.vec3Position
    private fun calculateStealthyMotion(localPlayer: LocalPlayer, inputPacket: PlayerAuthInputPacket) {
        val inputMotionX = inputPacket.motion?.x ?: 0f
        val inputMotionZ = inputPacket.motion?.y ?: 0f 

        val yaw = inputPacket.rotation?.y?.toDouble()?.let { it * (PI / 180.0) } ?: 0.0
        val targetHorizontalMotionX = (-sin(yaw) * inputMotionZ.toDouble() + cos(yaw) * inputMotionX.toDouble()).toFloat() * currentFlySpeed
        val targetHorizontalMotionZ = (cos(yaw) * inputMotionZ.toDouble() + sin(yaw) * inputMotionX.toDouble()).toFloat() * currentFlySpeed

        var targetVerticalMotion = 0f
        if (inputPacket.inputData.contains(PlayerAuthInputData.JUMPING)) {
            targetVerticalMotion = currentVerticalSpeed
        } else if (inputPacket.inputData.contains(PlayerAuthInputData.SNEAKING)) {
            targetVerticalMotion = -currentVerticalSpeed
        } else {
            targetVerticalMotion = currentVelocity.y * FRICTION_FACTOR * 0.5f 
        }

        var newVx = currentVelocity.x + (targetHorizontalMotionX - currentVelocity.x) * ACCELERATION_FACTOR 
        var newVy = currentVelocity.y + (targetVerticalMotion - currentVelocity.y) * ACCELERATION_FACTOR 
        var newVz = currentVelocity.z + (targetHorizontalMotionZ - currentVelocity.z) * ACCELERATION_FACTOR 

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

        localPlayer.vec3Position = localPlayer.vec3Position.add(currentVelocity)
    }

    // Определяет, нужно ли имитировать состояние "на земле"
    private fun shouldSpoofOnGround(localPlayer: LocalPlayer): Boolean { 
        val isVerticallyStable = currentVelocity.y > -VERTICAL_SPEED_TOLERANCE_FOR_GROUND && currentVelocity.y < VERTICAL_SPEED_TOLERANCE_FOR_GROUND
        
        if (tickCounter % GROUND_SPOOF_INTERVAL == 0L || isVerticallyStable) {
            val yOffset = Random.nextDouble(-GROUND_SPOOF_Y_OFFSET.toDouble(), GROUND_SPOOF_Y_OFFSET.toDouble()).toFloat()
            localPlayer.vec3Position = Vector3f.from(localPlayer.vec3Position.x, localPlayer.vec3Position.y + yOffset, localPlayer.vec3Position.z) 
            return true
        }
        return false
    }

    override fun beforeServerBound(packet: BedrockPacket): Boolean {
        if (packet is SetEntityMotionPacket && packet.runtimeEntityId == session?.localPlayer?.runtimeEntityId && isFlyingActive) {
            return true 
        }
        return false 
    }

    override fun beforeClientBound(packet: BedrockPacket): Boolean {
        session?.localPlayer?.let { player ->
            if (packet is MovePlayerPacket && packet.runtimeEntityId == player.runtimeEntityId && isFlyingActive) {
                player.vec3Position = packet.position 
                return true 
            }
            if (packet is SetEntityMotionPacket && packet.runtimeEntityId == player.runtimeEntityId && isFlyingActive) {
                return true 
            }
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
