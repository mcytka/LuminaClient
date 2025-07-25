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
// import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData // Не используется напрямую в финальной версии, но может быть в других частях
// import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource // Не используется напрямую в финальной версии
// import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId // Не используется напрямую в финальной версии
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction // <--- ВАЖНЫЙ ИМПОРТ

import kotlin.math.floor
import android.util.Log // <--- ВАЖНЫЙ ИМПОРТ ДЛЯ ЛОГОВ

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
            val predictedY = playerPos.y
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
                        Log.w(TAG, "No suitable item (not null/air) found in hotbar to place.")
                    }
                } else {
                    Log.i(TAG, "Block at $blockPos is NOT Air (Block ID is not 0). Skipping placement.")
                }
            }
        }
    }

    private fun calculateToweringTarget(x: Float, y: Float, z: Float): Pair<Vector3i, Int>? {
        val targetBlockY = floor(y - 1.0).toInt()
        val blockPos = Vector3i.from(floor(x).toInt(), targetBlockY, floor(z).toInt())

        if (!isAir(blockPos)) {
            Log.d(TAG, "Towering target $blockPos is not air.")
            return null
        }
        val groundBlockPos = Vector3i.from(blockPos.x, blockPos.y - 1, blockPos.z)
        if (isAir(groundBlockPos)) {
            Log.d(TAG, "Towering: Ground block $groundBlockPos is air. Cannot place.")
            return null
        }
        
        return Pair(blockPos, 1) // 1 = TOP_FACE
    }

    private fun calculateBridgingTarget(x: Float, y: Float, z: Float, yaw: Float): Pair<Vector3i, Int>? {
        val normalizedYaw = ((yaw % 360) + 360) % 360
        var directionX = 0f
        var directionZ = 0f

        when {
            normalizedYaw >= 315 || normalizedYaw < 45 -> directionZ = 1f // South (Z+)
            normalizedYaw >= 45 && normalizedYaw < 135 -> directionX = -1f // West (X-)
            normalizedYaw >= 135 && normalizedYaw < 225 -> directionZ = -1f // North (Z-)
            normalizedYaw >= 225 && normalizedYaw < 315 -> directionX = 1f // East (X+)
        }

        val targetBlockX = floor(x + directionX).toInt()
        val targetBlockY = floor(y - 1.0).toInt()
        val targetBlockZ = floor(z + directionZ).toInt()
        val blockPos = Vector3i.from(targetBlockX, targetBlockY, targetBlockZ)

        if (!isAir(blockPos)) {
            Log.d(TAG, "Bridging target $blockPos is not air.")
            return null
        }
        val groundBlockPos = Vector3i.from(blockPos.x, blockPos.y - 1, blockPos.z)
        if (isAir(groundBlockPos)) {
            Log.d(TAG, "Bridging: Ground block $groundBlockPos is air. Cannot place.")
            return null
        }

        val blockFace = 1 // TOP_FACE
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
            true // В случае ошибки считаем воздухом, чтобы попытаться поставить. Это может быть некорректно.
        }
    }

    /**
     * Используем session.localPlayer.inventory для поиска блока в хотбаре.
     * Убедитесь, что session.localPlayer.inventory корректно отслеживает инвентарь игрока.
     */
    private fun findBlockInHotbar(): Int {
        val slot = try {
            // Теперь просто проверяем, что предмет не null и не является "воздухом".
            // Если у ItemData в вашей версии нет blockRuntimeId, это самый безопасный способ.
            session.localPlayer.inventory.searchForItemInHotbar { itemData ->
                itemData != null && !itemData.isNull()
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
        blockPos: Vector3i,
        blockFace: Int,
        hotbarSlot: Int,
        playerPos: Vector3f,
        headYaw: Float,
        headPitch: Float
    ) {
        val itemInHand: ItemData = try {
            session.localPlayer.inventory.content[hotbarSlot] as ItemData
        } catch (e: Exception) {
            Log.e(TAG, "Error getting item from hotbar slot $hotbarSlot for placement: ${e.message}", e)
            ItemData.AIR
        }

        // Проверяем, что предмет не является null или воздухом.
        // Более точная проверка на "является ли блок" здесь затруднена без blockRuntimeId.
        if (itemInHand.isNull()) { // itemInHand.isNull() уже проверяет на null и AIR
            Log.w(TAG, "Attempted to place null or AIR item. Aborting block placement.")
            return
        }

        val clickPosition = calculateClickPosition(blockPos, blockFace)
        // Использование itemInHand.id и itemInHand.damage. Если это вызывает ошибку, то вашей ItemData их не имеет.
        Log.d(TAG, "Preparing to place block: Target=$blockPos, Face=$blockFace, Item=${itemInHand.id}:${itemInHand.damage}, ClickPos=$clickPosition")

        // Создаем объект ItemUseTransaction
        val itemUseTransaction = ItemUseTransaction().apply {
            this.blockPosition = blockPos
            this.blockFace = blockFace
            this.hotbarSlot = hotbarSlot
            this.itemInHand = itemInHand
            this.playerPosition = playerPos
            this.clickPosition = clickPosition
            this.clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
            this.triggerType = ItemUseTransaction.TriggerType.PLAYER_INPUT
            // this.faceDirection = blockFace // <--- УДАЛЕНО: Вызывает Unresolved reference 'faceDirection'
        }

        // Создаем главный пакет InventoryTransactionPacket
        val transactionPacket = InventoryTransactionPacket().apply {
            this.transactionType = InventoryTransactionType.ITEM_USE
            this.transactionData = itemUseTransaction // <--- СОХРАНЕНО: Если это вызывает Unresolved reference, то у вас очень старая или сильно отличающаяся версия библиотеки.
            this.actions.clear() // Убеждаемся, что список действий пуст
        }

        session.serverBound(transactionPacket)
        Log.d(TAG, "InventoryTransactionPacket (ITEM_USE) sent for block $blockPos.")
    }

    private fun calculateClickPosition(blockPos: Vector3i, blockFace: Int): Vector3f {
        val x = blockPos.x.toFloat()
        val y = blockPos.y.toFloat()
        val z = blockPos.z.toFloat()

        return when (blockFace) {
            0 -> Vector3f.from(x + 0.5f, y, z + 0.5f) // Bottom (Y-)
            1 -> Vector3f.from(x + 0.5f, y + 1.0f, z + 0.5f) // Top (Y+)
            2 -> Vector3f.from(x + 0.5f, y + 0.5f, z) // North (Z-)
            3 -> Vector3f.from(x + 0.5f, y + 0.5f, z + 1.0f) // South (Z+)
            4 -> Vector3f.from(x, y + 0.5f, z + 0.5f) // West (X-)
            5 -> Vector3f.from(x + 1.0f, y + 0.5f, z + 0.5f) // East (X+)
            else -> Vector3f.from(x + 0.5f, y + 0.5f, z + 0.5f) // Default to center
        }
    }
}
