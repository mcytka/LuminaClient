package com.project.lumina.client.game.module.motion

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.LocalPlayer
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
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
    private var isFlyingActive = false

    private fun toRadians(degrees: Double): Double = degrees * (PI / 180.0)

    override fun onEnable() {
        super.onEnable()
        isFlyingActive = false
    }

    override fun onDisable() {
        super.onDisable()
        isFlyingActive = false
        // При отключении модуля, можно сбросить скорость игрока до нуля, чтобы он начал падать.
        // Это сделает выход из режима полета более "ванильным".
        session.localPlayer.motion = Vector3f.ZERO
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        val packet = interceptablePacket.packet

        // Перехватываем RequestAbilityPacket, если клиент пытается запросить FLYING способность.
        // Это предотвратит отправку запроса на сервер, тем самым скрывая наш полет.
        if (packet is RequestAbilityPacket && packet.ability == Ability.FLYING) {
            interceptablePacket.intercept()
            return
        }

        // Мы больше не отправляем и не перехватываем UpdateAbilitiesPacket для установки способностей полета.
        // Вместо этого, мы имитируем полет, управляя напрямую позицией игрока.
        // if (packet is UpdateAbilitiesPacket) {
        //     interceptablePacket.intercept()
        //     return
        // }

        if (packet is PlayerAuthInputPacket) {
            if (isEnabled) {
                // Перехватываем START_FLYING/STOP_FLYING, если они все еще приходят от клиента
                // Это предотвращает отправку нежелательных флагов на сервер.
                if (packet.inputData.contains(PlayerAuthInputData.START_FLYING) ||
                    packet.inputData.contains(PlayerAuthInputData.STOP_FLYING)) {
                    interceptablePacket.intercept()
                }

                // Определяем, пытается ли игрок двигаться (вверх, вниз или по горизонтали)
                val tryingToFlyUp = packet.inputData.contains(PlayerAuthInputData.JUMPING)
                val tryingToFlyDown = packet.inputData.contains(PlayerAuthInputData.SNEAKING)
                val tryingToMoveHorizontal = packet.inputData.contains(PlayerAuthInputData.FORWARD) ||
                                              packet.inputData.contains(PlayerAuthInputData.BACK) ||
                                              packet.inputData.contains(PlayerAuthInputData.LEFT) ||
                                              packet.inputData.contains(PlayerAuthInputData.RIGHT)

                // Активируем полет, если есть какое-либо движение или если игрок уже в воздухе (чтобы не упасть сразу после активации)
                isFlyingActive = tryingToFlyUp || tryingToFlyDown || tryingToMoveHorizontal || !session.localPlayer.onGround

                var motionX = 0.0f
                var motionY = 0.0f
                var motionZ = 0.0f

                val currentYaw = packet.rotation?.y?.toDouble() ?: 0.0
                val yawRadians = toRadians(currentYaw)

                // Расчет горизонтального движения
                val forward = packet.inputData.contains(PlayerAuthInputData.FORWARD)
                val backward = packet.inputData.contains(PlayerAuthInputData.BACK)
                val left = packet.inputData.contains(PlayerAuthInputData.LEFT)
                val right = packet.inputData.contains(PlayerAuthInputData.RIGHT)

                if (forward || backward || left || right) {
                    var moveStrafe = 0.0f
                    var moveForward = 0.0f

                    if (forward) moveForward++
                    if (backward) moveForward--
                    if (left) moveStrafe++
                    if (right) moveStrafe--

                    val hypotenuse = StrictMath.sqrt((moveStrafe * moveStrafe + moveForward * moveForward).toDouble())
                    var multiplier = flySpeed.toDouble()
                    if (hypotenuse >= 0.01) {
                        multiplier /= hypotenuse
                    }

                    // Применяем горизонтальное движение
                    motionX = (-(moveStrafe * sin(yawRadians) - moveForward * cos(yawRadians)) * multiplier).toFloat()
                    motionZ = (-(moveStrafe * cos(yawRadians) + moveForward * sin(yawRadians)) * multiplier).toFloat()
                }

                // Расчет вертикального движения
                if (tryingToFlyUp) {
                    motionY = verticalSpeed
                } else if (tryingToFlyDown) {
                    motionY = -verticalSpeed
                } else {
                    // Имитация ванильной гравитации, если игрок не двигается вертикально
                    // Это делает полет более похожим на ванильный креатив-режим, где игрок медленно опускается.
                    if (isFlyingActive) {
                        motionY = -0.098f // Примерное значение ванильной гравитации
                    }
                }

                // Добавление небольшой случайности для обхода античита и имитации "человеческого" движения
                motionX += Random.nextDouble(-0.0001, 0.0001).toFloat()
                motionY += Random.nextDouble(-0.0001, 0.0001).toFloat()
                motionZ += Random.nextDouble(-0.0001, 0.0001).toFloat()

                // Вычисляем новую позицию на основе текущей и рассчитанного движения
                val currentPosition = session.localPlayer.position
                val newPosition = Vector3f.from(
                    currentPosition.x + motionX,
                    currentPosition.y + motionY,
                    currentPosition.z + motionZ
                )

                // Отправляем MovePlayerPacket для обновления позиции игрока
                val movePacket = MovePlayerPacket().apply {
                    runtimeEntityId = session.localPlayer.uniqueEntityId
                    position = newPosition
                    rotation = packet.rotation // Используем оригинальное вращение из входного пакета
                    mode = MovePlayerPacket.Mode.NORMAL
                    tick = packet.tick
                    isOnGround = false // Всегда false при полете для скрытности
                }
                session.serverBound(movePacket)

                // Обновляем motion локального игрока для внутренней согласованности
                // (это не отправляется напрямую на сервер для режима полета)
                session.localPlayer.motion = Vector3f.from(motionX, motionY, motionZ)

                // Перехватываем оригинальный PlayerAuthInputPacket, поскольку мы сами обрабатываем движение
                interceptablePacket.intercept()
            } else {
                // Если модуль выключен, убедимся, что isFlyingActive тоже false
                isFlyingActive = false
            }
        }
    }
}
