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
import kotlin.math.sin
import kotlin.math.cos

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

        // Кэширование мира (игнорируем ломание блока для предотвращения ложных срабатываний)
        when (packet) {
            is LevelChunkPacket -> {
                if (debugMode) {
                    session.displayClientMessage("Scaffold: Chunk loaded at ${packet.chunkX}, ${packet.chunkZ}")
                }
            }
            is UpdateBlockPacket -> {
                if (packet.definition.runtimeId != 0) { // Игнорируем воздух
                    world.setBlockIdAt(packet.blockPosition, packet.definition.runtimeId)
                    if (debugMode) {
                        session.displayClientMessage("Scaffold: Block updated at ${packet.blockPosition} to ${packet.definition.runtimeId}")
                    }
                }
            }
        }

        // Инвентарь и установка блоков
        if (packet is PlayerAuthInputPacket) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastPlaceTime < placeDelay) return

            val playerPos = packet.position
            val playerYaw = packet.rotation.y // Угол поворота (Yaw) для направления взгляда
            val posBelow = Vector3i.from(
                floor(playerPos.x).toInt(),
                floor(playerPos.y - 1).toInt(), // Блок под ногами
                floor(playerPos.z).toInt()
            )
            val blockBelowId = world.getBlockIdAt(posBelow)
            if (blockBelowId == 0) {
                if (debugMode) {
                    session.displayClientMessage("Scaffold: No block below at $posBelow (player at $playerPos)")
                }
                return
            }

            // Определяем позицию впереди по направлению взгляда
            val clickPosition = getClickPosition(posBelow, playerYaw)
            val blockAheadId = world.getBlockIdAt(clickPosition)
            if (blockAheadId != 0) {
                if (debugMode) {
                    session.displayClientMessage("Scaffold: Block ahead at $clickPosition, skipping placement")
                }
                return
            }

            // Проверка текущего слота
            val currentSlot = localPlayer.inventory.heldItemSlot
            if (currentSlot != blockSlot) {
                if (debugMode) {
                    session.displayClientMessage("Scaffold: Current slot $currentSlot, expected $blockSlot (manual switch required)")
                }
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

            if (!isBlockItem(itemInHand)) {
                if (debugMode) {
                    session.displayClientMessage("Scaffold: Item ${itemInHand.itemDefinition.getRuntimeId()} (${itemInHand.itemDefinition.identifier}) is not a block!")
                }
                return
            }

            placeBlock(localPlayer, inventory, clickPosition, itemInHand, packet, world, getBlockFace(playerYaw))
            lastPlaceTime = currentTime
        }
    }

    private fun getClickPosition(basePos: Vector3i, yaw: Float): Vector3i {
        // Определяем направление взгляда
        val angle = Math.toRadians(yaw.toDouble()).toFloat()
        val dx = -sin(angle).toInt() // Направление по X (север/юг)
        val dz = cos(angle).toInt()  // Направление по Z (запад/восток)
        return Vector3i.from(basePos.x + dx, basePos.y, basePos.z + dz) // Смещение на 1 блок вперёд
    }

    private fun getBlockFace(yaw: Float): Int {
        // Определяем грань по углу поворота (Yaw)
        val angle = (yaw % 360 + 360) % 360 // Нормализация угла
        return when {
            angle in 45.0..135.0 -> 3 // Юг
            angle in 135.0..225.0 -> 4 // Запад
            angle in 225.0..315.0 -> 2 // Север
            else -> 5 // Восток
        }
    }

    private fun isBlockItem(item: ItemData): Boolean {
        val isBlock = item.isBlock() // Используем расширение
        if (debugMode && !isBlock) {
            session.displayClientMessage("Scaffold: isBlock() returned false for item ${item.itemDefinition.getRuntimeId()} (${item.itemDefinition.identifier})")
        }
        // Временная проверка для булыжника (runtimeId = 1)
        if (item.itemDefinition.getRuntimeId() == 1 && !isBlock) {
            session.displayClientMessage("Scaffold: Forcing bulyzhnik (ID 1) as block")
            return true
        }
        return isBlock
    }

    private fun placeBlock(
        localPlayer: LocalPlayer,
        inventory: PlayerInventory,
        blockPosition: Vector3i,
        itemInHand: ItemData,
        inputPacket: PlayerAuthInputPacket,
        world: World,
        blockFace: Int
    ) {
        val clickPosition = blockPosition
        val blockIdAtClickPos = world.getBlockIdAt(Vector3i.from(blockPosition.x, blockPosition.y - 1, blockPosition.z)) // Блок под позицией
        if (blockIdAtClickPos == 0) {
            if (debugMode) {
                session.displayClientMessage("Scaffold: No block to click on at ${Vector3i.from(blockPosition.x, blockPosition.y - 1, blockPosition.z)}")
            }
            return
        }

        // Настройка ItemUseTransaction
        val itemUseTransaction = ItemUseTransaction()
        itemUseTransaction.actionType = 1 // PLACE_BLOCK (проверь перечисление)
        itemUseTransaction.blockPosition = Vector3i.from(blockPosition.x, blockPosition.y - 1, blockPosition.z) // Блок для клика
        itemUseTransaction.blockFace = blockFace // Определённая грань
        itemUseTransaction.hotbarSlot = localPlayer.inventory.heldItemSlot
        itemUseTransaction.itemInHand = itemInHand
        itemUseTransaction.playerPosition = inputPacket.position
        itemUseTransaction.clickPosition = Vector3f.from(0.5f, 0.5f, 0.5f) // Центр грани

        // Настройка InventoryActionData
        val source = InventorySource.fromContainerWindowId(0) // Горячая панель
        val action = InventoryActionData(
            source,
            localPlayer.inventory.heldItemSlot,
            itemInHand,
            itemInHand,
            0 // stackNetworkId
        )
        itemUseTransaction.actions.add(action)

        // Настройка InventoryTransactionPacket
        val transactionPacket = InventoryTransactionPacket()
        transactionPacket.transactionType = InventoryTransactionType.ITEM_USE
        transactionPacket.runtimeEntityId = localPlayer.runtimeEntityId
        transactionPacket.blockPosition = Vector3i.from(blockPosition.x, blockPosition.y - 1, blockPosition.z)
        transactionPacket.blockFace = blockFace
        transactionPacket.hotbarSlot = localPlayer.inventory.heldItemSlot
        transactionPacket.itemInHand = itemInHand
        transactionPacket.playerPosition = inputPacket.position
        transactionPacket.clickPosition = Vector3f.from(0.5f, 0.5f, 0.5f) // Центр грани
        transactionPacket.actions.addAll(itemUseTransaction.actions)

        // Отправка пакета
        session.serverBound(transactionPacket)
        if (debugMode) {
            session.displayClientMessage("Scaffold: Sent InventoryTransactionPacket at $clickPosition with item ${itemInHand.itemDefinition.getRuntimeId()} (${itemInHand.itemDefinition.identifier}), face $blockFace, slot ${localPlayer.inventory.heldItemSlot}")
        }

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
