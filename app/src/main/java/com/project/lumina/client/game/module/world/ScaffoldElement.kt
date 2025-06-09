package com.project.lumina.client.game.module.world

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.LocalPlayer
import com.project.lumina.client.game.inventory.PlayerInventory
import com.project.lumina.client.game.world.World
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.*
import org.cloudburstmc.protocol.bedrock.packet.*
import com.project.lumina.client.game.registry.* // Импорт расширений
import kotlin.math.floor

class ScaffoldElement(iconResId: Int = R.drawable.ic_cube_outline_black_24dp) : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId,
    displayNameResId = R.string.module_scaffold_display_name
) {

    private val placeDelay by intValue("Place Delay (ms)", 100, 50..500)
    private val blockSlot by intValue("Block Slot", 0, 0..8)
    private val debugMode by boolValue("Debug Mode", true)
    private var lastPlaceTime: Long = 0

    override fun onEnabled() {
        super.onEnabled()
        if (isSessionCreated) {
            session.displayClientMessage("Scaffold: Enabled")
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        if (isSessionCreated) {
            session.displayClientMessage("Scaffold: Disabled")
        }
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return // Проверка включения модуля

        val packet = interceptablePacket.packet
        val localPlayer = session.localPlayer as? LocalPlayer ?: run {
            if (debugMode) session.displayClientMessage("Scaffold: No local player!")
            return
        }
        val inventory = localPlayer.inventory as? PlayerInventory ?: run {
            if (debugMode) session.displayClientMessage("Scaffold: No player inventory!")
            return
        }
        val world = session.world as? World ?: run {
            if (debugMode) session.displayClientMessage("Scaffold: No world instance!")
            return
        }

        // Кэширование мира
        when (packet) {
            is LevelChunkPacket -> {
                if (debugMode) {
                    session.displayClientMessage("Scaffold: Chunk loaded at ${packet.chunkX}, ${packet.chunkZ}")
                }
            }
            is UpdateBlockPacket -> {
                world.setBlockIdAt(packet.blockPosition, packet.definition.runtimeId)
                if (debugMode) {
                    session.displayClientMessage("Scaffold: Block updated at ${packet.blockPosition} to ${packet.definition.runtimeId}")
                }
            }
        }

        // Инвентарь и установка блоков
        if (packet is PlayerAuthInputPacket) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastPlaceTime < placeDelay) return

            val posBelow = Vector3i.from(
                floor(packet.position.x).toInt(),
                floor(packet.position.y).toInt() - 1, // Проверяем блок под ногами
                floor(packet.position.z).toInt()
            )
            val blockBelowId = world.getBlockIdAt(posBelow)
            if (blockBelowId == 0) {
                if (debugMode) {
                    session.displayClientMessage("Scaffold: No block below at $posBelow")
                }
                return
            }

            // Проверка и переключение слота
            val currentSlot = localPlayer.inventory.heldItemSlot
            if (currentSlot != blockSlot) {
                if (debugMode) {
                    session.displayClientMessage("Scaffold: Switching from slot $currentSlot to slot $blockSlot (manual switch required)")
                }
                // Отключаем автоматическое переключение, если сервер не поддерживает
            } else if (debugMode) {
                session.displayClientMessage("Scaffold: Already on slot $blockSlot")
            }

            val itemInHand = inventory.content[blockSlot]
            if (itemInHand == null || itemInHand == ItemData.AIR) {
                if (debugMode) {
                    session.displayClientMessage("Scaffold: No item in slot $blockSlot!")
                }
                return
            }

            placeBlock(localPlayer, inventory, posBelow, itemInHand, packet, world)
            lastPlaceTime = currentTime
        }
    }

    private fun isBlockItem(item: ItemData): Boolean {
        return item.isBlock() // Используем расширение из registry
    }

    private fun placeBlock(
        localPlayer: LocalPlayer,
        inventory: PlayerInventory,
        blockPosition: Vector3i,
        itemInHand: ItemData,
        inputPacket: PlayerAuthInputPacket,
        world: World
    ) {
        val clickPosition = Vector3i.from(blockPosition.x, blockPosition.y + 1, blockPosition.z) // Позиция для размещения
        val blockIdAtClickPos = world.getBlockIdAt(Vector3i.from(blockPosition.x, blockPosition.y, blockPosition.z)) // Проверяем блок под ногами
        if (blockIdAtClickPos == 0) {
            if (debugMode) {
                session.displayClientMessage("Scaffold: No block to click on at $blockPosition")
            }
            return
        }

        if (!isBlockItem(itemInHand)) {
            if (debugMode) {
                session.displayClientMessage("Scaffold: Item ${itemInHand.itemDefinition.getRuntimeId()} (e.g., compass) is not a block!")
            }
            return
        }

        // Настройка ItemUseTransaction
        val itemUseTransaction = ItemUseTransaction()
        itemUseTransaction.actionType = 1 // PLACE_BLOCK (проверь перечисление)
        itemUseTransaction.blockPosition = blockPosition // Позиция блока под ногами
        itemUseTransaction.blockFace = 1 // UP
        itemUseTransaction.hotbarSlot = localPlayer.inventory.heldItemSlot // Используем текущий слот
        itemUseTransaction.itemInHand = itemInHand
        itemUseTransaction.playerPosition = inputPacket.position
        itemUseTransaction.clickPosition = Vector3f.from(0.5f, 0f, 0.5f) // Центр клика

        // Настройка InventoryActionData
        val source = InventorySource.fromContainerWindowId(0) // Горячая панель
        val action = InventoryActionData(
            source,
            localPlayer.inventory.heldItemSlot, // Используем текущий слот
            itemInHand, // fromItem
            itemInHand, // toItem (без изменения, пока сервер не подтвердит)
            0 // stackNetworkId
        )
        itemUseTransaction.actions.add(action)

        // Настройка InventoryTransactionPacket
        val transactionPacket = InventoryTransactionPacket()
        transactionPacket.transactionType = InventoryTransactionType.ITEM_USE
        transactionPacket.runtimeEntityId = localPlayer.runtimeEntityId
        transactionPacket.blockPosition = blockPosition
        transactionPacket.blockFace = 1 // UP
        transactionPacket.hotbarSlot = localPlayer.inventory.heldItemSlot // Используем текущий слот
        transactionPacket.itemInHand = itemInHand
        transactionPacket.playerPosition = inputPacket.position
        transactionPacket.clickPosition = Vector3f.from(0.5f, 0f, 0.5f) // Центр блока
        transactionPacket.actions.addAll(itemUseTransaction.actions)

        // Отправка пакета
        session.serverBound(transactionPacket)
        if (debugMode) {
            session.displayClientMessage("Scaffold: Sent InventoryTransactionPacket at $clickPosition with item ${itemInHand.itemDefinition.getRuntimeId()}, slot ${localPlayer.inventory.heldItemSlot}")
        }

        // Обновляем кэш инвентаря только после подтверждения (предполагаем)
        world.setBlockIdAt(clickPosition, itemInHand.itemDefinition.getRuntimeId())
    }

    // Обработка ответа сервера
    override fun afterPacketBound(packet: BedrockPacket) {
        if (debugMode && packet is InventoryTransactionPacket) {
            val currentSlot = session.localPlayer?.inventory?.heldItemSlot ?: -1
            session.displayClientMessage("Scaffold: Received response for InventoryTransactionPacket, slot $currentSlot, packet: ${packet.toString().take(50)}...")
        }
    }
}
