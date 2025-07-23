package com.project.lumina.client.game.module.world

import com.project.lumina.client.R
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.game.InterceptablePacket
// Импорты из файла TODO и стандартных библиотек
import org.cloudburstmc.math.vector.Vector2f
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
// Используем InventoryTransactionType и InventoryActionData, как указано в TODO
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData
// Источник инвентаря, как в примерах из TODO
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
// Данные предмета, как в примерах из TODO
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
// Импортируем PlayerAuthInputData, так как мы проверяем FORWARD
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import kotlin.math.floor

class ScaffoldElement(iconResId: Int = R.drawable.ic_cube_outline_black_24dp) : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId = iconResId,
    // Используем правильный идентификатор строки
    displayNameResId = R.string.module_scaffold_display_name
) {

    private var lastPlaceTime: Long = 0
    private val placeDelay: Long = 100 // 100ms между установками

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet

        // --- Исправление 1: Проверяем правильный тип пакета ---
        if (packet is PlayerAuthInputPacket) {
            // --- Исправление 2: Получаем yaw и pitch из rotation ---
            // rotation это Vector3f? (x=pitch, y=yaw, z=не используется для головы в большинстве случаев)
            val playerPos = packet.position ?: return
            val motion = packet.motion ?: Vector2f.ZERO
            // В Bedrock packet.rotation.y это yaw (горизонтальный поворот), packet.rotation.x это pitch (вертикальный)
            val headYaw = packet.rotation?.y ?: 0f
            val headPitch = packet.rotation?.x ?: 0f
            val inputData = packet.inputData

            val predictedX = playerPos.x + motion.x
            val predictedY = playerPos.y
            val predictedZ = playerPos.z + motion.y

            // --- Исправление 3: Проверяем движение через inputData или motion ---
            // FORWARD может не существовать, проверим флаги или motion
            val isMovingForward = inputData.contains(PlayerAuthInputData.UP) || // Часто используется для "вперёд"
                                  (motion.x != 0f || motion.y != 0f) // Или просто движение
            val isJumping = inputData.contains(PlayerAuthInputData.JUMPING)

            val targetInfo = if (isJumping && headPitch < -60) {
                calculateToweringTarget(predictedX, predictedY, predictedZ)
            } else if (isMovingForward) {
                calculateBridgingTarget(predictedX, predictedY, predictedZ, headYaw)
            } else {
                null
            }

            if (targetInfo != null) {
                val (blockPos, blockFace) = targetInfo

                if (isAir(blockPos)) {
                    val blockSlot = findBlockInHotbar()
                    if (blockSlot != -1) {
                        val now = System.currentTimeMillis()
                        if (now - lastPlaceTime > placeDelay) {
                            placeBlock(blockPos, blockFace, blockSlot, playerPos, headYaw, headPitch)
                            lastPlaceTime = now
                        }
                    }
                }
            }
        }
    }

    private fun calculateToweringTarget(x: Float, y: Float, z: Float): Pair<Vector3i, Int>? {
        val blockPos = Vector3i.from(floor(x).toInt(), floor(y - 1.0).toInt(), floor(z).toInt())
        val groundBlockPos = Vector3i.from(floor(x).toInt(), floor(y - 2.0).toInt(), floor(z).toInt())
        if (isAir(groundBlockPos)) {
            return null
        }
        return Pair(blockPos, 1) // 1 = TOP_FACE
    }

    private fun calculateBridgingTarget(x: Float, y: Float, z: Float, yaw: Float): Pair<Vector3i, Int>? {
        val normalizedYaw = ((yaw % 360) + 360) % 360
        val direction = when {
            // --- Исправление: Уточнение направлений ---
            // 0 = юг (Z+), 90 = запад (X-), 180 = север (Z-), 270 = восток (X+)
            // Диапазоны скорректированы для более точного соответствия
            normalizedYaw in 315.0..360.0 || normalizedYaw in 0.0..45.0 -> Vector2f.from(0.0f, 1.0f) // Юг
            normalizedYaw in 45.0..135.0 -> Vector2f.from(-1.0f, 0.0f) // Запад
            normalizedYaw in 135.0..225.0 -> Vector2f.from(0.0f, -1.0f) // Север
            else -> Vector2f.from(1.0f, 0.0f) // Восток
        }

        val targetX = floor(x + direction.x).toInt()
        val targetY = floor(y - 1.0).toInt()
        val targetZ = floor(z + direction.y).toInt()
        val blockPos = Vector3i.from(targetX, targetY, targetZ)

        val groundBlockPos = Vector3i.from(targetX, targetY - 1, targetZ)
        if (isAir(groundBlockPos)) {
            return null
        }

        val blockFace = 1 // TOP_FACE для Bridging
        return Pair(blockPos, blockFace)
    }

    /**
     * --- Исправление 4: Используем session.level.getBlockId (из TODO: Level.getBlockId) ---
     * Предполагаем, что session.level имеет метод getBlockId(x, y, z) или аналог.
     * Из файла TODO: fun getBlockId(x: Int, y: Int, z: Int): Int
     */
    private fun isAir(position: Vector3i): Boolean {
        return try {
            // --- Исправление 4: Проверяем правильный вызов ---
            val blockId = session.level.getBlockIdAt(position)
            // 0 часто означает воздух. Уточнить по маппингу проекта.
            blockId == 0
        } catch (e: Exception) {
            // Если мир ещё не загружен или ошибка, считаем воздухом (для теста)
            true
        }
    }

    /**
     * --- Исправление 5: Используем session.localPlayer.inventory (из TODO) ---
     * TODO указывает на searchForItem и getItem.
     */
    private fun findBlockInHotbar(): Int {
        return try {
            // Ищем любой предмет в слотах 0-8 (хотбар), который не воздух
            session.localPlayer.inventory.searchForItem(0..8) { itemData ->
                itemData != null && !itemData.isNull() // && isBlockItem(itemData) - позже
            } ?: -1
        } catch (e: Exception) {
            // Если инвентарь недоступен, возвращаем слот 0 (для теста)
            0
        }
    }

    /**
     * --- Исправление 6 & 7: Используем InventoryActionData и правильную структуру пакета ---
     * Из TODO: ActionType: CLICK_BLOCK -> Это часть InventoryActionData
     * InventoryTransactionPacket.actions.add(InventoryActionData(...))
     */
    private fun placeBlock(
        blockPos: Vector3i,
        blockFace: Int,
        hotbarSlot: Int,
        playerPos: Vector3f,
        headYaw: Float,
        headPitch: Float
    ) {
        // --- Исправление 5: Получаем предмет из инвентаря ---
        val itemInHand = try {
            val itemInHand = session.localPlayer.inventory.content[hotbarSlot]
        } catch (e: Exception) {
            ItemData.AIR
        }

        val clickPosition = calculateClickPosition(blockPos, blockFace)

        // --- Исправление 6: Создаем правильный пакет ---
        val transactionPacket = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE

            // Создаем InventoryActionData, как в примерах из TODO
            val action = InventoryActionData(
                source = InventorySource.fromContainerWindowId(ContainerId.UI),
                slot = 0,
                fromItem = itemInHand,
                toItem = ItemData.AIR,
                stackNetworkId = 0 // <-- Добавили пятый обязательный параметр
            )
            actions.add(action)
            // --- Поля пакета ITEM_USE ---
            // blockPosition, blockFace и т.д. устанавливаются прямо в пакете
            this.blockPosition = blockPos
            this.blockFace = blockFace
            this.hotbarSlot = hotbarSlot
            this.playerPosition = playerPos
            this.clickPosition = clickPosition
            // itemInHand (heldItem) также устанавливается в пакете
            this.itemInHand = itemInHand
            // headPosition может быть опционален или называться иначе
            // this.headPosition = Vector3f.from(headYaw, headPitch, 0f)
        }

        // --- Отправляем пакет ---
        session.serverBound(transactionPacket)
    }

    private fun calculateClickPosition(blockPos: Vector3i, blockFace: Int): Vector3f {
        val x = blockPos.x.toFloat()
        val y = blockPos.y.toFloat()
        val z = blockPos.z.toFloat()

        return when (blockFace) {
            0 -> Vector3f.from(x + 0.5f, y, z + 0.5f) // bottom
            1 -> Vector3f.from(x + 0.5f, y + 1.0f, z + 0.5f) // top
            2 -> Vector3f.from(x + 0.5f, y + 0.5f, z) // north
            3 -> Vector3f.from(x + 0.5f, y + 0.5f, z + 1.0f) // south
            4 -> Vector3f.from(x, y + 0.5f, z + 0.5f) // west
            5 -> Vector3f.from(x + 1.0f, y + 0.5f, z + 0.5f) // east
            else -> Vector3f.from(x + 0.5f, y + 0.5f, z + 0.5f) // center
        }
    }
}
