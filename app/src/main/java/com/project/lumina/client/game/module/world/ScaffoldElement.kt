package com.project.lumina.client.game.module.world

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction // Не импортируем ActionType отсюда
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

class ScaffoldElement(iconResId: Int = R.drawable.ic_cube_outline_black_24dp) : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId,
    displayNameResId = R.string.module_scaffold_display_name
) {

    private var lastPlayerPosition: Vector3f = Vector3f.ZERO
    private var lastPlayerRotation: Vector3f = Vector3f.ZERO // pitch, yaw

    private var isScaffoldActive by boolValue("Scaffold Active", false)
    // Захардкоженный список предпочитаемых блоков для тестирования
    private val hardcodedPreferredBlocks = setOf("minecraft:planks", "minecraft:wool", "minecraft:stone", "minecraft:dirt")

    private val playerInventory: MutableMap<Int, ItemData> = mutableMapOf()

    private fun findScaffoldBlock(): ItemData? {
        val inventoryIndices = playerInventory.keys.sorted()

        // Сначала пытаемся найти захардкоженные предпочитаемые блоки
        for (slotIndex in inventoryIndices) {
            val itemData = playerInventory[slotIndex] ?: continue

            if (itemData.count > 0 && itemData.definition != null) {
                val itemIdentifier = itemData.definition.identifier // e.g., "minecraft:planks"
                val blockDefinition = session.blockMapping.getDefinition(session.blockMapping.getRuntimeByIdentifier(itemIdentifier))

                if (blockDefinition != null && blockDefinition.identifier != "minecraft:air") {
                    // Это блок и не воздух
                    if (hardcodedPreferredBlocks.contains(itemIdentifier)) {
                        return itemData // Найден предпочитаемый блок
                    }
                }
            }
        }

        // Если предпочитаемые блоки не найдены, ищем любой пригодный для использования блок
        for (slotIndex in inventoryIndices) {
            val itemData = playerInventory[slotIndex] ?: continue

            if (itemData.count > 0 && itemData.definition != null) {
                val itemIdentifier = itemData.definition.identifier
                val blockDefinition = session.blockMapping.getDefinition(session.blockMapping.getRuntimeByIdentifier(itemIdentifier))

                if (blockDefinition != null && blockDefinition.identifier != "minecraft:air") {
                    return itemData // Найден любой пригодный для использования блок
                }
            }
        }
        return null // Подходящий блок не найден
    }

    override fun onEnabled() {
        super.onEnabled()
    }

    override fun onDisabled() {
        super.onDisabled()
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        when (packet) {
            is PlayerAuthInputPacket -> {
                lastPlayerPosition = packet.position
                lastPlayerRotation = packet.rotation

                // Базовая логика Scaffold: если активен и игрок не на земле, пытаемся разместить блок
                if (isScaffoldActive && !session.localPlayer.isOnGround) {
                    val playerFootPos = session.localPlayer.vec3Position
                    // Целевая позиция блока - один блок ниже ног игрока
                    val targetBlockPos = Vector3i.from(
                        playerFootPos.x.toInt(),
                        playerFootPos.y.toInt() - 1,
                        playerFootPos.z.toInt()
                    )

                    // Проверяем, является ли целевая позиция блока воздухом, используя кеш мира
                    if (session.world.getBlockIdAt(targetBlockPos) == session.blockMapping.airId) {
                        val blockToPlace = findScaffoldBlock()
                        if (blockToPlace != null) {
                            placeBlock(targetBlockPos, blockToPlace)
                        }
                    }
                }
            }
            is InventoryContentPacket -> {
                // Обновляем инвентарь игрока полным содержимым
                playerInventory.clear()
                packet.contents.forEachIndexed { index, item ->
                    playerInventory[index] = item
                }
            }
            is InventorySlotPacket -> {
                // Обновляем конкретный слот инвентаря
                playerInventory[packet.slot] = packet.item
            }
        }
    }

    override fun afterPacketBound(packet: org.cloudburstmc.protocol.bedrock.packet.BedrockPacket) {
        if (!isEnabled) return
        super.afterPacketBound(packet)
    }

    override fun onDisconnect(reason: String) {
        super.onDisconnect(reason)
        lastPlayerPosition = Vector3f.ZERO
        lastPlayerRotation = Vector3f.ZERO
        playerInventory.clear()
    }

    private fun placeBlock(targetPosition: Vector3i, itemToPlace: ItemData) {
        val transaction = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            
            // Устанавливаем actionType как целочисленное значение. Для PLACE это обычно 0.
            actionType = 0 // ИСПРАВЛЕНО: Прямое целочисленное значение.
            blockPosition = targetPosition
            blockFace = 1 // Лицо UP (положительная ось Y) для размещения на стороне блока ниже
            hotbarSlot = session.localPlayer.inventory.heldItemSlot
            itemInHand = session.localPlayer.inventory.hand
            playerPosition = session.localPlayer.vec3Position
            headPosition = session.localPlayer.vec3Rotation // Maps to player's head rotation
            clickPosition = Vector3f.from(0.5f, 0.5f, 0.5f) // Типичная позиция клика по поверхности блока

            // Устанавливаем blockDefinition для пакета транзакции. Получаем его из runtime ID предмета.
            blockDefinition = session.blockMapping.getDefinition(itemToPlace.definition.runtimeId)

            // Это действие описывает изменение в инвентаре (один предмет потреблен)
            actions.add(InventoryActionData(
                InventorySource.fromContainerWindowId(ContainerId.INVENTORY),
                session.localPlayer.inventory.heldItemSlot,
                itemToPlace, // fromItem: Предмет до размещения
                itemToPlace.toBuilder().count(itemToPlace.count - 1).build() // toItem: Предмет после размещения (на одну единицу меньше)
            ))
        }
        session.serverBound(transaction)
    }
}
