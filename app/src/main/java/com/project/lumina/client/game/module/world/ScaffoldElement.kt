package com.project.lumina.client.game.module.world

import com.project.lumina.client.R
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.game.InterceptablePacket

// Импорты для Bedrock Protocol
import org.cloudburstmc.math.vector.Vector2f
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction // <--- ДОБАВЛЕННЫЙ ВАЖНЫЙ ИМПОРТ

import kotlin.math.floor
import android.util.Log // <--- ДОБАВЛЕННЫЙ ВАЖНЫЙ ИМПОРТ ДЛЯ ЛОГОВ

class ScaffoldElement(iconResId: Int = R.drawable.ic_cube_outline_black_24dp) : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId = iconResId,
    displayNameResId = R.string.module_scaffold_display_name
) {

    private var lastPlaceTime: Long = 0
    private val placeDelay: Long = 100 // 100ms между установками

    // Константа для тега логирования
    private val TAG = "LuminaScaffold"

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet

        if (packet is PlayerAuthInputPacket) {
            val playerPos = packet.position ?: return
            val motion = packet.motion ?: Vector2f.ZERO
            val headYaw = packet.rotation?.y ?: 0f
            val headPitch = packet.rotation?.x ?: 0f
            val inputData = packet.inputData

            // Логируем основные параметры игрока
            Log.d(TAG, "PlayerAuthInputPacket received. Pos=${playerPos.x}, ${playerPos.y}, ${playerPos.z}, Yaw=$headYaw, Pitch=$headPitch")

            val predictedX = playerPos.x + motion.x
            val predictedY = playerPos.y // Для Scaffold обычно используем текущий Y или Y-0.001
            val predictedZ = playerPos.z + motion.y // motion.y здесь относится к Z в Bedrock (YZ plane for horizontal motion)

            val isMovingForward = inputData.contains(PlayerAuthInputData.UP) || (motion.x != 0f || motion.y != 0f)
            val isJumping = inputData.contains(PlayerAuthInputData.JUMPING)

            var targetInfo: Pair<Vector3i, Int>? = null

            // Логика определения режима (Towering/Bridging)
            if (isJumping && headPitch < -60) {
                targetInfo = calculateToweringTarget(predictedX, predictedY, predictedZ)
                Log.d(TAG, "Mode: Towering. Calculated target: $targetInfo")
            } else if (isMovingForward) {
                targetInfo = calculateBridgingTarget(predictedX, predictedY, predictedZ, headYaw)
                Log.d(TAG, "Mode: Bridging. Calculated target: $targetInfo")
            } else {
                Log.d(TAG, "Mode: Idle/No specific Scaffold action.")
            }

            if (targetInfo != null) {
                val (blockPos, blockFace) = targetInfo
                Log.d(TAG, "Target Block Position: $blockPos, Face: $blockFace")

                // Проверяем, является ли целевой блок воздухом
                if (isAir(blockPos)) {
                    Log.d(TAG, "Block at $blockPos is confirmed as Air. Looking for block in hotbar.")
                    val blockSlot = findBlockInHotbar()
                    if (blockSlot != -1) {
                        Log.d(TAG, "Block found in hotbar at slot: $blockSlot.")
                        val now = System.currentTimeMillis()
                        if (now - lastPlaceTime > placeDelay) {
                            Log.d(TAG, "Place delay met (${now - lastPlaceTime}ms). Attempting to place block at $blockPos.")
                            placeBlock(blockPos, blockFace, blockSlot, playerPos, headYaw, headPitch)
                            lastPlaceTime = now
                        } else {
                            Log.d(TAG, "Place delay not met. Remaining: ${placeDelay - (now - lastPlaceTime)}ms.")
                        }
                    } else {
                        Log.w(TAG, "No suitable block (blockRuntimeId != 0) found in hotbar.")
                    }
                } else {
                    Log.i(TAG, "Block at $blockPos is NOT Air (Block ID is not 0). Skipping placement.")
                }
            }
        }
    }

    private fun calculateToweringTarget(x: Float, y: Float, z: Float): Pair<Vector3i, Int>? {
        // Ставим блок на y-1.0 (под ноги)
        val targetBlockY = floor(y - 1.0).toInt()
        val blockPos = Vector3i.from(floor(x).toInt(), targetBlockY, floor(z).toInt())

        // Дополнительная проверка: убедиться, что под нами что-то есть (или место для установки первого блока)
        // Если блок, на который мы хотим поставить, уже занят, или если под ним нет опоры
        if (!isAir(blockPos)) { // Если место, куда хотим поставить, не воздух - не можем ставить
            Log.d(TAG, "Towering target $blockPos is not air.")
            return null
        }
        val groundBlockPos = Vector3i.from(blockPos.x, blockPos.y - 1, blockPos.z)
        if (isAir(groundBlockPos)) { // Если под целевым блоком тоже воздух, то не можем поставить (пока нету Fly)
            Log.d(TAG, "Towering: Ground block $groundBlockPos is air. Cannot place.")
            return null
        }
        
        // Грань для установки: TOP_FACE (1) для блока под нами
        return Pair(blockPos, 1)
    }

    private fun calculateBridgingTarget(x: Float, y: Float, z: Float, yaw: Float): Pair<Vector3i, Int>? {
        val normalizedYaw = ((yaw % 360) + 360) % 360 // Нормализуем Yaw к 0-360
        var directionX = 0f
        var directionZ = 0f

        // Определяем направление движения относительно игрока (по Yaw)
        // Bedrock Edition: Z+ = South (0 deg), X- = West (90 deg), Z- = North (180 deg), X+ = East (270 deg)
        // Округляем до ближайших 45 градусов для определения оси
        when {
            normalizedYaw >= 315 || normalizedYaw < 45 -> directionZ = 1f // Юг (Z+)
            normalizedYaw >= 45 && normalizedYaw < 135 -> directionX = -1f // Запад (X-)
            normalizedYaw >= 135 && normalizedYaw < 225 -> directionZ = -1f // Север (Z-)
            normalizedYaw >= 225 && normalizedYaw < 315 -> directionX = 1f // Восток (X+)
        }

        // Целевая позиция блока перед игроком и под ним
        val targetBlockX = floor(x + directionX).toInt()
        val targetBlockY = floor(y - 1.0).toInt()
        val targetBlockZ = floor(z + directionZ).toInt()
        val blockPos = Vector3i.from(targetBlockX, targetBlockY, targetBlockZ)

        if (!isAir(blockPos)) { // Если место, куда хотим поставить, не воздух - не можем ставить
            Log.d(TAG, "Bridging target $blockPos is not air.")
            return null
        }
        val groundBlockPos = Vector3i.from(blockPos.x, blockPos.y - 1, blockPos.z)
        if (isAir(groundBlockPos)) { // Если под целевым блоком тоже воздух, то не можем поставить
            Log.d(TAG, "Bridging: Ground block $groundBlockPos is air. Cannot place.")
            return null
        }

        // Грань для установки: TOP_FACE (1) для блока под нами
        val blockFace = 1
        return Pair(blockPos, blockFace)
    }

    /**
     * Используем session.world.getBlockIdAt.
     * ЭТА ФУНКЦИЯ КРИТИЧЕСКИ ЗАВИСИТ ОТ ТОГО, НА СКОЛЬКО ТОЧНО session.world
     * ОТСЛЕЖИВАЕТ СОСТОЯНИЕ МИРА СЕРВЕРА (через LevelChunkPacket, UpdateBlockPacket и т.д.)
     */
    private fun isAir(position: Vector3i): Boolean {
        return try {
            val blockId = session.world.getBlockIdAt(position)
            val result = blockId == 0 // В Bedrock Protocol 0 обычно означает воздух
            Log.d(TAG, "isAir($position): Block ID = $blockId, Is Air = $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error checking isAir for $position: ${e.message}", e)
            // Если мир ещё не загружен или есть ошибка в доступе к данным мира,
            // логично предположить, что там воздух, чтобы попытаться поставить.
            // Но это также может привести к попыткам ставить блоки в занятые места.
            true
        }
    }

    /**
     * Используем session.localPlayer.inventory для поиска блока в хотбаре.
     * Убедитесь, что session.localPlayer.inventory корректно отслеживает инвентарь игрока.
     */
    private fun findBlockInHotbar(): Int {
        val slot = try {
            // Ищем предмет, который не является воздухом и имеет blockRuntimeId (т.е. является блоком)
            session.localPlayer.inventory.searchForItemInHotbar { itemData ->
                itemData != null && !itemData.isNull() && itemData.blockRuntimeId != 0
            } ?: -1 // Если не найден, возвращаем -1
        } catch (e: Exception) {
            Log.e(TAG, "Error finding block in hotbar: ${e.message}", e)
            -1 // Возвращаем -1 в случае ошибки
        }
        Log.d(TAG, "findBlockInHotbar result: $slot")
        return slot
    }

    /**
     * Отправляет InventoryTransactionPacket типа ITEM_USE для размещения блока.
     */
    private fun placeBlock(
        blockPos: Vector3i, // Позиция блока, куда ставим
        blockFace: Int,     // Грань, на которую кликаем
        hotbarSlot: Int,    // Слот в хотбаре
        playerPos: Vector3f, // Позиция игрока (ноги)
        headYaw: Float,
        headPitch: Float
    ) {
        val itemInHand: ItemData = try {
            session.localPlayer.inventory.content[hotbarSlot] as ItemData
        } catch (e: Exception) {
            Log.e(TAG, "Error getting item from hotbar slot $hotbarSlot for placement: ${e.message}", e)
            ItemData.AIR
        }

        // Проверяем, что предмет в руке - это действительно блок
        if (itemInHand == ItemData.AIR || itemInHand.blockRuntimeId == 0) {
            Log.w(TAG, "Attempted to place non-block or AIR item. Aborting block placement.")
            return
        }

        // Вычисляем clickPosition (точку на грани блока, куда происходит "клик")
        val clickPosition = calculateClickPosition(blockPos, blockFace)
        Log.d(TAG, "Preparing to place block: Target=$blockPos, Face=$blockFace, Item=${itemInHand.id}:${itemInHand.damage}, ClickPos=$clickPosition")

        // Создаем объект ItemUseTransaction
        val itemUseTransaction = ItemUseTransaction().apply {
            this.blockPosition = blockPos
            this.blockFace = blockFace
            this.hotbarSlot = hotbarSlot
            this.itemInHand = itemInHand
            this.playerPosition = playerPos
            this.clickPosition = clickPosition
            this.clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS // Предполагаем успешное взаимодействие
            this.triggerType = ItemUseTransaction.TriggerType.PLAYER_INPUT // Действие от игрока

            // faceDirection часто является дубликатом blockFace, но может быть необходим для некоторых версий протокола
            this.faceDirection = blockFace
        }

        // Создаем главный пакет InventoryTransactionPacket
        val transactionPacket = InventoryTransactionPacket().apply {
            this.transactionType = InventoryTransactionType.ITEM_USE // Указываем тип транзакции
            this.transactionData = itemUseTransaction // <--- КРИТИЧЕСКОЕ ИЗМЕНЕНИЕ: присваиваем ItemUseTransaction
            // Важно: для ITEM_USE список actions обычно должен быть пустым.
            // Если вы добавляли actions.add(...) ранее, убедитесь, что это удалено или очищено.
            this.actions.clear() // Убеждаемся, что список действий пуст
        }

        // Отправляем пакет
        session.serverBound(transactionPacket)
        Log.d(TAG, "InventoryTransactionPacket (ITEM_USE) sent for block $blockPos.")
    }

    // Эта функция рассчитывает точку "клика" на грани блока
    private fun calculateClickPosition(blockPos: Vector3i, blockFace: Int): Vector3f {
        val x = blockPos.x.toFloat()
        val y = blockPos.y.toFloat()
        val z = blockPos.z.toFloat()

        // Эти значения - относительные координаты на грани блока (0.0-1.0)
        // Для центра грани обычно 0.5f
        return when (blockFace) {
            // Bedrock Protocol Block Face (0-5):
            // 0: Bottom (Y-)
            // 1: Top (Y+)
            // 2: North (Z-)
            // 3: South (Z+)
            // 4: West (X-)
            // 5: East (X+)
            0 -> Vector3f.from(x + 0.5f, y, z + 0.5f) // Bottom face
            1 -> Vector3f.from(x + 0.5f, y + 1.0f, z + 0.5f) // Top face
            2 -> Vector3f.from(x + 0.5f, y + 0.5f, z) // North face
            3 -> Vector3f.from(x + 0.5f, y + 0.5f, z + 1.0f) // South face
            4 -> Vector3f.from(x, y + 0.5f, z + 0.5f) // West face
            5 -> Vector3f.from(x + 1.0f, y + 0.5f, z + 0.5f) // East face
            else -> Vector3f.from(x + 0.5f, y + 0.5f, z + 0.5f) // Default to center of block
        }
    }
}
