package com.project.lumina.client.game.module.misc

import com.project.lumina.client.R
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.game.InterceptablePacket
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket

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

        val packet = interceptablePacket.packet

        // Логируем только исходящие пакеты, связанные с атакой или действиями
        when (packet) {
            is InteractPacket -> {
                val logMessage = buildString {
                    append("§l§b[SelfAttackTest-Logger] §r§aInteractPacket (client -> server) logged:\n")
                    append("  runtimeEntityId: ${packet.runtimeEntityId}\n")
                    if (InteractPacket::class.java.declaredFields.any { it.name == "targetEntityId" }) {
                        val targetField = InteractPacket::class.java.getDeclaredField("targetEntityId")
                        targetField.isAccessible = true
                        append("  targetEntityId: ${targetField.get(packet)}\n")
                    }
                    if (InteractPacket::class.java.declaredFields.any { it.name == "action" }) {
                        val actionField = InteractPacket::class.java.getDeclaredField("action")
                        actionField.isAccessible = true
                        append("  action: ${actionField.get(packet)}\n")
                    }
                    append("  Timestamp: ${System.currentTimeMillis()}")
                }
                session.displayClientMessage(logMessage)
            }
            is PlayerActionPacket -> {
                val logMessage = buildString {
                    append("§l§b[SelfAttackTest-Logger] §r§aPlayerActionPacket (client -> server) logged:\n")
                    append("  runtimeEntityId: ${packet.runtimeEntityId}\n")
                    append("  action: ${packet.action}\n")
                    append("  Timestamp: ${System.currentTimeMillis()}")
                }
                session.displayClientMessage(logMessage)
            }
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        lastActivationTime = 0
        session.displayClientMessage("§l§b[SelfAttackTest] §r§aSelfAttackTest disabled")
    }
}
