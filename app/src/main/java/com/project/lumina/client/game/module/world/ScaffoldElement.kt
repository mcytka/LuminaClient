package com.project.lumina.client.game.module.world

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.NetBound
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.inventory.PlayerInventory
import com.project.lumina.client.game.registry.BlockMapping
import com.project.lumina.client.game.world.World
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket
import kotlin.math.floor

class ScaffoldElement : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId = R.drawable.ic_cube_outline_black_24dp,
    defaultEnabled = false,
    displayNameResId = R.string.scaffold
) {
    private lateinit var world: World
    private lateinit var playerInventory: PlayerInventory
    private lateinit var blockMapping: BlockMapping
    private var lastBlockPlaceTime = 0L
    private val placeDelay = 100L // Задержка между установками блоков (в миллисекундах)
    private val lookaheadTime = 0.1f // Время предсказания для быстрого движения

    // Настройки чита
    private val towerMode by boolValue("TowerMode", false)
    private val placeRate by intValue("PlaceRate", 100, 50..500)

    override fun onEnabled() {
        super.onEnabled()
        if (isSessionCreated) {
            world = session.world
            playerInventory = session.localPlayer.inventory
            blockMapping = session.blockMapping
        }
    }

    override fun beforeServerBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return

        val packet = interceptablePacket.packet
        if (packet is PlayerAuthInputPacket) {
            handlePlayerInput(packet)
        }
    }

    private fun handlePlayerInput(packet: PlayerAuthInputPacket) {
        val player = session.localPlayer
        val position = packet.position
        val velocity = packet.delta
        val isMoving = velocity.x != 0f || velocity.z != 0f
        val isOnGround = player.isOnGround

        // Проверка активации Scaffold
        if (!shouldActivateScaffold(isMoving, isOnGround, packet)) return

        // Предсказание позиции для установки блока
        val targetPos = calculateTargetPosition(position, velocity)

        // Проверка, можно ли установить блок
        if (!canPlaceBlock(targetPos)) return

        // Выбор блока из инвентаря
        val blockSlot = selectBlockFromInventory()
        if (blockSlot == null) return

        // Переключение на нужный слот
        switchToHotbarSlot(blockSlot)

        // Имитация поворота головы вниз для правдоподобности
        adjustHeadRotation(packet)

        // Установка блока
        placeBlock(targetPos, blockSlot, packet)
    }

    private fun shouldActivateScaffold(isMoving: Boolean, isOnGround: Boolean, packet: PlayerAuthInputPacket): Boolean {
        if (towerMode) {
            // Логика для Towering: активируется при прыжке и взгляде вверх
            return packet.inputData.contains(PlayerAuthInputData.JUMPING) &&
                    packet.rotation.x < -45f // Взгляд вверх
        } else {
            // Логика для Bridging: активируется при движении и отсутствии блока под ногами
            val posBelow = Vector3i.from(
                floor(session.localPlayer.posX).toInt(),
                floor(session.localPlayer.posY - 1).toInt(),
                floor(session.localPlayer.posZ).toInt()
            )
            return isMoving && world.getBlockIdAt(posBelow) == blockMapping.airId
        }
    }

    private fun calculateTargetPosition(position: Vector3f, velocity: Vector3f): Vector3i {
        // Предсказание позиции с учётом скорости
        val predictedX = position.x + velocity.x * lookaheadTime
        val predictedZ = position.z + velocity.z * lookaheadTime
        val predictedY = if (towerMode) {
            floor(position.y).toInt() // Для Towering ставим блок на уровне ног
        } else {
            floor(position.y - 1).toInt() // Для Bridging ставим блок под ногами
        }

        return Vector3i.from(
            floor(predictedX).toInt(),
            predictedY,
            floor(predictedZ).toInt()
        )
    }

    private fun canPlaceBlock(pos: Vector3i): Boolean {
        // Проверка, пуст ли целевой блок
        if (world.getBlockIdAt(pos) != blockMapping.airId) return false

        // Проверка, чтобы не ставить блоки слишком часто
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBlockPlaceTime < placeRate) return false

        return true
    }

    private fun selectBlockFromInventory(): Int? {
        // Поиск блока в хотбаре
        return playerInventory.searchForItemInHotbar { item ->
            item.isBlock() && item.count > 0
        }
    }

    private fun switchToHotbarSlot(slot: Int) {
        if (playerInventory.heldItemSlot != slot) {
            val hotbarPacket = PlayerHotbarPacket().apply {
                selectedHotbarSlot = slot
                containerId = 0
                selectHotbarSlot = true
            }
            session.serverBound(hotbarPacket)
            playerInventory.heldItemSlot = slot
        }
    }

    private fun adjustHeadRotation(packet: PlayerAuthInputPacket) {
        // Имитация взгляда вниз
        val newPitch = 80f // Смотрим почти вертикально вниз
        packet.rotation = Vector3f.from(newPitch, packet.rotation.y, packet.rotation.z)
    }

    private fun placeBlock(pos: Vector3i, slot: Int, packet: PlayerAuthInputPacket) {
        val itemInHand = playerInventory.content[slot]
        val blockDefinition = itemInHand.blockDefinition ?: return

        // Формирование пакета для установки блока
        val transactionPacket = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            actionType = 0 // CLICK_BLOCK
            this.blockPosition = pos
            blockFace = 1 // UP (ставим на верхнюю грань блока ниже)
            hotbarSlot = slot
            this.itemInHand = itemInHand
            playerPosition = packet.position
            clickPosition = Vector3f.from(0.5f, 1f, 0.5f) // Центр верхней грани
            this.blockDefinition = blockDefinition
        }

        // Отправка пакета
        session.serverBound(transactionPacket)

        // Обновление времени последней установки
        lastBlockPlaceTime = System.currentTimeMillis()

        // Имитация действия игрока
        session.localPlayer.swing()

        // Обновление инвентаря локально
        updateInventoryAfterPlace(slot, itemInHand)
    }

    private fun updateInventoryAfterPlace(slot: Int, item: ItemData) {
        // Уменьшение количества блоков в инвентаре
        val newCount = item.count - 1
        val newItem = if (newCount > 0) {
            item.toBuilder().count(newCount).build()
        } else {
            ItemData.AIR
        }
        playerInventory.content[slot] = newItem
        playerInventory.updateItem(session, slot)
    }

    override fun afterClientBound(packet: org.cloudburstmc.protocol.bedrock.packet.BedrockPacket) {
        // Обработка отклонённых транзакций
        if (packet is InventoryTransactionPacket && packet.transactionType == InventoryTransactionType.ITEM_USE) {
            if (packet.itemUseTransaction?.clientInteractPrediction == ItemUseTransaction.PredictedResult.FAILURE) {
                // Откат изменений в инвентаре при отклонении
                val slot = packet.hotbarSlot
                val item = packet.itemInHand
                if (item != null && item != ItemData.AIR) {
                    val currentItem = playerInventory.content[slot]
                    if (currentItem == ItemData.AIR) {
                        playerInventory.content[slot] = item
                    } else {
                        playerInventory.content[slot] = currentItem.toBuilder()
                            .count(currentItem.count + 1)
                            .build()
                    }
                    playerInventory.updateItem(session, slot)
                }
            }
        }
    }
}
