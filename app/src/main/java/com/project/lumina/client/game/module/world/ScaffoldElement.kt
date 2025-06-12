package com.project.lumina.client.game.module.world

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.LocalPlayer
import com.project.lumina.client.game.inventory.PlayerInventory
import com.project.lumina.client.game.world.World
import com.project.lumina.client.game.registry.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.*
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

class ScaffoldElement(iconResId: Int = R.drawable.ic_cube_outline_black_24dp) : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId,
    displayNameResId = R.string.module_scaffold_display_name
) {
    private val placeDelay by intValue("Place Delay (ms)", 150, 50..500)
    private val blockSlot by intValue("Block Slot", 0, 0..8)
    private val debugMode by boolValue("Debug Mode", true)
    private val towerMode by boolValue("Tower Mode", false)
    private var lastPlaceTime: Long = 0

    override fun onEnabled() {
        super.onEnabled()
        if (isSessionCreated) {
            if (debugMode) session.displayClientMessage("Scaffold: Enabled, towerMode=$towerMode")
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        if (isSessionCreated) {
            if (debugMode) session.displayClientMessage("Scaffold: Disabled")
        }
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) {
            if (debugMode) session.displayClientMessage("Scaffold: Not active - isEnabled=$isEnabled, isSessionCreated=$isSessionCreated")
            return
        }

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

        // Обработка чанков и блоков
        when (packet) {
            is LevelChunkPacket -> {
                if (debugMode) session.displayClientMessage("Scaffold: Chunk loaded at ${packet.chunkX}, ${packet.chunkZ}")
            }
            is UpdateBlockPacket -> {
                world.setBlockIdAt(packet.blockPosition, packet.definition.runtimeId)
                if (debugMode) session.displayClientMessage("Scaffold: Block updated at ${packet.blockPosition} to ${packet.definition.runtimeId}")
            }
        }

        // Размещение блоков
        if (packet is PlayerAuthInputPacket) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastPlaceTime < placeDelay + Random.nextLong(0, 50)) {
                if (debugMode) session.displayClientMessage("Scaffold: Place delay not met: ${currentTime - lastPlaceTime}ms")
                return
            }

            // Проверка движения или прыжка
            val velocity = packet.delta
            val isMoving = velocity.x != 0f || velocity.z != 0f
            val isJumping = packet.inputData.contains(PlayerAuthInputData.JUMPING)
            val pitch = packet.rotation.x
            if (!towerMode && !isMoving) {
                if (debugMode) session.displayClientMessage("Scaffold: Player not moving")
                return
            }
            if (towerMode && (!isJumping || pitch > -30f)) {
                if (debugMode) session.displayClientMessage("Scaffold: Tower mode inactive - jumping=$isJumping, pitch=$pitch")
                return
            }

            // Проверка предмета
            val itemInHand = inventory.content[blockSlot]
            if (itemInHand == null || itemInHand == ItemData.AIR || !isBlockItem(itemInHand)) {
                if (debugMode) session.displayClientMessage("Scaffold: Invalid item in slot $blockSlot: $itemInHand")
                return
            }

            // Вычисление целевой позиции
            val targetPos = if (towerMode) {
                calculateToweringPosition(packet)
            } else {
                calculateBridgingPosition(packet)
            }
            if (targetPos == null) {
                if (debugMode) session.displayClientMessage("Scaffold: No valid target position")
                return
            }

            // Проверка чанка
            val chunkX = targetPos.x shr 4
            val chunkZ = targetPos.z shr 4
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                if (debugMode) session.displayClientMessage("Scaffold: Chunk not loaded at $chunkX, $chunkZ")
                return
            }

            // Проверка, что целевая позиция свободна
            if (world.getBlockIdAt(targetPos) != 0) {
                if (debugMode) session.displayClientMessage("Scaffold: Target position $targetPos is not air")
                return
            }

            // Переключение слота
            val hotbarPacket = PlayerHotbarPacket().apply {
                selectedHotbarSlot = blockSlot
                containerId = 0
                selectHotbarSlot = true
            }
            session.serverBound(hotbarPacket)
            if (debugMode) session.displayClientMessage("Scaffold: Switched to slot $blockSlot")

            placeBlock(localPlayer, inventory, targetPos, itemInHand, packet, world)
            lastPlaceTime = currentTime
        }
    }

    private fun isBlockItem(item: ItemData): Boolean {
        val isBlock = item.isBlock()
        if (debugMode) session.displayClientMessage("Scaffold: Item ${item.getDefinition()} isBlock=$isBlock")
        return isBlock
    }

    private fun calculateBridgingPosition(packet: PlayerAuthInputPacket): Vector3i? {
        val yaw = packet.rotation.y.toDouble() * (Math.PI / 180)
        val eyePos = packet.position
        val feetY = eyePos.y - 1.5f
        val minDistance = 1.5 // Начинаем с 1.5 блока для надежности
        val maxDistance = 2.5
        val direction = Vector3f.from(-sin(yaw), 0.0f, cos(yaw)).normalize()

        for (dist in minDistance..maxDistance step 0.5) {
            val checkPos = eyePos.add(direction.mul(dist.toFloat()))
            val blockPos = Vector3i.from(
                floor(checkPos.x).toInt(),
                floor(feetY).toInt(),
                floor(checkPos.z).toInt()
            )
            val world = session.world as? World ?: return null
            if (world.getBlockIdAt(blockPos) == 0) {
                if (debugMode) session.displayClientMessage("Scaffold: Bridging target position $blockPos at distance $dist")
                return blockPos
            }
        }
        return null
    }

    private fun calculateToweringPosition(packet: PlayerAuthInputPacket): Vector3i {
        val position = packet.position
        return Vector3i.from(
            floor(position.x).toInt(),
            floor(position.y - 2).toInt(), // Позиция размещения
            floor(position.z).toInt()
        )
    }

    private fun calculateBlockFace(yaw: Float, isTowering: Boolean): Int {
        if (isTowering) return 1 // Вверх для Towering
        val normalizedYaw = (yaw % 360 + 360) % 360
        return when {
            normalizedYaw >= 45 && normalizedYaw < 135 -> 4 // Запад (-X)
            normalizedYaw >= 135 && normalizedYaw < 225 -> 2 // Север (-Z)
            normalizedYaw >= 225 && normalizedYaw < 315 -> 5 // Восток (+X)
            else -> 3 // Юг (+Z)
        }
    }

    private fun getClickPosition(face: Int, blockPos: Vector3i): Vector3f {
        return when (face) {
            1 -> Vector3f.from(0.5f, 1.0f, 0.5f) // Вверх
            2 -> Vector3f.from(0.5f, 0.5f, 0.0f) // Север
            3 -> Vector3f.from(0.5f, 0.5f, 1.0f) // Юг
            4 -> Vector3f.from(0.0f, 0.5f, 0.5f) // Запад
            5 -> Vector3f.from(1.0f, 0.5f, 0.5f) // Восток
            else -> Vector3f.from(0.5f, 0.5f, 0.5f)
        }.add(Random.nextFloat() * 0.2f - 0.1f, Random.nextFloat() * 0.2f - 0.1f, Random.nextFloat() * 0.2f - 0.1f)
    }

    private fun placeBlock(
        localPlayer: LocalPlayer,
        inventory: PlayerInventory,
        blockPosition: Vector3i,
        itemInHand: ItemData,
        inputPacket: PlayerAuthInputPacket,
        world: World
    ) {
        val yaw = inputPacket.rotation.y
        val blockFace = calculateBlockFace(yaw, towerMode)
        // Соседний блок для клика
        val clickPosition = if (towerMode) {
            // Для Towering кликаем по блоку на y - 3
            Vector3i.from(blockPosition.x, blockPosition.y - 1, blockPosition.z)
        } else {
            // Для Bridging кликаем по блоку за целевой позицией
            when (blockFace) {
                2 -> Vector3i.from(blockPosition.x, blockPosition.y, blockPosition.z + 1) // Север
                3 -> Vector3i.from(blockPosition.x, blockPosition.y, blockPosition.z - 1) // Юг
                4 -> Vector3i.from(blockPosition.x + 1, blockPosition.y, blockPosition.z) // Запад
                5 -> Vector3i.from(blockPosition.x - 1, blockPosition.y, blockPosition.z) // Восток
                else -> return
            }
        }

        // Проверка, что кликаем по существующему блоку
        if (world.getBlockIdAt(clickPosition) == 0) {
            if (debugMode) session.displayClientMessage("Scaffold: No block to click on at $clickPosition")
            return
        }

        // Настройка пакета
        val transactionPacket = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            actionType = 0 // PLACE
            blockPosition = clickPosition
            blockFace = this@apply.blockFace
            hotbarSlot = blockSlot
            this.itemInHand = itemInHand
            playerPosition = inputPacket.position
            this.clickPosition = getClickPosition(blockFace, clickPosition)
            runtimeEntityId = localPlayer.runtimeEntityId
            blockDefinition = itemInHand.blockDefinition
        }

        session.serverBound(transactionPacket)
        if (debugMode) session.displayClientMessage("Scaffold: Sent InventoryTransactionPacket at $blockPosition, face=$blockFace, clickPos=$clickPosition, item=${itemInHand.getDefinition()}")

        // Временное обновление инвентаря
        val newCount = itemInHand.getCount() - 1
        val updatedItem = if (newCount > 0) {
            itemInHand.toBuilder().count(newCount).build()
        } else {
            ItemData.AIR
        }
        inventory.content[blockSlot] = updatedItem
        val slotPacket = InventorySlotPacket().apply {
            containerId = 0
            slot = blockSlot
            item = updatedItem
        }
        session.clientBound(slotPacket)
        if (debugMode) session.displayClientMessage("Scaffold: Updated inventory slot $blockSlot, newCount=$newCount")
    }

    override fun afterClientBound(packet: BedrockPacket) {
        if (packet is InventoryTransactionPacket && packet.transactionType == InventoryTransactionType.ITEM_USE) {
            val slot = packet.hotbarSlot
            val item = packet.itemInHand
            if (item != null && item != ItemData.AIR && packet.actionType == 0) {
                val inventory = (session.localPlayer as? LocalPlayer)?.inventory as? PlayerInventory
                if (inventory != null) {
                    val currentItem = inventory.content[slot]
                    val newItem = if (currentItem == ItemData.AIR) {
                        item
                    } else {
                        item.toBuilder().count(currentItem.getCount() + 1).build()
                    }
                    inventory.content[slot] = newItem
                    val slotPacket = InventorySlotPacket().apply {
                        containerId = 0
                        slot = this@apply.slot
                        item = newItem
                    }
                    session.clientBound(slotPacket)
                    if (debugMode) session.displayClientMessage("Scaffold: Transaction failed, restored slot $slot, count=${newItem.getCount()}")
                }
            }
        }
        // Подтверждение размещения
        if (packet is UpdateBlockPacket) {
            val world = session.world as? World
            if (world != null) {
                world.setBlockIdAt(packet.blockPosition, packet.definition.runtimeId)
                if (debugMode) session.displayClientMessage("Scaffold: Confirmed block at ${packet.blockPosition}, id=${packet.definition.runtimeId}")
            }
        }
    }
}
