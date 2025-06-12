package com.project.lumina.client.elements.world

import com.project.lumina.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.registry.isBlock
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket
import kotlin.math.floor

/**
 * Scaffold - чит для автоматической установки блоков под игроком.
 * Позволяет строить мосты и башни без риска падения.
 *
 * Реализация основана на перехвате и отправке пакетов через MITM-слой.
 */
class ScaffoldElement : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId = R.drawable.ic_cube_outline_black_24dp, // Иконка, которая точно есть в проекте
    defaultEnabled = false
) {

    // --- Настройки (Values) для UI ---
    private val tower by boolValue("Tower", true)
    private val rotate by boolValue("Rotate", true)
    private val prediction by floatValue("Prediction", 0.25f, 0f..2f)
    private val delay by intValue("Delay", 50, 0..500)

    // Переменная для ограничения скорости установки блоков
    private var lastPlacementTime = 0L

    /**
     * Основной метод обработки пакетов. Вызывается для каждого пакета.
     */
    override fun onPacket(packet: BedrockPacket, isOutgoing: Boolean): Boolean {
        // Чит работает только если включен и сессия активна
        if (!isEnabled || !isSessionCreated) {
            return true
        }

        // Мы анализируем исходящий PlayerAuthInputPacket, так как он содержит актуальные данные
        // о движении и вводе игрока, что идеально подходит для триггера.
        if (isOutgoing && packet is PlayerAuthInputPacket) {
            handleScaffoldLogic(packet)
        }

        return true // Всегда пропускаем пакет дальше
    }

    /**
     * Центральная логика Scaffold.
     */
    private fun handleScaffoldLogic(packet: PlayerAuthInputPacket) {
        // --- Этап 4: Ограничение скорости (Rate Limiting) ---
        if (System.currentTimeMillis() - lastPlacementTime < delay.value) {
            return
        }

        val player = session.localPlayer
        val world = session.world

        // --- Этап 2: Предиктивный расчет позиции ---
        // Рассчитываем позицию на опережение, используя вектор скорости из пакета.
        val predictedPos = player.vec3Position.add(packet.delta.mul(prediction.value))

        // Определяем, находится ли игрок в режиме строительства башни (Tower).
        val isJumping = packet.inputData.contains(PlayerAuthInputData.JUMPING)
        val isMovingHorizontally = packet.motion.x != 0f || packet.motion.y != 0f // motion в PAIP - это (x, z)

        val targetPos = if (tower.value && isJumping && !isMovingHorizontally) {
            // Режим Tower: строимся вверх. Целевая позиция - прямо под ногами.
            Vector3i.from(floor(player.posX).toInt(), floor(player.posY - 1).toInt(), floor(player.posZ).toInt())
        } else {
            // Режим Bridge/Normal: используем предсказанную позицию.
            Vector3i.from(floor(predictedPos.x).toInt(), floor(predictedPos.y - 1).toInt(), floor(predictedPos.z).toInt())
        }

        // --- Этап 2: Разработка триггера ---
        // Проверяем, пуст ли блок в целевой позиции. Если нет, ничего не делаем.
        val blockAtTarget = world.getBlockIdAt(targetPos)
        if (blockAtTarget != session.blockMapping.airId) {
            return
        }

        // --- Этап 2: Выбор блока ---
        val blockToPlace = findBestBlock() ?: return // В инвентаре нет подходящих блоков

        // Ищем опорный блок, на который будем "кликать" для установки нового.
        val anchor = findAnchorBlock(targetPos) ?: return // Не найдено точки опоры

        // --- Этап 3: Имитация действий игрока ---

        // Переключаем хотбар, если выбранный блок не в руке
        val inventory = player.inventory
        if (inventory.heldItemSlot != blockToPlace.slot && blockToPlace.slot in 0..8) {
            val hotbarPacket = PlayerHotbarPacket().apply {
                selectedHotbarSlot = blockToPlace.slot
                containerId = 0 // Инвентарь игрока
                selectHotbarSlot = true
            }
            session.serverBound(hotbarPacket)
            // Немедленно обновляем локальное состояние, чтобы следующий пакет был корректным
            // В `PlayerInventory` эта логика уже может быть, но для надежности дублируем.
            try {
                val field = inventory::class.java.getDeclaredField("heldItemSlot")
                field.isAccessible = true
                field.setInt(inventory, blockToPlace.slot)
            } catch (e: Exception) {
                // Обработка ошибки, если рефлексия не удалась
            }
        }

        // Убедимся, что в руке действительно тот предмет, который мы собираемся ставить
        val itemInHand = inventory.content[inventory.heldItemSlot]
        if (!itemInHand.isBlock() || isNonScaffoldBlock(itemInHand)) {
            return // После переключения слота в руке оказался не блок. Отмена.
        }

        // --- Этап 4: Имитация поворота головы ---
        if (rotate.value) {
            // Модифицируем текущий пакет PlayerAuthInputPacket, чтобы имитировать взгляд вниз.
            // Это помогает обходить некоторые анти-читы.
            packet.rotation = packet.rotation.toBuilder().x(82f).y(packet.rotation.y).z(packet.rotation.z).build()
        }

        // --- Этап 3: Создание InventoryTransactionPacket ---
        val transaction = ItemUseTransaction().apply {
            actionType = 1 // 1 = CLICK_BLOCK (Использование предмета на блоке)
            blockPosition = anchor.pos // Позиция опорного блока
            blockFace = anchor.face // Грань опорного блока, на которую кликаем
            hotbarSlot = inventory.heldItemSlot
            this.itemInHand = itemInHand
            playerPosition = player.vec3Position
            clickPosition = Vector3f.from(0.5, 0.5, 0.5) // Условная точка клика в центре грани
            blockDefinition = session.blockMapping.getDefinition(itemInHand.blockDefinition.runtimeId)
        }

        val transactionPacket = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            itemUseTransaction = transaction
            legacyRequestId = 0
            isUsingNetIds = false
        }

        // Отправляем сформированный пакет на сервер от имени клиента
        session.serverBound(transactionPacket)

        // Обновляем время последней установки
        lastPlacementTime = System.currentTimeMillis()

        // --- Превентивное обновление инвентаря ---
        // Чтобы избежать рассинхронизации, можно уменьшить количество блоков в локальном
        // представлении инвентаря, не дожидаясь ответа от сервера.
        if (itemInHand.count > 1) {
            inventory.content[inventory.heldItemSlot] = itemInHand.toBuilder().count(itemInHand.count - 1).build()
        } else {
            inventory.content[inventory.heldItemSlot] = ItemData.AIR
        }
    }

    /**
     * Ищет лучший блок для установки в инвентаре.
     * Приоритет у хотбара.
     */
    private fun findBestBlock(): BlockPlacementData? {
        val inventory = session.localPlayer.inventory
        // Сначала ищем в хотбаре (0-8), затем в остальном инвентаре (9-35)
        for (i in 0 until 36) {
            val item = inventory.content[i]
            if (item.isBlock() && !isNonScaffoldBlock(item)) {
                return BlockPlacementData(i, item)
            }
        }
        return null
    }

    /**
     * Проверяет, является ли блок "нежелательным" для строительства (сундуки, печки и т.д.).
     */
    private fun isNonScaffoldBlock(item: ItemData): Boolean {
        val identifier = item.definition.identifier
        return identifier.contains("chest") ||
                identifier.contains("shulker_box") ||
                identifier.contains("furnace") ||
                identifier.contains("crafting_table") ||
                identifier.contains("bed") ||
                identifier.contains("door") ||
                identifier.contains("gate")
    }

    /**
     * Находит опорный блок и грань для клика, чтобы разместить новый блок в `targetPos`.
     */
    private fun findAnchorBlock(targetPos: Vector3i): AnchorResult? {
        val world = session.world
        // Грани блока: 0:Y-, 1:Y+, 2:Z-, 3:Z+, 4:X-, 5:X+
        // Нам нужно найти соседний с targetPos блок, чтобы "кликнуть" по нему.

        // 1. Самый простой случай: ставим блок на блок снизу.
        val belowPos = targetPos.sub(0, 1, 0)
        if (world.getBlockIdAt(belowPos) != session.blockMapping.airId) {
            return AnchorResult(belowPos, 1) // Кликаем на верхнюю грань (1) блока под целью.
        }

        // 2. Случай моста (bridging): ищем опору по сторонам.
        val neighbors = listOf(
            targetPos.add(0, 0, 1) to 2,  // Южный сосед -> клик по северной грани (2)
            targetPos.add(0, 0, -1) to 3, // Северный сосед -> клик по южной грани (3)
            targetPos.add(1, 0, 0) to 4,   // Восточный сосед -> клик по западной грани (4)
            targetPos.add(-1, 0, 0) to 5,  // Западный сосед -> клик по восточной грани (5)
            targetPos.add(0, 1, 0) to 0 // Верхний сосед -> клик по нижней грани (0), редкий случай
        )

        for ((pos, face) in neighbors) {
            if (world.getBlockIdAt(pos) != session.blockMapping.airId) {
                return AnchorResult(pos, face)
            }
        }

        return null // Опора не найдена
    }

    // Вспомогательные классы для хранения данных
    private data class BlockPlacementData(val slot: Int, val item: ItemData)
    private data class AnchorResult(val pos: Vector3i, val face: Int)
}
