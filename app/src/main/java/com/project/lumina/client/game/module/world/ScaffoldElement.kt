package com.project.lumina.client.game.module.world // Правильный пакет

import android.util.Log
import com.project.lumina.client.R
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.LocalPlayer
import com.project.lumina.client.game.inventory.PlayerInventory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket
import kotlin.math.floor

class ScaffoldElement : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId = R.drawable.ic_cube_outline_black_24dp,
    defaultEnabled = false,
    displayNameResId = null
) {
    // Конфигурационные параметры
    private val placeDelay by intValue("PlaceDelay", 2, 0..10)
    private val lookDownAngle by floatValue("LookDownAngle", 75.0f, 0.0f..90.0f)
    private val lookaheadTime by floatValue("LookaheadTime", 0.1f, 0.0f..0.5f)

    // Внутреннее состояние
    private lateinit var player: LocalPlayer
    private lateinit var inventory: PlayerInventory
    private var lastPlaceTick = 0L
    private var selectedBlockSlot: Int? = null

    override fun onEnabled() {
        super.onEnabled()
        if (isSessionInitialized()) {
            player = getLocalPlayer()
            inventory = player.inventory
        }
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionInitialized()) return

        val packet = interceptablePacket.packet
        when (packet) {
            is PlayerAuthInputPacket -> handlePlayerAuthInput(packet)
            is InventoryTransactionPacket -> handleInventoryTransaction(packet)
        }
    }

    // Заменяем afterPacketBound на handleReceivedPacket из-за ошибки с переопределением
    fun handleReceivedPacket(packet: Any) {
        if (!isEnabled || !isSessionInitialized()) return

        when (packet) {
            is InventoryContentPacket -> updateInventory(packet)
            is InventorySlotPacket -> updateInventorySlot(packet)
        }
    }

    private fun updateInventory(packet: InventoryContentPacket) {
        if (packet.containerId == 0) {
            inventory.setContent(packet.contents)
            updateSelectedBlock()
        }
    }

    private fun updateInventorySlot(packet: InventorySlotPacket) {
        if (packet.containerId == 0) {
            inventory.setSlot(packet.slot, packet.item)
            updateSelectedBlock()
        }
    }

    private fun updateSelectedBlock() {
        selectedBlockSlot = inventory.searchForItemInHotbar { item ->
            item.blockRuntimeId != 0 && item.count > 0 // Проверка на блок
        }
        selectedBlockSlot?.let { slot ->
            if (inventory.getSelectedSlot() != slot) {
                val hotbarPacket = PlayerHotbarPacket().apply {
                    selectedHotbarSlot = slot
                    containerId = 0
                    selectHotbarSlot = true
                }
                sendServerBound(hotbarPacket)
                inventory.selectSlot(slot) // Предполагаем публичный метод
            }
        }
    }

    private fun handlePlayerAuthInput(packet: PlayerAuthInputPacket) {
        if (lastPlaceTick + placeDelay > packet.tick) return

        val playerPos = packet.position
        val velocity = packet.delta
        val rotation = packet.rotation

        val isMoving = velocity.x != 0f || velocity.z != 0f
        val isOnGround = player.isOnGround
        if (!isMoving || isOnGround) return

        val isTowering = packet.inputData.contains(PlayerAuthInputData.JUMPING) && rotation.x > 45.0f
        val targetPos = calculateTargetPosition(playerPos, velocity, isTowering)

        if (isBlockEmpty(targetPos)) {
            placeBlock(packet, targetPos, isTowering)
            lastPlaceTick = packet.tick
        }
    }

    private fun calculateTargetPosition(playerPos: Vector3f, velocity: Vector3f, isTowering: Boolean): Vector3i {
        val predictedPos = if (!isTowering) {
            Vector3f.from(
                playerPos.x + velocity.x * lookaheadTime,
                playerPos.y - 1.0f,
                playerPos.z + velocity.z * lookaheadTime
            )
        } else {
            Vector3f.from(playerPos.x, playerPos.y - 1.0f, playerPos.z)
        }

        return Vector3i.from(
            floor(predictedPos.x).toInt(),
            floor(predictedPos.y).toInt(),
            floor(predictedPos.z).toInt()
        )
    }

    private fun isBlockEmpty(pos: Vector3i): Boolean {
        val blockId = getWorld().getBlockRuntimeId(pos) // Предполагаем публичный метод
        return blockId == 0
    }

    private fun placeBlock(authPacket: PlayerAuthInputPacket, targetPos: Vector3i, isTowering: Boolean) {
        selectedBlockSlot?.let { slot ->
            val item = inventory.getSlot(slot)
            if (item.blockRuntimeId == 0 || item.count == 0) return

            val blockFace = if (isTowering) {
                1 // Верхняя грань
            } else {
                val yaw = authPacket.rotation.y
                when {
                    yaw in 45.0f..135.0f -> 2 // North
                    yaw in 135.0f..225.0f -> 4 // West
                    yaw in 225.0f..315.0f -> 3 // South
                    else -> 5 // East
                }
            }

            val transaction = ItemUseTransaction().apply {
                actionType = 2 // CLICK_BLOCK
                blockPosition = targetPos
                this.blockFace = blockFace
                hotbarSlot = slot
                itemInHand = item
                playerPosition = authPacket.position
                clickPosition = Vector3f.from(0.5f, 0.5f, 0.5f)
            }

            val transactionPacket = InventoryTransactionPacket().apply {
                transactionType = InventoryTransactionType.ITEM_USE
                setTransactionData(transaction)
            }

            val modifiedAuthPacket = authPacket.clone().apply {
                rotation = Vector3f.from(lookDownAngle, rotation.y, rotation.z)
            }

            sendServerBound(modifiedAuthPacket)
            sendServerBound(transactionPacket)

            inventory.decrementSlot(slot) // Предполагаем публичный метод
        }
    }

    private fun handleInventoryTransaction(packet: InventoryTransactionPacket) {
        if (packet.transactionType == InventoryTransactionType.ITEM_USE &&
            packet.getTransactionData()?.clientInteractPrediction == ItemUseTransaction.PredictedResult.FAILURE
        ) {
            selectedBlockSlot?.let { slot ->
                inventory.incrementSlot(slot) // Предполагаем публичный метод
            }
        }
    }

    // Вспомогательные методы для обхода ограничений доступа
    private fun isSessionInitialized(): Boolean {
        return try {
            getSession()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun sendServerBound(packet: Any) {
        getSession().serverBound(packet)
    }

    private fun getWorld() = getSession().world

    private fun getLocalPlayer() = getSession().localPlayer

    override fun onDisabled() {
        super.onDisabled()
        selectedBlockSlot = null
    }
}
