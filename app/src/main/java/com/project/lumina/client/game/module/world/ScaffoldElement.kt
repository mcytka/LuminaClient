package com.project.lumina.client.game.module.world

import com.project.lumina.client.R
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.game.InterceptablePacket
import org.cloudburstmc.math.vector.Vector2f
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import kotlin.math.floor

class ScaffoldElement(iconResId: Int = R.drawable.ic_cube_outline_black_24dp) : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId = iconResId,
    displayNameResId = R.string.module_scaffold_display_name
) {

    private var lastPlaceTime: Long = 0
    private val placeDelay: Long = 100 // 100ms между установками, можно настроить позже
    // Согласно файлу, 0=bottom, 1=top, 2=north, 3=south, 4=west, 5=east

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet

        if (packet is PlayerAuthInputPacket) {
            val playerPos = packet.position ?: return
            val motion = packet.motion ?: Vector2f.ZERO
            val headYaw = packet.headYaw ?: 0f
            val headPitch = packet.headPitch ?: 0f
            val inputData = packet.inputData

            // Предиктивная позиция: текущая + движение
            val predictedX = playerPos.x + motion.x
            val predictedY = playerPos.y
            val predictedZ = playerPos.z + motion.y

            // Определяем, пытается ли игрок поставить блок (для Towering/Bridging)
            // В файле упоминается важность клика, но в Bedrock это скорее "желание поставить"
            // Проверим, есть ли движение вперёд или прыжок
            val isMovingForward = inputData.contains(PlayerAuthInputData.FORWARD) || (motion.x != 0f || motion.y != 0f)
            val isJumping = inputData.contains(PlayerAuthInputData.JUMPING)

            // Определяем режим: Bridging (вперёд) или Towering (вверх при прыжке)
            val targetInfo = if (isJumping && headPitch < -60) {
                // Towering: смотрит вниз и прыгает -> ставим блок под собой
                calculateToweringTarget(predictedX, predictedY, predictedZ)
            } else if (isMovingForward) {
                // Bridging: движется вперёд -> ставим блок перед собой вниз
                calculateBridgingTarget(predictedX, predictedY, predictedZ, headYaw)
            } else {
                null // Не нужно ставить блок
            }

            if (targetInfo != null) {
                val (blockPos, blockFace) = targetInfo

                // Проверяем, есть ли блок в этой позиции (воздух ли это)
                if (isAir(blockPos)) {
                    // Ищем блок в хотбаре
                    val blockSlot = findBlockInHotbar()
                    if (blockSlot != -1) {
                        // Проверяем задержку
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

    /**
     * Рассчитывает цель для Towering (вверх)
     * Игрок в прыжке, смотрит вниз -> ставим блок под ногами
     */
    private fun calculateToweringTarget(x: Float, y: Float, z: Float): Pair<Vector3i, Int>? {
        // Блок под ногами (y - 1)
        val blockPos = Vector3i.from(floor(x).toInt(), floor(y - 1.0).toInt(), floor(z).toInt())
        // Сторона: верх блока, который уже есть (y - 2)
        // Но по логике из файла: игрок кликает по верхней грани блока под собой
        // То есть blockPosition = (x, y-1, z), blockFace = 1 (верх блока на y-1)
        // Но сервер проверит, есть ли блок на (x, y-1, z) - это будет воздух.
        // Поэтому нужно ставить на блок, который УЖЕ есть под ногами.
        // Пересмотрим: если под ногами воздух, то ставить НЕКУДА.
        // Значит, Towering работает так: игрок прыгает, смотрит вниз, кликает по верхней грани блока НИЖЕ.
        // То есть, если игрок на высоте 70.5, то он ставит блок на 69 (если там есть основание).
        // Но если 69 пустой, то ставить некуда.
        // Следовательно, Towering возможен, если НА 2 блока ниже есть основание.
        val groundBlockPos = Vector3i.from(floor(x).toInt(), floor(y - 2.0).toInt(), floor(z).toInt())
        if (isAir(groundBlockPos)) {
            return null // Нет основания для tower'а
        }
        // Цель: ставим блок на позицию (x, y-1, z), грань 1 (верх groundBlockPos)
        return Pair(Vector3i.from(floor(x).toInt(), floor(y - 1.0).toInt(), floor(z).toInt()), 1) // 1 = TOP_FACE
    }

    /**
     * Рассчитывает цель для Bridging (вперёд)
     * Игрок движется вперёд -> ставим блок перед собой вниз
     */
    private fun calculateBridgingTarget(x: Float, y: Float, z: Float, yaw: Float): Pair<Vector3i, Int>? {
        // Определяем направление взгляда по yaw (горизонталь)
        // 0 = юг, 90 = запад, 180 = север, 270 = восток
        val normalizedYaw = ((yaw % 360) + 360) % 360
        val direction = when {
            normalizedYaw in 315.0..360.0 || normalizedYaw in 0.0..45.0 -> Vector2f.from(0.0f, 1.0f) // Юг (Z+)
            normalizedYaw in 45.0..135.0 -> Vector2f.from(-1.0f, 0.0f) // Запад (X-)
            normalizedYaw in 135.0..225.0 -> Vector2f.from(0.0f, -1.0f) // Север (Z-)
            else -> Vector2f.from(1.0f, 0.0f) // Восток (X+)
        }

        // Позиция блока перед игроком и на уровень ниже ног
        val targetX = floor(x + direction.x).toInt()
        val targetY = floor(y - 1.0).toInt()
        val targetZ = floor(z + direction.y).toInt()
        val blockPos = Vector3i.from(targetX, targetY, targetZ)

        // Определяем сторону, на которую кликаем (относительно блока, КУДА ставим)
        // Мы ставим на сторону блока, который УЖЕ есть.
        // Например, если ставим на (10, 63, 10), то кликаем по верхней грани блока (10, 62, 10).
        // Но сервер проверит, есть ли (10, 62, 10). Если нет - не поставит.
        val groundBlockPos = Vector3i.from(targetX, targetY - 1, targetZ)
        if (isAir(groundBlockPos)) {
            return null // Нет основания для моста
        }

        // Сторона: верх блока основания
        val blockFace = 1 // TOP_FACE
        return Pair(blockPos, blockFace)
    }

    /**
     * Проверяет, является ли блок воздухом
     */
    private fun isAir(position: Vector3i): Boolean {
        // Используем локальный кэш мира из файла
        return try {
            val blockId = session.level.getBlockId(position.x, position.y, position.z)
            // 0 обычно означает воздух или "нет блока"
            // TODO: Уточнить, какой ID у воздуха в конкретной маппинг-системе проекта
            blockId == 0
        } catch (e: Exception) {
            // Если не удалось получить блок, считаем, что воздух (для теста)
            true
        }
    }

    /**
     * Ищет блок в хотбаре
     */
    private fun findBlockInHotbar(): Int {
        // Используем систему инвентаря из файла
        return try {
            // Ищем любой предмет, который не является воздухом и потенциально блоком
            // TODO: Более точная проверка на "блок"
            session.localPlayer.inventory.searchForItem(0..8) { itemData ->
                itemData != null && !itemData.isNull() // && isBlockItem(itemData) - если есть такая проверка
            } ?: -1
        } catch (e: Exception) {
            // Если не удалось получить инвентарь, возвращаем слот 0 (для теста)
            0
        }
    }

    /**
     * Отправляет пакет установки блока
     */
    private fun placeBlock(
        blockPos: Vector3i,
        blockFace: Int,
        hotbarSlot: Int,
        playerPos: Vector3f,
        headYaw: Float,
        headPitch: Float
    ) {
        val itemInHand = try {
            session.localPlayer.inventory.getItem(hotbarSlot)
        } catch (e: Exception) {
            // Если не удалось получить предмет, используем "пустышку" (для теста)
            org.cloudburstmc.protocol.bedrock.data.inventory.ItemData.AIR
        }

        // Вычисляем точку клика на грани блока
        val clickPosition = calculateClickPosition(blockPos, blockFace)

        val transactionPacket = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            actions.add(
                ItemUseTransaction().apply {
                    actionType = 0 // PLACE_BLOCK (согласно файлу)
                    this.blockPosition = blockPos
                    this.blockFace = blockFace
                    this.hotbarSlot = hotbarSlot
                    this.heldItem = itemInHand
                    this.playerPosition = playerPos
                    this.clickPosition = clickPosition
                    // headPosition в файле упоминается, но в API может называться иначе или отсутствовать
                    // this.headPosition = Vector3f.from(headYaw, headPitch, 0f)
                }
            )
        }

        session.serverBound(transactionPacket)
    }

    /**
     * Вычисляет позицию клика на грани блока
     */
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
            else -> Vector3f.from(x + 0.5f, y + 0.5f, z + 0.5f) // center by default
        }
    }
}
