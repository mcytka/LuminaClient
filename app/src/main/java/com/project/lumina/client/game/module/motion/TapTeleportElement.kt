package com.project.lumina.client.game.module.motion

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket

class TapTeleportElement(iconResId: Int = R.drawable.ic_feather_black_24dp) : Element(
    name = "TapTeleport",
    category = CheatCategory.Motion,
    iconResId,
    displayNameResId = R.string.module_tapteleport_display_name
) {

    private val teleportOffset = floatValue("Offset", 0.0f, 0.0f..2.0f) // Отступ от целевой позиции, 0.0f по умолчанию

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return // Модуль должен быть включен для работы

        val packet = interceptablePacket.packet

        if (packet is PlayerActionPacket) {
            // Мы ищем действие, которое указывает на "тап" по блоку
            if (packet.action == PlayerActionType.CONTINUE_DESTROY_BLOCK || packet.action == PlayerActionType.START_BREAK) {
                val blockPosition = packet.blockPosition
                val face = packet.face

                // Получаем текущую позицию игрока для относительного сравнения
                val playerY = session.localPlayer.vec3Position.y
                val playerEyeHeight = session.localPlayer.eyeHeight

                // Базовые координаты телепортации - центр блока
                val targetX = blockPosition.x.toFloat() + 0.5f 
                val targetZ = blockPosition.z.toFloat() + 0.5f 

                var teleportToY: Float

                when (face) {
                    1 -> { // Тап по верхней грани (TOP_FACE)
                        // Телепортируемся на верхнюю поверхность блока
                        teleportToY = blockPosition.y.toFloat() + 1.0f 
                    }
                    0 -> { // Тап по нижней грани (BOTTOM_FACE)
                        // Телепортируемся под блок
                        teleportToY = blockPosition.y.toFloat() - playerEyeHeight - 0.1f 
                    }
                    else -> { // Тап по боковой грани (SIDE_FACES)
                        // Если блок значительно выше игрока (над головой)
                        if (blockPosition.y.toFloat() > playerY + playerEyeHeight) {
                            // Телепортируемся под блок
                            teleportToY = blockPosition.y.toFloat() - playerEyeHeight - 0.1f
                        } else {
                            // Иначе телепортируемся на тот же Y-уровень, что и основание блока
                            teleportToY = blockPosition.y.toFloat()
                        }
                    }
                }

                // Применяем отступ
                teleportToY += teleportOffset.value

                val newPosition = Vector3f.from(targetX, teleportToY, targetZ)

                // Создаем пакет для телепортации
                val movePlayerPacket = MovePlayerPacket().apply {
                    runtimeEntityId = session.localPlayer.uniqueEntityId
                    position = newPosition
                    rotation = session.localPlayer.vec3Rotation // Сохраняем текущее вращение
                    mode = MovePlayerPacket.Mode.TELEPORT // Используем режим телепортации
                    isOnGround = true // Имитируем приземление для обхода урона от падения
                    tick = session.localPlayer.tickExists
                }

                session.serverBound(movePlayerPacket) // Отправляем пакет на сервер

                interceptablePacket.intercept() // Перехватываем оригинальный пакет действия
            }
        }
    }
}
