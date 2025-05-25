package com.project.lumina.client.game.module.misc

import com.project.lumina.client.R
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.game.InterceptablePacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket

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

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActivationTime < activationInterval) return

        val packet = interceptablePacket.packet
        val logMessage = StringBuilder("§l§b[SelfAttackTest-Logger] §r§aPacket (client -> server) logged:\n")

        when (packet) {
            is PlayerAuthInputPacket -> {
                logMessage.append("  Packet Type: PlayerAuthInputPacket\n")
                logMessage.append("  Input Data: ${packet.inputData}\n")
                logMessage.append("  Position: ${packet.position}\n")
                logMessage.append("  Rotation: ${packet.rotation}\n")
                logMessage.append("  Timestamp: $currentTime")
            }
            is PlayerActionPacket -> {
                logMessage.append("  Packet Type: PlayerActionPacket\n")
                logMessage.append("  Action Type: ${packet.action}\n")
                logMessage.append("  Block Position: ${packet.blockPosition}\n")
                logMessage.append("  Face: ${packet.face}\n")
                logMessage.append("  Timestamp: $currentTime")
            }
            is InventoryTransactionPacket -> {
                logMessage.append("  Packet Type: InventoryTransactionPacket\n")
                logMessage.append("  Transaction Type: ${packet.transactionType}\n")
                logMessage.append("  Block Position: ${packet.blockPosition}\n")
                packet.actions.forEach { action ->
                    logMessage.append("  Action: $action\n")
                }
                logMessage.append("  Timestamp: $currentTime")
            }
            else -> {
                logMessage.append("  Packet Type: ${packet.javaClass.simpleName}\n")
                logMessage.append("  Packet Details: $packet\n")
                logMessage.append("  Timestamp: $currentTime")
            }
        }

        session.displayClientMessage(logMessage.toString())
        lastActivationTime = currentTime
    }

    override fun onDisabled() {
        super.onDisabled()
        lastActivationTime = 0
        session.displayClientMessage("§l§b[SelfAttackTest] §r§aSelfAttackTest disabled")
    }
}
