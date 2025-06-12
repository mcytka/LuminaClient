package com.project.lumina.client.constructors

import android.util.Log
import com.project.lumina.client.R
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.LocalPlayer
import com.project.lumina.client.game.inventory.ItemData
import com.project.lumina.client.game.inventory.PlayerInventory
import com.project.lumina.client.game.registry.BlockDefinition
import com.project.lumina.client.game.world.World
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
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
    defaultEnabled = false
) {
    // Конфигурационные параметры
    private val placeDelay by intValue("PlaceDelay", 2, 0..10) // Задержка между установками блоков (в тиках)
    private val lookDownAngle by floatValue("LookDownAngle", 75.0f, 0.0f..90.0f) // Угол взгляда вниз для имитации
    private val lookaheadTime by floatValue("LookaheadTime", 0.1f, 0.0f..0.5f) // Время предсказания для быстрого движения

    // Внутреннее состояние
    private lateinit var world: World
    private lateinit var player: LocalPlayer
    private lateinit var inventory: PlayerInventory
    private var lastPlaceTick = 0L
    private val blockCache = mutableMapOf<Vector3i, Int>() // Кэш блоков мира
    private var selectedBlockSlot: Int? = null

    override fun onEnabled() {
        super.onEnabled()
        if (::session.isInitialized) {
            world = session.world
            player = session.localPlayer
            inventory = player.inventory
        }
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !::session.isInitialized) return

        val packet = interceptablePacket.packet
        when (packet) {
            is PlayerAuthInputPacket -> handlePlayerAuthInput(packet)
            is InventoryTransactionPacket -> handleInventoryTransaction(packet)
        }
    }

    override fun afterPacketBound(packet: BedrockPacket) {
        if (!isEnabled || !::session.isInitialized) return

        when (packet) {
            is InventoryContentPacket -> updateInventory(packet)
            is InventorySlotPacket -> updateInventorySlot(packet)
            is UpdateBlockPacket -> updateBlockCache(packet.blockPosition, packet.definition.runtimeId)
        }
    }

    private fun updateInventory(packet: InventoryContentPacket) {
        if (packet.containerId == 0) { // Основной инвентарь
            inventory.content.indices.forEach { slot ->
                inventory.content[slot] = packet.contents.getOrNull(slot) ?: ItemData.AIR
            }
            updateSelectedBlock()
        }
    }

    private fun updateInventorySlot(packet: InventorySlotPacket) {
        if (packet.containerId == 0) {
            inventory.content[packet.slot] = packet.item
            updateSelectedBlock()
        }
    }

    private fun updateBlockCache(position: Vector3i, runtimeId: Int) {
        blockCache[position] = runtimeId
    }

    private fun updateSelectedBlock() {
        // Ищем подходящий блок в хотбаре
        selectedBlockSlot = inventory.searchForItemInHotbar { item ->
            item.isBlock() && item.count > 0
        }
        // Если блок найден, выбираем его в хотбаре
        selectedBlockSlot?.let { slot ->
            if (inventory.heldItemSlot != slot) {
                val hotbarPacket = PlayerHotbarPacket().apply {
                    selectedHotbarSlot = slot
                    containerId = 0
                    selectHotbarSlot = true
                }
                session.serverBound(hotbarPacket)
                inventory.heldItemSlot = slot
            }
        }
    }

    private fun handlePlayerAuthInput(packet: PlayerAuthInputPacket) {
        if (lastPlaceTick + placeDelay > packet.tick) return // Ограничение скорости установки

        val playerPos = packet.position
        val velocity = packet.delta
        val rotation = packet.rotation

        // Проверяем, находится ли игрок в воздухе и движется ли горизонтально
        val isMoving = velocity.x != 0f || velocity.z != 0f
        val isOnGround = player.isOnGround
        if (!isMoving || isOnGround) return

        // Определяем режим (Bridging или Towering)
        val isTowering = packet.inputData.contains(PlayerAuthInputData.JUMPING) && rotation.x > 45.0f
        val targetPos = calculateTargetPosition(playerPos, velocity, isTowering)

        // Проверяем, пуст ли целевой блок
        if (isBlockEmpty(targetPos)) {
            placeBlock(packet, targetPos, isTowering)
            lastPlaceTick = packet.tick
        }
    }

    private fun calculateTargetPosition(playerPos: Vector3f, velocity: Vector3f, isTowering: Boolean): Vector3i {
        val predictedPos = if (!isTowering) {
            // Для Bridging: предсказываем позицию с учетом скорости
            Vector3f.from(
                playerPos.x + velocity.x * lookaheadTime,
                playerPos.y - 1.0f, // Под ногами игрока
                playerPos.z + velocity.z * lookaheadTime
            )
        } else {
            // Для Towering: ставим блок прямо под игроком
            Vector3f.from(playerPos.x, playerPos.y - 1.0f, playerPos.z)
        }

        return Vector3i.from(
            floor(predictedPos.x).toInt(),
            floor(predictedPos.y).toInt(),
            floor(predictedPos.z).toInt()
        )
    }

    private fun isBlockEmpty(pos: Vector3i): Boolean {
        val blockId = blockCache[pos] ?: world.getBlockIdAt(pos)
        return blockId == 0 // 0 = воздух
    }

    private fun placeBlock(authPacket: PlayerAuthInputPacket, targetPos: Vector3i, isTowering: Boolean) {
        selectedBlockSlot?.let { slot ->
            val item = inventory.content[slot]
            if (!item.isBlock() || item.count == 0) return

            // Определяем грань блока для клика
            val blockFace = if (isTowering) {
                1 // Верхняя грань для Towering
            } else {
                // Для Bridging выбираем грань в зависимости от направления движения
                val yaw = authPacket.rotation.y
                when {
                    yaw in 45.0f..135.0f -> 2 // North
                    yaw in 135.0f..225.0f -> 4 // West
                    yaw in 225.0f..315.0f -> 3 // South
                    else -> 5 // East
                }
            }

            // Формируем пакет для установки блока
            val transaction = ItemUseTransaction().apply {
                actionType = 2 // CLICK_BLOCK
                blockPosition = targetPos
                this.blockFace = blockFace
                hotbarSlot = slot
                itemInHand = item
                playerPosition = authPacket.position
                clickPosition = Vector3f.from(0.5f, 0.5f, 0.5f) // Центр блока
                blockDefinition = item.blockDefinition
                clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                triggerType = ItemUseTransaction.TriggerType.PLAYER_INPUT
            }

            val transactionPacket = InventoryTransactionPacket().apply {
                transactionType = InventoryTransactionType.ITEM_USE
                itemUseTransaction = transaction
            }

            // Имитация взгляда вниз
            val modifiedAuthPacket = authPacket.clone().apply {
                rotation = Vector3f.from(lookDownAngle, rotation.y, rotation.z)
            }

            // Отправляем пакеты
            session.serverBound(modifiedAuthPacket)
            session.serverBound(transactionPacket)

            // Обновляем инвентарь локально
            if (item.count == 1) {
                inventory.content[slot] = ItemData.AIR
            } else {
                inventory.content[slot] = item.toBuilder().count(item.count - 1).build()
            }
            inventory.notifySlotUpdate(session, slot)
        }
    }

    private fun handleInventoryTransaction(packet: InventoryTransactionPacket) {
        // Проверяем ответ сервера на нашу транзакцию
        if (packet.transactionType == InventoryTransactionType.ITEM_USE &&
            packet.itemUseTransaction?.clientInteractPrediction == ItemUseTransaction.PredictedResult.FAILURE
        ) {
            // Откатываем изменения в инвентаре
            selectedBlockSlot?.let { slot ->
                val item = inventory.content[slot]
                if (item == ItemData.AIR) {
                    inventory.content[slot] = item.toBuilder().count(1).build()
                } else {
                    inventory.content[slot] = item.toBuilder().count(item.count + 1).build()
                }
                inventory.notifySlotUpdate(session, slot)
            }
            // Удаляем блок из кэша, если он не был установлен
            packet.itemUseTransaction?.blockPosition?.let { pos ->
                blockCache.remove(pos)
            }
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        blockCache.clear()
        selectedBlockSlot = null
    }
}
