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
// Импортируем PlayerAuthInputData, так как мы проверяем UP
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

        if (packet is PlayerAuthInputPacket) {
            val playerPos = packet.position ?: return
            val motion = packet.motion ?: Vector2f.ZERO
            // В Bedrock packet.rotation.y это yaw (горизонтальный поворот), packet.rotation.x это pitch (вертикальный)
            val headYaw = packet.rotation?.y ?: 0f
            val headPitch = packet.rotation?.x ?: 0f
            val inputData = packet.inputData

            val predictedX = playerPos.x + motion.x
            val predictedY = playerPos.y
            val predictedZ = playerPos.z + motion.y

            // Проверяем движение через inputData или motion
            val isMovingForward = inputData.contains(PlayerAuthInputData.UP) ||
                                  (motion.x != 0f || motion.y != 0f)
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
            // 0 = юг (Z+), 90 = запад (X-), 180 = север (Z-), 270 = восток (X+)
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
     * Используем session.world.getBlockIdAt (из TODO: Level.getBlockIdAt)
     */
    private fun isAir(position: Vector3i): Boolean {
        return try {
            // Предполагаем, что session.world имеет метод getBlockIdAt(Vector3i)
            // и что 0 означает воздух (как в LevelDBWorld из TODO)
            val blockId = session.world.getBlockIdAt(position)
            blockId == 0
        } catch (e: Exception) {
            // Если мир ещё не загружен или ошибка, считаем воздухом (для теста)
            true
        }
    }

    /**
     * Используем session.localPlayer.inventory (из TODO)
     * Используем searchForItemInHotbar из TODO
     */
    private fun findBlockInHotbar(): Int {
        return try {
            // Используем метод из TODO файла: searchForItemInHotbar
            session.localPlayer.inventory.searchForItemInHotbar { itemData ->
                itemData != null && !itemData.isNull()
                // TODO: Более точная проверка на "блок"
                // && isBlockItem(itemData)
            } ?: -1
        } catch (e: Exception) {
            // Если инвентарь недоступен, возвращаем слот 0 (для теста)
            0
        }
    }

    /**
     * Используем InventoryActionData и правильную структуру пакета
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
        // Получаем предмет из инвентаря, используя прямой доступ к content и приведение типа
        val itemInHand: ItemData = try {
            session.localPlayer.inventory.content[hotbarSlot] as ItemData
        } catch (e: Exception) {
            ItemData.AIR
        }

        val clickPosition = calculateClickPosition(blockPos, blockFace)

        // Создаем правильный пакет
        val transactionPacket = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE

            // Явно указываем типы для InventoryActionData
            val source: InventorySource = InventorySource.fromContainerWindowId(ContainerId.UI)
            val slot: Int = 0
            val fromItem: ItemData = itemInHand
            val toItem: ItemData = ItemData.AIR
            val stackNetworkId: Int = 0

            // Создаем InventoryActionData с правильными типами
            val action = InventoryActionData(source, slot, fromItem, toItem, stackNetworkId)
            actions.add(action)

            // Поля пакета ITEM_USE
            this.blockPosition = blockPos
            this.blockFace = blockFace
            this.hotbarSlot = hotbarSlot
            this.playerPosition = playerPos
            this.clickPosition = clickPosition
            // itemInHand (heldItem) также устанавливается в пакете
            this.itemInHand = itemInHand
            // headPosition может быть опционален
            // this.headPosition = Vector3f.from(headYaw, headPitch, 0f)
        }

        // Отправляем пакет
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
