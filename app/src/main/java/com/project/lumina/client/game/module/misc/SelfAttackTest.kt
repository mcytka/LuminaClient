package com.project.lumina.client.game.module.misc

import com.project.lumina.client.R
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.game.InterceptablePacket
import org.cloudburstmc.protocol.bedrock.data.InteractAction
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket

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

        val packet = interceptablePacket.packet
        if (packet is TextPacket && packet.type == TextPacket.Type.CHAT) {
            val message = packet.message.trim()
            if (message.startsWith(".selfattacktest")) {
                interceptablePacket.intercept()
                val args = message.split(" ").drop(1)
                when {
                    args.contains("toggle") -> {
                        state = !state
                        val status = if (state) "enabled" else "disabled"
                        session.displayClientMessage("§l§b[SelfAttackTest] §r§aSelfAttackTest $status")
                        if (!state) {
                            lastActivationTime = 0
                        }
                    }
                    else -> {
                        session.displayClientMessage("§l§b[SelfAttackTest] §r§7Usage: .selfattacktest toggle")
                    }
                }
            }
        }

        // Автоматическая активация атаки на себя
        if (isEnabled && state) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastActivationTime >= activationInterval) {
                performSelfAttack()
                lastActivationTime = currentTime
            }
        }
    }

    private fun performSelfAttack() {
        // Отправка пакета атаки на себя
        val attackPacket = InteractPacket()
        val playerId = session.localPlayer.runtimeEntityId
        attackPacket.runtimeEntityId = playerId // Атакующий
        attackPacket.targetRuntimeEntityId = playerId // Цель (себя)
        attackPacket.action = InteractAction.ATTACK
        session.serverBound(attackPacket)

        session.displayClientMessage("§l§b[SelfAttackTest] §r§aSent self-attack packet at ${System.currentTimeMillis()}!")
    }

    override fun onDisabled() {
        super.onDisabled()
        lastActivationTime = 0
        session.displayClientMessage("§l§b[SelfAttackTest] §r§aSelfAttackTest disabled")
    }
}
