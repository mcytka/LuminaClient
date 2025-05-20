package com.project.lumina.client.game.module.misc

import com.project.lumina.client.R
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.game.InterceptablePacket

class SelfAttackTest(iconResId: Int = R.drawable.ic_sword_cross_black_24dp) : Element(
    name = "SelfAttackTest",
    category = CheatCategory.Misc,
    iconResId,
    displayNameResId = R.string.module_selfattacktest_display_name
) {

    private val activationInterval by intValue("Activation Interval", 1000, 500..2000)
    private var lastActivationTime: Long = 0

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        // Логируем пакеты, которые действительно отправляются на сервер
        val packet = interceptablePacket.packet
        val logMessage = buildString {
            append("§l§b[SelfAttackTest-Logger] §r§aOutgoing Packet logged:\n")
            append("  Packet Type: ${packet.javaClass.simpleName}\n")
            append("  Packet Details: $packet\n")
            append("  Timestamp: ${System.currentTimeMillis()}")
        }
        session.displayClientMessage(logMessage)
    }

    override fun onTick() {
        if (!isEnabled) return

        // Логируем изменения состояния игрока (например, позицию, здоровье, скорость)
        val player = session.localPlayer
        val logMessage = buildString {
            append("§l§b[SelfAttackTest-Logger] §r§aPlayer State logged:\n")
            append("  Position: ${player.position}\n")
            append("  Motion: ${player.motion}\n")
            append("  OnGround: ${player.isOnGround}\n")
            append("  Health: ${player.health}\n")
            append("  Timestamp: ${System.currentTimeMillis()}")
        }
        session.displayClientMessage(logMessage)
    }

    override fun onDisabled() {
        super.onDisabled()
        lastActivationTime = 0
        session.displayClientMessage("§l§b[SelfAttackTest] §r§aSelfAttackTest disabled")
    }
}
