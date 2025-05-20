package com.project.lumina.client.game.module.misc

import com.project.lumina.client.R
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.game.InterceptablePacket
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket

class SelfAttackTest(iconResId: Int = R.drawable.ic_sword_cross_black_24dp) : Element(
    name = "SelfAttackTest",
    category = CheatCategory.Misc,
    iconResId,
    displayNameResId = R.string.module_selfattacktest_display_name
) {

    // Настройки
    private val activationInterval by intValue("Activation Interval", 1000, 500..2000) // Интервал активации (в мс)

    // Переменные состояния
    private var lastActivationTime: Long = 0

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        // Автоматическая активация атаки на себя
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActivationTime >= activationInterval) {
            performSelfAttack()
            lastActivationTime = currentTime
        }
    }

    private fun performSelfAttack() {
        // Отправка пакета атаки на себя
        val attackPacket = InteractPacket()
        val playerId = session.localPlayer.runtimeEntityId
        attackPacket.runtimeEntityId = playerId // Атакующий

        // Проверяем доступное поле для цели
        if (InteractPacket::class.java.declaredFields.any { it.name == "targetEntityId" }) {
            val targetField = InteractPacket::class.java.getDeclaredField("targetEntityId")
            targetField.isAccessible = true
            targetField.set(attackPacket, playerId) // Устанавливаем цель (себя)
        } else if (InteractPacket::class.java.declaredFields.any { it.name == "targetId" }) {
            val targetField = InteractPacket::class.java.getDeclaredField("targetId")
            targetField.isAccessible = true
            targetField.set(attackPacket, playerId) // Устанавливаем цель (себя)
        }

        // Проверяем доступное поле для action и устанавливаем значение через рефлексию
        if (InteractPacket::class.java.declaredFields.any { it.name == "action" }) {
            val actionField = InteractPacket::class.java.getDeclaredField("action")
            actionField.isAccessible = true
            // Устанавливаем значение 1 (соответствует ATTACK)
            actionField.set(attackPacket, 1) // Используем числовое значение, так как перечисление недоступно
        }

        session.serverBound(attackPacket)

        session.displayClientMessage("§l§b[SelfAttackTest] §r§aSent self-attack packet at ${System.currentTimeMillis()}!")
    }

    override fun onDisabled() {
        super.onDisabled()
        lastActivationTime = 0
        session.displayClientMessage("§l§b[SelfAttackTest] §r§aSelfAttackTest disabled")
    }
}
