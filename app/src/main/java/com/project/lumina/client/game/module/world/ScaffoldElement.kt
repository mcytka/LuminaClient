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
                floor(packet.position.y).toInt() - 2,
                floor(packet.position.z).toInt()
            )
            val blockBelowId = world.getBlockIdAt(posBelow)
            if (blockBelowId != 0) return

            // Переключаем слот с помощью PlayerHotbarPacket
            val hotbarPacket = PlayerHotbarPacket().apply {
                selectedHotbarSlot = blockSlot
                containerId = 0 // Горячая панель
                selectHotbarSlot = true
            }
            session.serverBound(hotbarPacket)

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
        val clickPosition = Vector3i.from(blockPosition.x, blockPosition.y - 1, blockPosition.z)
        val blockIdAtClickPos = world.getBlockIdAt(clickPosition)
        if (blockIdAtClickPos == 0) {
            if (debugMode) {
                session.displayClientMessage("Scaffold: No block to click on at $clickPosition")
            }
            return
        }

        if (!isBlockItem(itemInHand)) {
            if (debugMode) {
                session.displayClientMessage("Scaffold: Item ${itemInHand.itemDefinition.getRuntimeId()} (e.g., compass) is not a block!")
            }
            return
        }

        val transactionPacket = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            runtimeEntityId = localPlayer.runtimeEntityId
            blockPosition = clickPosition
            blockFace = 1 // UP
            hotbarSlot = blockSlot
            itemInHand = itemInHand
            playerPosition = inputPacket.position
            clickPosition = Vector3i.from(0, 0, 0) // Центр блока
            var itemUseTransaction = ItemUseTransaction().apply {
                actionType = 1 // PLACE_BLOCK
                this.blockPosition = clickPosition
                this.blockFace = 1 // UP
                this.hotbarSlot = blockSlot
                this.itemInHand = itemInHand
                this.playerPosition = inputPacket.position
                this.clickPosition = Vector3f.from(0.5f, 0f, 0.5f) // Центр клика
                val source = InventorySource.fromContainerWindowId(0) // Горячая панель
                val action = InventoryActionData(
                    source,
                    blockSlot,
                    itemInHand, // fromItem
                    itemInHand.toBuilder()
                        .count(itemInHand.getCount() - 1)
                        .build(), // toItem
                    0 // stackNetworkId
                )
                actions.add(action)
            }
            actions.addAll(itemUseTransaction.actions)
        }

        session.serverBound(transactionPacket)
        if (debugMode) {
            session.displayClientMessage("Scaffold: Sent place block at $blockPosition (click on $clickPosition) with item ${itemInHand.itemDefinition.getRuntimeId()}")
        }

        // Обновляем кэш инвентаря
        val updatedItem = itemInHand.toBuilder()
            .count(itemInHand.getCount() - 1)
            .build()
        if (updatedItem.getCount() > 0) {
            inventory.content[blockSlot] = updatedItem
        } else {
            inventory.content[blockSlot] = ItemData.AIR
        }
        // updateItem недоступен, пропускаем

        world.setBlockIdAt(blockPosition, itemInHand.itemDefinition.getRuntimeId())
    }
}
