package com.project.lumina.client.game.module.world

import android.util.Log
import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.LocalPlayer
import com.project.lumina.client.game.inventory.PlayerInventory
import com.project.lumina.client.game.registry.BlockMapping
import com.project.lumina.client.game.world.World
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.floor

class ScaffoldElement(iconResId: Int = R.drawable.ic_cube_outline_black_24dp) : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId = iconResId,
    displayNameResId = R.string.module_scaffold_display_name
) {

    private var lastPlaceTime = 0L
    private val placeCooldown = 200L // ms для rate limiting
    private val lookaheadTime = 0.1f // Секунд для предиктивного расчета
    private val eyeHeight = 1.62f // Высота глаз игрока над ногами

    override fun onEnabled() {
        super.onEnabled()
        Log.d("Scaffold", "Scaffold enabled")
    }

    override fun onDisabled() {
        super.onDisabled()
        Log.d("Scaffold", "Scaffold disabled")
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        val packet = interceptablePacket.packet
        if (packet !is PlayerAuthInputPacket || !isEnabled) return

        val player = session.localPlayer
        val world = session.world
        val inventory = player.inventory as PlayerInventory
        val currentTime = System.currentTimeMillis()

        // Проверка cooldown
        if (currentTime - lastPlaceTime < placeCooldown) return
        lastPlaceTime = currentTime

        // Трекинг игрока
        val pos = packet.position
        val velocity = Vector3f(packet.motion.x, packet.motion.y, packet.motion.z)
        val pitch = packet.rotation.x
        val yaw = packet.rotation.y

        // Проверка условия активации (движение и отсутствие блока под ногами)
        val isMoving = velocity.lengthSquared() > 0.01f
        val blockBelow = world.getBlockId(
            floor(pos.x).toInt(),
            floor(pos.y - 1).toInt(),
            floor(pos.z).toInt()
        )
        if (!isMoving || blockBelow != 0) return

        // Предиктивный расчет позиции
        val targetPos = calculateTargetPosition(pos, velocity, pitch, yaw)
        val blockFace = determineBlockFace(pitch, yaw)

        // Выбор блока из инвентаря
        val blockSlot = findSuitableBlock(inventory) ?: return
        val blockItem = inventory.content[blockSlot]
        if (blockItem == ItemData.AIR) return

        // Имитация поворота головы
        val adjustedPitch = if (pitch > -45) pitch - 10 else pitch // Смотрим слегка вниз
        packet.rotation = Vector3f(adjustedPitch, yaw, 0f)

        // Формирование и отправка InventoryTransactionPacket
        val transactionPacket = createTransactionPacket(player, targetPos, blockFace, blockItem, blockSlot)
        session.serverBound(transactionPacket)

        // Обновление инвентаря
        inventory.updateItem(session, blockSlot)
        inventory.notifySlotUpdate(session, blockSlot)
    }

    private fun calculateTargetPosition(pos: Vector3f, velocity: Vector3f, pitch: Float, yaw: Float): Vector3i {
        // Базовая позиция (под ногами с учетом смещения)
        var x = floor(pos.x).toInt()
        var y = floor(pos.y - 1).toInt()
        var z = floor(pos.z).toInt()

        // Предиктивный расчет с учетом скорости
        val lookahead = velocity.mul(lookaheadTime)
        x += floor(lookahead.x).toInt()
        z += floor(lookahead.z).toInt()

        // Корректировка для Bridging/Towering
        if (pitch > 45) { // Towering (взгляд вверх)
            y += 1
        } else if (pitch < -45) { // Bridging (взгляд вниз)
            // Учитываем сторону блока относительно взгляда
            val direction = getDirectionFromYaw(yaw)
            when (direction) {
                "north" -> z -= 1
                "south" -> z += 1
                "west" -> x -= 1
                "east" -> x += 1
            }
        }

        return Vector3i.from(x, y, z)
    }

    private fun determineBlockFace(pitch: Float, yaw: Float): Int {
        return when {
            pitch > 45 -> 1 // Top face для Towering
            pitch < -45 -> {
                val direction = getDirectionFromYaw(yaw)
                when (direction) {
                    "north" -> 2 // North face
                    "south" -> 3 // South face
                    "west" -> 4 // West face
                    "east" -> 5 // East face
                    else -> 2 // Default north
                }
            }
            else -> 1 // Default top face
        }
    }

    private fun getDirectionFromYaw(yaw: Float): String {
        val angle = (yaw % 360 + 360) % 360
        return when {
            angle in 315..360 || angle in 0..45 -> "south"
            angle in 45..135 -> "west"
            angle in 135..225 -> "north"
            angle in 225..315 -> "east"
            else -> "south"
        }
    }

    private fun findSuitableBlock(inventory: PlayerInventory): Int? {
        return inventory.searchForItemIndexed { slot, item ->
            item.itemDefinition.isBlock() && item != ItemData.AIR
        }
    }

    private fun createTransactionPacket(
        player: LocalPlayer,
        targetPos: Vector3i,
        blockFace: Int,
        blockItem: ItemData,
        blockSlot: Int
    ): InventoryTransactionPacket {
        return InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            actionType = 1 // CLICK_BLOCK
            blockPosition = targetPos
            blockFace = blockFace
            hotbarSlot = blockSlot
            itemInHand = blockItem
            playerPosition = player.vec3Position.add(0f, eyeHeight, 0f) // Учитываем высоту глаз
            clickPosition = Vector3f.ZERO
            actions.add(
                InventoryActionData(
                    source = InventorySource.fromContainerWindowId(0),
                    slot = blockSlot,
                    fromItem = blockItem,
                    toItem = ItemData.AIR
                )
            )
        }
    }
}
