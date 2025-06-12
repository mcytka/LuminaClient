package com.project.lumina.client.game.module.world

import com.project.lumina.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.LocalPlayer
import com.project.lumina.client.game.inventory.PlayerInventory
import com.project.lumina.client.game.world.World
// Исправленные импорты для intValue и boolValue
import com.project.lumina.client.constructors.intValue
import com.project.lumina.client.constructors.boolValue
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.*
import org.cloudburstmc.protocol.bedrock.packet.*
import com.project.lumina.client.game.registry.* // Импорт расширений, например isBlock()
import kotlin.math.floor
import kotlin.random.Random // Для legacyRequestId, если еще используется

class ScaffoldElement(iconResId: Int = R.drawable.ic_cube_outline_black_24dp) : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId,
    displayNameResId = R.string.module_scaffold_display_name
) {

    private val placeDelay by intValue("Place Delay (ms)", 100, 50..500)
    private val blockSlot by intValue("Block Slot", 0, 0..8) // Используем для выбора слота, если useAnyBlock = false
    private val useAnyBlock by boolValue("Use Any Block", true) // Опция: использовать любой блок в инвентаре
    private val debugMode by boolValue("Debug Mode", true)
    private var lastPlaceTime: Long = 0
    private var lastRequestId: Long = 0L // Используем для requestId
    private var originalHotbarSlot: Int = -1 // Для восстановления слота

    override fun onEnabled() {
        super.onEnabled()
        if (isSessionCreated) {
            session.displayClientMessage("Scaffold: Enabled")
        }
        originalHotbarSlot = -1 // Сбрасываем при включении
    }

    override fun onDisabled() {
        super.onDisabled()
        if (isSessionCreated) {
            session.displayClientMessage("Scaffold: Disabled")
        }
        // Возвращаем на исходный слот, если он был изменен и сессия активна
        if (isSessionCreated && originalHotbarSlot != -1) {
            session.localPlayer?.inventory?.setHeldItemSlot(originalHotbarSlot) // Используем сеттер
            session.serverBound(PlayerHotbarPacket().apply {
                selectedHotbarSlot = originalHotbarSlot
                containerId = ContainerId.INVENTORY // Горячая панель
                selectHotbarSlot = true
            })
            if (debugMode) session.displayClientMessage("Scaffold: Returned to original slot $originalHotbarSlot")
            originalHotbarSlot = -1 // Сбрасываем после возврата
        }
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return // Проверяем isEnabled

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

        // Кэширование мира: это хорошая практика
        when (packet) {
            is LevelChunkPacket -> {
                // Если есть возможность, можно вызвать метод обновления чанка в world
                // world.loadChunk(packet.chunkX, packet.chunkZ, packet.payload) // Пример
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

        if (packet is PlayerAuthInputPacket) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastPlaceTime < placeDelay) return

            // Позиция, куда мы хотим установить блок (прямо под игроком)
            val targetBlockPos = Vector3i.from(
                floor(packet.position.x).toInt(),
                floor(packet.position.y - 1).toInt(), // Один блок под игроком
                floor(packet.position.z).toInt()
            )

            // Проверяем, есть ли уже блок на целевой позиции
            val blockAtTargetId = world.getBlockIdAt(targetBlockPos)
            if (blockAtTargetId != 0) {
                if (debugMode) {
                    session.displayClientMessage("Scaffold: Block already at $targetBlockPos (ID: $blockAtTargetId), skipping placement.")
                }
                return // Не ставим блок, если он уже есть
            }

            // Находим подходящий блок в инвентаре
            val blockInInventorySlot = findBlockInInventory(inventory)
            if (blockInInventorySlot == -1) {
                if (debugMode) session.displayClientMessage("Scaffold: No suitable block found in inventory!")
                return
            }
            val itemToPlace = inventory.content[blockInInventorySlot]

            // Сохраняем исходный слот при первой смене
            if (originalHotbarSlot == -1) {
                originalHotbarSlot = localPlayer.inventory.getHeldItemSlot() // Используем геттер
            }

            // Переключаемся на слот с блоком, если текущий слот не совпадает
            if (localPlayer.inventory.getHeldItemSlot() != blockInInventorySlot) { // Используем геттер
                localPlayer.inventory.setHeldItemSlot(blockInInventorySlot) // Используем сеттер
                session.serverBound(PlayerHotbarPacket().apply {
                    selectedHotbarSlot = blockInInventorySlot
                    containerId = ContainerId.INVENTORY // Горячая панель
                    selectHotbarSlot = true
                })
                if (debugMode) session.displayClientMessage("Scaffold: Switched to slot $blockInInventorySlot")
            }

            // Позиция блока, на который мы "кликаем", чтобы установить наш блок.
            // Если ставим на Y-1, то кликаем на блок на Y-2.
            val clickBlockPosition = Vector3i.from(
                targetBlockPos.x,
                targetBlockPos.y - 1, // Блок на один ниже целевого
                targetBlockPos.z
            )
            // Грань, на которую мы кликаем (вверх)
            val clickBlockFace = 1 // 1 = UP

            // Проверяем, есть ли вообще блок, на который можно кликнуть
            val blockAtClickPosId = world.getBlockIdAt(clickBlockPosition)
            if (blockAtClickPosId == 0) {
                if (debugMode) session.displayClientMessage("Scaffold: No block to click on at $clickBlockPosition (needed for placement)")
                return // Нет опоры для установки блока
            }


            placeBlock(
                localPlayer,
                inventory,
                targetBlockPos, // Позиция, куда ставим блок
                clickBlockPosition, // Позиция, на которую кликаем
                clickBlockFace, // Грань, на которую кликаем
                itemToPlace,
                packet,
                world
            )
            lastPlaceTime = currentTime
        }
    }

    // Ищет блок в инвентаре. Приоритет: горячая панель, затем весь инвентарь (если useAnyBlock).
    private fun findBlockInInventory(inventory: PlayerInventory): Int {
        val currentHotbarSlot = inventory.getHeldItemSlot() // Используем геттер

        // 1. Проверяем текущий горячий слот, если он уже является блоком
        val currentItem = inventory.content[currentHotbarSlot]
        if (currentItem != null && currentItem != ItemData.AIR && isBlockItem(currentItem)) {
            return currentHotbarSlot
        }

        // 2. Если не текущий, ищем в настроенном blockSlot (если useAnyBlock = false)
        if (!useAnyBlock) {
            val itemInConfiguredSlot = inventory.content[blockSlot]
            if (itemInConfiguredSlot != null && itemInConfiguredSlot != ItemData.AIR && isBlockItem(itemInConfiguredSlot)) {
                return blockSlot
            }
        }

        // 3. Ищем в горячей панели (слоты 0-8)
        for (i in 0..8) {
            val item = inventory.content[i]
            if (item != null && item != ItemData.AIR && isBlockItem(item)) {
                return i
            }
        }

        // 4. Если useAnyBlock включено, ищем во всем остальном инвентаре
        if (useAnyBlock) {
            for (i in 9 until inventory.content.size) { // Начиная с 9-го слота (инвентарь)
                val item = inventory.content[i]
                if (item != null && item != ItemData.AIR && isBlockItem(item)) {
                    return i
                }
            }
        }
        return -1 // Блок не найден
    }

    private fun isBlockItem(item: ItemData): Boolean {
        // Предполагаем, что item.isBlock() правильно реализован в com.project.lumina.client.game.registry.*
        // Если для булыжника (ID 1) isBlock() возвращает false, это указывает на проблему в вашей реализации isBlock().
        // Можно добавить здесь логику для "форсирования" известных блоков, если isBlock() не идеален.
        // Пример: if (item.itemDefinition.runtimeId == 1) return true // Булыжник
        return item.isBlock()
    }

    private fun placeBlock(
        localPlayer: LocalPlayer,
        inventory: PlayerInventory,
        targetBlockPosition: Vector3i, // Место, куда устанавливаем блок
        clickBlockPosition: Vector3i, // Место, куда кликаем
        clickBlockFace: Int, // Грань, на которую кликаем
        itemInHand: ItemData,
        inputPacket: PlayerAuthInputPacket,
        world: World
    ) {
        // Увеличиваем requestId и используем его
        lastRequestId++

        val transaction = ItemUseTransaction().apply {
            actionType = 1 // PLACE_BLOCK
            blockPosition = clickBlockPosition
            blockFace = clickBlockFace
            hotbarSlot = localPlayer.inventory.getHeldItemSlot() // Используем геттер
            itemInHand = itemInHand
            playerPosition = inputPacket.position
            clickPosition = Vector3f.from(0.5f, 0.5f, 0.5f) // Центр грани блока
            blockDefinition = itemInHand.itemDefinition as? org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
                ?: throw IllegalStateException("Item ${itemInHand.itemDefinition.identifier} is not a valid block definition!")
            // requestId и legacyRequestId устанавливаются на уровне InventoryTransaction (родитель ItemUseTransaction)
            requestId = lastRequestId
            legacyRequestId = Random.nextInt() // Генерируем новый legacyRequestId
        }

        // Создаем InventoryTransactionPacket, передавая ItemUseTransaction как поле 'transaction'
        val transactionPacket = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            runtimeEntityId = localPlayer.runtimeEntityId
            // Само поле транзакции
            this.transaction = transaction

            // Действия инвентаря
            actions.add(
                InventoryActionData(
                    InventorySource.fromContainerWindowId(ContainerId.INVENTORY), // Источник: инвентарь
                    localPlayer.inventory.getHeldItemSlot(), // Слот, из которого берется предмет
                    itemInHand, // Предмет до изменения
                    itemInHand.toBuilder().count(itemInHand.count - 1).build() // Предмет после изменения (количество уменьшено)
                )
            )
        }

        session.serverBound(transactionPacket)
        if (debugMode) {
            session.displayClientMessage("Scaffold: Sent InventoryTransactionPacket for ${targetBlockPosition} with item ${itemInHand.itemDefinition.identifier}, slot ${localPlayer.inventory.getHeldItemSlot()}, requestId ${transaction.requestId}, legacyRequestId ${transaction.legacyRequestId}")
        }

        // Обновляем локальное состояние мира и инвентаря
        world.setBlockIdAt(targetBlockPosition, itemInHand.itemDefinition.runtimeId)
        val updatedItem = itemInHand.toBuilder().count(itemInHand.count - 1).build()
        if (updatedItem.count > 0) { // Используем .count, а не .getCount()
            inventory.content[localPlayer.inventory.getHeldItemSlot()] = updatedItem
        } else {
            inventory.content[localPlayer.inventory.getHeldItemSlot()] = ItemData.AIR
        }
    }

    override fun afterPacketBound(packet: BedrockPacket) {
        if (debugMode && packet is InventoryTransactionPacket) {
            val currentSlot = session.localPlayer?.inventory?.getHeldItemSlot() ?: -1 // Используем геттер
            val receivedRequestId = packet.transaction?.requestId ?: -1L // Доступ через transaction
            val receivedLegacyRequestId = packet.transaction?.legacyRequestId ?: -1 // Доступ через transaction

            session.displayClientMessage("Scaffold: Received response for InventoryTransactionPacket, slot $currentSlot, requestId $receivedRequestId, legacyRequestId $receivedLegacyRequestId, packet: ${packet.toString().take(50)}...")
        }
    }
}
