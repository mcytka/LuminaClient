package com.project.lumina.client.game.module.world

import android.util.Log
import com.project.lumina.client.R
import com.project.lumina.client.constructors.*
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.LocalPlayer
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket
import com.project.lumina.client.game.registry.isBlock
import com.project.lumina.client.game.registry.itemDefinition
import kotlin.math.floor
import kotlin.math.abs


class ScaffoldElement : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId = R.drawable.ic_cube_outline_black_24dp,
    defaultEnabled = false,
    displayNameResId = R.string.scaffold_module_name // Добавим это в strings.xml
) {

    // Настройки чита
    private var delay by intValue("Delay", 100, 0..500) // Задержка между установками блоков
    private var expand by intValue("Expand", 0, 0..5) // Расширение моста
    private var prediction by boolValue("Prediction", true) // Предиктивная установка
    private var swing by boolValue("Swing", true) // Имитация взмаха рукой
    private var rotate by boolValue("Rotate", true) // Имитация поворота головы
    private var blockOnly by boolValue("Block Only", false) // Использовать только блоки (не предметы)

    private var lastPlaceTime: Long = 0

    // Локальный кэш мира
    private val worldCache = mutableMapOf<Vector3i, Int>() // Position to BlockRuntimeId

    // Отслеживание инвентаря игрока
    // PlayerInventory уже отслеживает инвентарь игрока, мы будем использовать его напрямую
    // Дополнительно можно добавить более детальное кэширование, если PlayerInventory не предоставляет нужных деталей
    // Для этого примера будем полагаться на PlayerInventory

    override fun onEnabled() {
        super.onEnabled()
        resetState()
    }

    override fun onDisabled() {
        super.onDisabled()
        resetState()
    }

    private fun resetState() {
        worldCache.clear()
        lastPlaceTime = 0
    }

    override fun beforeServerBound(interceptablePacket: InterceptablePacket) {
        val packet = interceptablePacket.packet
        when (packet) {
            is PlayerAuthInputPacket -> handlePlayerAuthInput(packet)
            is InventoryTransactionPacket -> handleInventoryTransaction(packet)
            else -> {}
        }
    }

    override fun afterClientBound(packet: BedrockPacket) {
        when (packet) {
            is LevelChunkPacket -> handleLevelChunk(packet)
            is UpdateBlockPacket -> handleUpdateBlock(packet)
            is InventoryContentPacket -> handleInventoryContent(packet)
            is InventorySlotPacket -> handleInventorySlot(packet)
            else -> {}
        }
    }

    /**
     * Этап 1: Создание "всевидящего" слоя (Сбор информации)
     * Трекинг игрока: Перехватывать и постоянно анализировать PlayerAuthInputPacket для получения актуальных координат, вектора скорости и углов поворота камеры (pitch, yaw).
     */
    private fun handlePlayerAuthInput(packet: PlayerAuthInputPacket) {
        val localPlayer = session.localPlayer ?: return

        // Обновляем позицию, скорость и углы поворота игрока
        localPlayer.move(packet.position)
        localPlayer.rotate(packet.rotation.y, packet.rotation.x, packet.rotation.z) // yaw, pitch, headYaw
        // Скорость уже обновляется в LocalPlayer на основе дельты позиции, но PlayerAuthInputPacket также имеет motion
        localPlayer.motionX = packet.motion.x
        localPlayer.motionY = packet.motion.y
        localPlayer.motionZ = packet.motion.y // Предполагаем motion.y для всех осей для простоты, если нет delta explicit

        if (!isEnabled) return

        // Проверяем, можно ли установить блок
        if (System.currentTimeMillis() - lastPlaceTime < delay) {
            return
        }

        val targetPosition = calculateTargetBlockPosition(localPlayer)

        // Проверяем, свободен ли блок для установки в локальном кэше мира
        if (session.world.getBlockIdAt(targetPosition) != session.blockMapping.airId) {
            return // Блок уже занят
        }

        // Выбираем блок из инвентаря
        val blockToPlace = findBlockInInventory(localPlayer)
        if (blockToPlace == null) {
            Log.d("Scaffold", "No suitable block found in inventory.")
            return
        }

        // Устанавливаем блок
        placeBlock(localPlayer, targetPosition, blockToPlace)
        lastPlaceTime = System.currentTimeMillis()
    }

    /**
     * Трекинг инвентаря: При старте сессии парсить InventoryContentPacket для создания полной карты инвентаря.
     * Далее отслеживать InventorySlotPacket, чтобы поддерживать эту карту в актуальном состоянии.
     * Эту часть уже обрабатывает PlayerInventory, мы просто используем его.
     */
    private fun handleInventoryContent(packet: InventoryContentPacket) {
        // PlayerInventory уже обрабатывает этот пакет
        // Для Scaffold достаточно, чтобы PlayerInventory был в актуальном состоянии
    }

    private fun handleInventorySlot(packet: InventorySlotPacket) {
        // PlayerInventory уже обрабатывает этот пакет
        // Для Scaffold достаточно, чтобы PlayerInventory был в актуальном состоянии
    }

    private fun handleInventoryTransaction(packet: InventoryTransactionPacket) {
        // Отслеживание ответов сервера на InventoryTransactionPacket
        // В данном случае, если сервер отклонил транзакцию, нам нужно вернуть блок в кэш инвентаря.
        // CloudburstMC Protocol уже обрабатывает большую часть этого внутри PlayerInventory и NetBound,
        // но мы можем добавить логику для отката, если это необходимо.
        // На данном этапе, для простоты, мы будем предполагать успешную транзакцию.
    }


    /**
     * Создание локального кэша мира: Перехватывать пакеты LevelChunk и UpdateBlock, чтобы построить и поддерживать простую 3D-модель мира вокруг игрока.
     */
    private fun handleLevelChunk(packet: LevelChunkPacket) {
        // World уже обрабатывает этот пакет и обновляет свой внутренний кэш
        // Мы можем просто использовать session.world.getBlockId
    }

    private fun handleUpdateBlock(packet: UpdateBlockPacket) {
        // World уже обрабатывает этот пакет и обновляет свой внутренний кэш
        // Мы можем просто использовать session.world.getBlockId
    }


    /**
     * Этап 2: Логика принятия решений (Мозг чита)
     * Разработка триггера: "игрок движется горизонтально И под ним нет блока (он в воздухе)".
     */
    private fun shouldActivateScaffold(player: LocalPlayer): Boolean {
        // Проверяем, движется ли игрок горизонтально
        val motionXZ = Vector3f.from(player.motionX, 0f, player.motionZ).length()
        if (motionXZ < 0.01f) { // Если скорость по XZ очень мала, считаем, что игрок не движется горизонтально
            return false
        }

        // Проверяем, находится ли игрок в воздухе (под ним нет блока)
        // Вычисляем позицию блока под игроком
        val blockUnderPlayerX = floor(player.posX).toInt()
        val blockUnderPlayerY = floor(player.posY - 1).toInt()
        val blockUnderPlayerZ = floor(player.posZ).toInt()
        val blockUnderPlayerPos = Vector3i.from(blockUnderPlayerX, blockUnderPlayerY, blockUnderPlayerZ)

        return session.world.getBlockIdAt(blockUnderPlayerPos) == session.blockMapping.airId
    }

    /**
     * Базовый расчет позиции: (floor(x), floor(y - 1), floor(z)).
     * Предиктивный расчет позиции (для скорости): position + velocity * lookahead_time.
     */
    private fun calculateTargetBlockPosition(player: LocalPlayer): Vector3i {
        var targetX = floor(player.posX).toInt()
        var targetY = floor(player.posY - 1).toInt()
        var targetZ = floor(player.posZ).toInt()

        if (prediction) {
            // Простейшая предикция: добавляем вектор скорости
            // lookahead_time можно настроить, например, 0.1f - 0.5f
            val lookaheadTime = 0.2f
            targetX = floor(player.posX + player.motionX * lookaheadTime).toInt()
            targetY = floor(player.posY - 1 + player.motionY * lookaheadTime).toInt() // Для Scaffold обычно не нужно предсказывать по Y, но для общего случая полезно
            targetZ = floor(player.posZ + player.motionZ * lookaheadTime).toInt()
        }

        // Применяем расширение
        if (expand > 0) {
            val facingYaw = player.rotationYaw
            val absYaw = abs(facingYaw % 360) // Приводим к 0-360
            // Определяем направление движения для расширения
            // Север: -Z, Юг: +Z, Восток: +X, Запад: -X
            when {
                absYaw >= 315 || absYaw < 45 -> targetZ -= expand // Север
                absYaw >= 45 && absYaw < 135 -> targetX += expand // Восток
                absYaw >= 135 && absYaw < 225 -> targetZ += expand // Юг
                absYaw >= 225 && absYaw < 315 -> targetX -= expand // Запад
            }
        }

        return Vector3i.from(targetX, targetY, targetZ)
    }

    /**
     * Выбор блока: Написать функцию, которая проверяет наличие подходящих блоков в отслеживаемом инвентаре и выбирает один для использования.
     */
    private fun findBlockInInventory(player: LocalPlayer): ItemData? {
        val inventory = player.inventory

        // Ищем блок в хотбаре
        for (i in 0 until 9) {
            val item = inventory.content[i]
            if (item.isBlock() && item.count > 0) {
                if (blockOnly && item.itemDefinition.identifier.startsWith("minecraft:")) {
                    return item // Приоритет отдаем блокам из Minecraft
                } else if (!blockOnly) {
                    return item // Берем любой блок
                }
            }
        }

        // Если в хотбаре нет, ищем в основном инвентаре
        for (i in 9 until 36) { // Инвентарь от слота 9 до 35
            val item = inventory.content[i]
            if (item.isBlock() && item.count > 0) {
                if (blockOnly && item.itemDefinition.identifier.startsWith("minecraft:")) {
                    return item
                } else if (!blockOnly) {
                    return item
                }
            }
        }
        return null
    }

    /**
     * Этап 3: Имитация действий игрока (Руки чита)
     * Создание InventoryTransactionPacket:
     */
    private fun placeBlock(player: LocalPlayer, blockPosition: Vector3i, itemInHand: ItemData) {
        val inventory = player.inventory

        // Находим слот, в котором находится выбранный блок
        val itemSlot = inventory.searchForItemIndexed { i, item -> item == itemInHand }
        if (itemSlot == null) {
            Log.e("Scaffold", "Selected block not found in inventory. This should not happen.")
            return
        }

        // Если блок не в хотбаре, нужно его туда переместить.
        // Для упрощения, предположим, что мы всегда используем блок из хотбара.
        // В реальной реализации Scaffold, вы бы переключали хотбар или перемещали блок.
        // Для демо, мы будем использовать текущий heldItemSlot, если там блок, или пытаться использовать первый найденный.
        // Это упрощение может привести к проблемам, если heldItemSlot не содержит блока.
        var actualHotbarSlot = inventory.heldItemSlot
        var actualItemInHand = inventory.hand
        if (inventory.hand != itemInHand) {
            // Если выбранный блок не в руке, попробуем переключиться на него, если он в хотбаре
            val hotbarSlotFound = inventory.searchForItemInHotbar { it == itemInHand }
            if (hotbarSlotFound != null) {
                actualHotbarSlot = hotbarSlotFound
                actualItemInHand = itemInHand

                // Отправляем пакет PlayerHotbarPacket для переключения слота
                val hotbarPacket = PlayerHotbarPacket().apply {
                    selectedHotbarSlot = actualHotbarSlot
                    containerId = 0 // Инвентарь
                    selectHotbarSlot = true
                }
                session.serverBound(hotbarPacket)
                Log.d("Scaffold", "Switched to hotbar slot $actualHotbarSlot for placing block.")
            } else {
                Log.e("Scaffold", "Block to place is not in hotbar. Cannot place without switching.")
                return
            }
        }

        val transaction = ItemUseTransaction().apply {
            actionType = ItemUseTransaction.ActionType.CLICK_BLOCK.ordinal // Предполагаем 0 для CLICK_BLOCK
            blockPosition = blockPosition
            blockFace = 1 // UP (ставим на блок под ногами)
            hotbarSlot = actualHotbarSlot
            itemInHand = actualItemInHand
            playerPosition = player.vec3Position
            clickPosition = Vector3f.from(0.5f, 0.5f, 0.5f) // Центр блока
            blockDefinition = session.blockMapping.getDefinition(session.world.getBlockIdAt(blockPosition)) // Текущий блок, на который ставим
            // predictedResult и triggerType могут быть установлены для обхода анти-читов, но для базовой реализации можно опустить
            clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
            triggerType = ItemUseTransaction.TriggerType.PLAYER_INPUT
        }

        val transactionPacket = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            this.itemUseTransaction = transaction
            // Необходимо добавить actions для itemUseTransaction, описывающие изменение инвентаря
            // Это самая сложная часть, требующая точного понимания, как сервер обрабатывает изменения
            // Для упрощения, мы пока не будем детально моделировать все действия, но в реальном чите это критично.
            // При использовании ItemUseTransaction, actions могут быть пустыми, если сервер обрабатывает
            // это как "игрок использовал предмет".
            // Если же сервер требует полного описания инвентарных действий, то нужно добавить:
            // - ItemData.AIR в source.slot
            // - itemInHand в destination.slot
            actions.add(InventoryActionData(
                InventorySource.fromContainerWindowId(0), // Player Inventory
                actualHotbarSlot,
                itemInHand, // From: itemInHand before placement (full stack)
                itemInHand.toBuilder().count(itemInHand.count - 1).build() // To: itemInHand after placement (one less)
            ))
            // Дополнительно можно добавить действие для "сброса" блока, если он был взят из другого места в инвентаре
            // или если сервер ожидает более детальной транзакции.
        }

        session.serverBound(transactionPacket)
        Log.d("Scaffold", "Sent InventoryTransactionPacket for block at $blockPosition")

        // Имитация взмаха рукой
        if (swing) {
            player.swing()
        }

        // Имитация поворота головы (для обхода анти-читов)
        if (rotate) {
            // Это нужно сделать, изменив pitch в PlayerAuthInputPacket
            // PlayerAuthInputPacket не интерцептируется, поэтому нужно будет
            // либо отправить отдельный MovePlayerPacket, либо изменить
            // следующий PlayerAuthInputPacket. Для простоты, мы можем
            // временно установить pitch в localPlayer и надеяться, что
            // следующий PlayerAuthInputPacket отправит его.
            // В идеале, Scaffold должен изменять pitch в PlayerAuthInputPacket,
            // который он перехватывает и ретранслирует.
            val originalPitch = player.rotationPitch
            val targetPitch = 90f // Смотреть прямо вниз
            // Для плавности можно интерполировать
            player.rotate(player.rotationYaw, targetPitch, player.rotationYawHead) // Только pitch
        }
    }


    /**
     * Этап 4: Стабильность и обход защит (Полировка)
     * Ограничение скорости (Rate Limiting) - реализовано через "delay"
     * Имитация поворота головы - частично реализовано, требуется более глубокая интеграция с перехватом PlayerAuthInputPacket
     * Адаптация под разные режимы - пока только базовый бриджинг.
     */

    // Добавляем строковый ресурс для displayNameResId
    // В файле res/values/strings.xml добавьте:
    // <string name="scaffold_module_name">Scaffold</string>
}
