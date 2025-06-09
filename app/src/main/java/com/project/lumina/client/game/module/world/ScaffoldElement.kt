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
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.*
import org.cloudburstmc.protocol.bedrock.packet.*
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import kotlin.math.floor

class ScaffoldElement(iconResId: Int = R.drawable.ic_block_black_24dp) : Element(
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
        if (!isSessionCreated) return

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

        // 3. Кэширование мира
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

        // 1. Инвентарь и 2. Установка блоков
        if (packet is PlayerAuthInputPacket) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastPlaceTime < placeDelay) return

            // Триггер: игрок в воздухе
            val posBelow = Vector3i.from(
                floor(packet.position.x).toInt(),
                floor(packet.position.y).toInt() - 2,
                floor(packet.position.z).toInt()
            )
            val blockBelowId = world.getBlockIdAt(posBelow)
            if (blockBelowId != 0) return // Не в воздухе

            // Устанавливаем слот
            inventory.heldItemSlot = blockSlot
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
        // Упрощённая проверка — можно заменить на список реальных блоков
        return item.id > 0 && item.id <= 255 // Включает компас (345), но сервер отклонит
    }

    private fun placeBlock(
        localPlayer: LocalPlayer,
        inventory: PlayerInventory,
        blockPosition: Vector3i,
        itemInHand: ItemData,
        inputPacket: PlayerAuthInputPacket,
        world: World
    ) {
        val clickPosition = Vector3i.from(blockPosition.x, blockPosition.y - 1, blockPosition.z)
        val blockIdAtClickPos = world.getBlockIdAt(clickPosition)
        if (blockIdAtClickPos == 0) {
            if (debugMode) {
                session.displayClientMessage("Scaffold: No block to click on at $clickPosition")
            }
            return
        }

        // Проверяем, является ли предмет блоком (для теста оставляем широкую проверку)
        if (!isBlockItem(itemInHand)) {
            if (debugMode) {
                session.displayClientMessage("Scaffold: Item ${itemInHand.id} (e.g., compass) is not a block!")
            }
            return
        }

        // Настраиваем InventoryTransactionPacket
        val transactionPacket = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            runtimeEntityId = localPlayer.runtimeEntityId
            blockPosition = clickPosition
            blockFace = 1 // UP
            hotbarSlot = inventory.heldItemSlot
            itemInHand = itemInHand
            playerPosition = inputPacket.position
            clickPosition = Vector3f.from(0.5f, 1.0f, 0.5f)
            val transaction = ItemUseTransaction().apply {
                actionType = ItemUseTransaction.ActionType.PLACE_BLOCK
                this.blockPosition = clickPosition
                face = 1 // UP
                hotbarSlot = inventory.heldItemSlot
                itemInHand = itemInHand
                position = inputPacket.position
                clickPosition = Vector3f.from(0.5f, 1.0f, 0.5f)
                if (!inputPacket.inputData.contains(PlayerAuthInputData.PERFORM_ITEM_INTERACTION)) {
                    inputPacket.inputData.add(PlayerAuthInputData.PERFORM_ITEM_INTERACTION)
                }
            }
            this.transactionData = transaction
        }

        session.serverBound(transactionPacket)
        if (debugMode) {
            session.displayClientMessage("Scaffold: Sent place block at $blockPosition (click on $clickPosition) with item ${itemInHand.id}")
        }

        // Обновляем кэш инвентаря (предполагаем успех, сервер откатит при ошибке)
        val updatedItem = ItemData(itemInHand.id, itemInHand.damage, itemInHand.count - 1)
        if (updatedItem.count > 0) {
            inventory.content[blockSlot] = updatedItem
        } else {
            inventory.content[blockSlot] = ItemData.AIR
        }
        inventory.updateItem(session, blockSlot)

        // Обновляем мир (опционально, сервер должен подтвердить)
        world.setBlockIdAt(blockPosition, itemInHand.id)
    }

    override fun afterPacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return
        if (interceptablePacket.packet is InventoryTransactionPacket) {
            // Простая проверка отклонения (нужна доработка)
            if (debugMode) {
                session.displayClientMessage("Scaffold: Transaction response received")
            }
        }
    }
}
