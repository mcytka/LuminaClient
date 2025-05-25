package com.project.lumina.client.game.module.misc

import com.project.lumina.client.R
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.game.InterceptablePacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType

class ClickDebugger(iconResId: Int = R.drawable.ic_bug_black_24dp) : Element(
    name = "ClickDebugger",
    category = CheatCategory.Misc,
    iconResId,
    displayNameResId = R.string.module_selfattacktest_display_name
) {

    private val debugInterval by intValue("Debug Interval", 1000, 500..2000)
    private var lastDebugTime: Long = 0

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastDebugTime < debugInterval) return // Ограничение частоты логов

        val packet = interceptablePacket.packet
        val logMessage = StringBuilder("§l§b[ClickDebugger] §r§aPacket (client -> server) logged:\n")

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
                if (packet.transactionType == InventoryTransactionType.ITEM_USE_ON) {
                    logMessage.append("  Block Position: ${packet.blockPosition}\n")
                    packet.actions.forEach { action ->
                        logMessage.append("  Action: $action\n")
                        logMessage.append("    Block Interaction Type: ${action.blockInteractionType}\n")
                    }
                }
                logMessage.append("  Timestamp: $currentTime")
            }
        }

        if (logMessage.length > "§l§b[ClickDebugger] §r§aPacket (client -> server) logged:\n".length) {
            session.displayClientMessage(logMessage.toString())
            lastDebugTime = currentTime
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        lastDebugTime = 0
        session.displayClientMessage("§l§b[ClickDebugger] §r§aClickDebugger disabled")
    }
}
