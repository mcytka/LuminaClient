package com.project.lumina.client.game.module.world

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.LocalPlayer
import com.project.lumina.client.game.inventory.PlayerInventory
import com.project.lumina.client.game.world.World
import com.project.lumina.client.constructors.intValue
import com.project.lumina.client.constructors.boolValue
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.*
import org.cloudburstmc.protocol.bedrock.packet.*
import com.project.lumina.client.game.registry.* // Импорт расширений (предполагаем, что isBlock() здесь)
import kotlin.math.floor
import kotlin.random.Random

class ScaffoldElement(iconResId: Int = R.drawable.ic_cube_outline_black_24dp) : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId,
    displayNameResId = R.string.module_scaffold_display_name
) {

    private val placeDelay by intValue("Place Delay (ms)", 100, 50..500)
    // Можно убрать blockSlot, если Scaffold будет искать любой блок
    private val useAnyBlock by boolValue("Use Any Block", true) // Новая опция: использовать любой блок
    private val debugMode by boolValue("Debug Mode", true)
    private var lastPlaceTime: Long = 0
    private var requestIdCounter = 0L
    private var originalHotbarSlot: Int = -1 // Для хранения исходного слота

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
        // Возвращаем на исходный слот, если он был изменен
        if (isSessionCreated && originalHotbarSlot != -1) {
            session.localPlayer?.inventory?.heldItemSlot = originalHotbarSlot
            session.serverBound(PlayerHotbarPacket().apply {
                selectedHotbarSlot = originalHotbarSlot
                containerId = ContainerId.INVENTORY
                selectHotbarSlot = true
            })
            if (debugMode) session.displayClientMessage("Scaffold: Returned to original slot $originalHotbarSlot")
            originalHotbarSlot = -1 // Сбрасываем после возврата
        }
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return

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

        if (packet is PlayerAuthInputPacket) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastPlaceTime < placeDelay) return

            val playerPos = packet.position
            // Позиция блока, который должен быть установлен (под игроком)
            val blockToPlaceAt = Vector3i.from(
                floor(playerPos.x).toInt(),
                floor(playerPos.y - 1).toInt(), // Один блок под игроком
                floor(playerPos.z).toInt()
            )

            val blockAtTargetId = world.getBlockIdAt(blockToPlaceAt)

            // Если на целевой позиции уже есть блок, ничего не делаем
            if (blockAtTargetId != 0) {
                if (debugMode) {
                    session.displayClientMessage("Scaffold: Block already at $blockToPlaceAt (ID: $blockAtTargetId)")
                }
                return
            }

            // Ищем блок для установки
            val blockInInventorySlot = findBlockInInventory(inventory)
            if (blockInInventorySlot == -1) {
                if (debugMode) session.displayClientMessage("Scaffold: No suitable block found in inventory!")
                return
            }
            val itemToPlace = inventory.content[blockInInventorySlot]

            // Сохраняем исходный слот, если еще не сохранили
            if (originalHotbarSlot == -1) {
                originalHotbarSlot = localPlayer.inventory.heldItemSlot
            }

            // Переключаемся на слот с блоком, если нужно
            if (localPlayer.inventory.heldItemSlot != blockInInventorySlot) {
                localPlayer.inventory.heldItemSlot = blockInInventorySlot
                session.serverBound(PlayerHotbarPacket().apply {
                    selectedHotbarSlot = blockInInventorySlot
                    containerId = ContainerId.INVENTORY // Контейнер инвентаря
                    selectHotbarSlot = true // Это важно
                })
                if (debugMode) session.displayClientMessage("Scaffold: Switched to slot $blockInInventorySlot")
            }

            // Позиция блока, на который мы "кликаем", чтобы поставить наш блок.
            // Если ставим на Y-1, то кликаем на блок на Y-2.
            val blockClickPosition = Vector3i.from(
                blockToPlaceAt.x,
                blockToPlaceAt.y - 1,
                blockToPlaceAt.z
            )
            // Грань, на которую мы кликаем (вверх)
            val blockClickFace = 1 // 1 = UP

            placeBlock(
                localPlayer,
                inventory,
                blockToPlaceAt, // Позиция, куда ставим блок
                blockClickPosition, // Позиция, на которую кликаем
                blockClickFace, // Грань, на которую кликаем
                itemToPlace,
                packet,
                world
            )
            lastPlaceTime = currentTime
        }
    }

    private fun findBlockInInventory(inventory: PlayerInventory): Int {
        // Проверяем только горячую панель (слоты 0-8)
        for (i in 0..8) {
            val item = inventory.content[i]
            if (item != null && item != ItemData.AIR && isBlockItem(item)) {
                return i
            }
        }
        // Если useAnyBlock включено, ищем во всем инвентаре
        if (useAnyBlock) {
            for (i in 9 until inventory.content.size) { // Начиная с 9-го слота (инвентарь)
                val item = inventory.content[i]
                if (item != null && item != ItemData.AIR && isBlockItem(item)) {
                    return i
                }
            }
        }
        return -1
    }

    private fun isBlockItem(item: ItemData): Boolean {
        // Убедитесь, что item.isBlock() работает корректно и находится в com.project.lumina.client.game.registry.*
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
        val requestId = requestIdCounter++
        val legacyRequestId = Random.nextInt()

        val transactionPacket = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            runtimeEntityId = localPlayer.runtimeEntityId
            this.blockPosition = clickBlockPosition // Позиция блока, на который кликаем
            this.blockFace = clickBlockFace // Грань, на которую кликаем
            hotbarSlot = localPlayer.inventory.heldItemSlot
            this.itemInHand = itemInHand
            playerPosition = inputPacket.position
            clickPosition = Vector3f.from(0.5f, 0.5f, 0.5f) // Центр грани блока
            // blockDefinition должен быть BlockDefinition
            this.blockDefinition = itemInHand.itemDefinition as? org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
                ?: throw IllegalStateException("Item ${itemInHand.itemDefinition.identifier} is not a valid block definition!")
        }

        // Actions: из какого слота взят, какой предмет был, какой предмет стал (уменьшился на 1)
        transactionPacket.actions.add(
            InventoryActionData(
                InventorySource.fromContainerWindowId(ContainerId.INVENTORY), // Источник: инвентарь игрока
                localPlayer.inventory.heldItemSlot, // Слот, из которого берется предмет
                itemInHand, // Предмет до изменения
                itemInHand.toBuilder().count(itemInHand.count - 1).build() // Предмет после изменения (количество уменьшено)
            )
        )
        // Для PLACE_BLOCK не нужно третье действие, если нет дополнительных перетаскиваний.
        // Последний аргумент в InventoryActionData(source, slot, fromItem, toItem, ???)
        // 5-й аргумент - stackNetworkId, в некоторых версиях API он может быть необязательным
        // или иметь дефолтное значение 0. Убрал его для упрощения.

        transactionPacket.requestId = requestId
        transactionPacket.legacyRequestId = legacyRequestId

        session.serverBound(transactionPacket)
        if (debugMode) {
            session.displayClientMessage("Scaffold: Sent InventoryTransactionPacket for ${targetBlockPosition} with item ${itemInHand.itemDefinition.identifier}, slot ${localPlayer.inventory.heldItemSlot}, requestId $requestId, legacyRequestId $legacyRequestId")
        }

        // Обновляем локальное состояние мира
        world.setBlockIdAt(targetBlockPosition, itemInHand.itemDefinition.runtimeId)
        // Уменьшаем количество предмета в локальном инвентаре
        inventory.content[localPlayer.inventory.heldItemSlot] = itemInHand.toBuilder().count(itemInHand.count - 1).build()
    }

    // Если есть какой-либо ответ от сервера, который нужно обработать
    // Например, для подтверждения транзакции или дебага
    override fun afterPacketBound(packet: BedrockPacket) {
        if (debugMode && packet is InventoryTransactionPacket) {
            val currentSlot = session.localPlayer?.inventory?.heldItemSlot ?: -1
            session.displayClientMessage("Scaffold: Received response for InventoryTransactionPacket, slot $currentSlot, requestId ${packet.requestId}, legacyRequestId ${packet.legacyRequestId}, packet: ${packet.toString().take(50)}...")
        }
    }
}
