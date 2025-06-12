package com.project.lumina.client.game.module.world

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.NetBound
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.inventory.PlayerInventory
import com.project.lumina.client.game.registry.BlockMapping
import com.project.lumina.client.game.world.World
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import kotlin.math.floor

class ScaffoldElement : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId = R.drawable.ic_cube_outline_black_24dp,
    defaultEnabled = false,
    displayNameResId = null // TODO: Fix unresolved reference 'scaffold' later
) {
    private lateinit var world: World
    private lateinit var playerInventory: PlayerInventory
    private lateinit var blockMapping: BlockMapping
    private var lastBlockPlaceTime = 0L
    private val placeDelay = 100L
    private val lookaheadTime = 0.1f

    private val towerMode by boolValue("TowerMode", false)
    private val placeRate by intValue("PlaceRate", 100, 50..500)

    override fun onEnabled() {
        super.onEnabled()
        if (isSessionCreated) {
            world = session.world
            playerInventory = session.localPlayer.inventory
            blockMapping = session.blockMapping
        }
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (interceptablePacket.packet is PlayerAuthInputPacket) {
            beforeServerBound(interceptablePacket)
        }
    }

    override fun beforeServerBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return

        val packet = interceptablePacket.packet
        if (packet is PlayerAuthInputPacket) {
            handlePlayerInput(packet)
        }
    }

    private fun handlePlayerInput(packet: PlayerAuthInputPacket) {
        val player = session.localPlayer
        val position = packet.position
        val velocity = packet.delta
        val isMoving = velocity.x != 0f || velocity.z != 0f
        val isOnGround = player.isOnGround

        if (!shouldActivateScaffold(isMoving, isOnGround, packet)) return

        val targetPos = calculateTargetPosition(position, velocity)
        if (!canPlaceBlock(targetPos)) return

        val blockSlot = selectBlockFromInventory() ?: return
        switchToHotbarSlot(blockSlot)
        adjustHeadRotation(packet)
        placeBlock(targetPos, blockSlot, packet)
    }

    private fun shouldActivateScaffold(isMoving: Boolean, isOnGround: Boolean, packet: PlayerAuthInputPacket): Boolean {
        if (towerMode) {
            return packet.inputData.contains(PlayerAuthInputData.JUMPING) &&
                    packet.rotation.x < -45f
        } else {
            val posBelow = Vector3i.from(
                floor(session.localPlayer.posX).toInt(),
                floor(session.localPlayer.posY - 1).toInt(),
                floor(session.localPlayer.posZ).toInt()
            )
            return isMoving && world.getBlockIdAt(posBelow) == blockMapping.airId
        }
    }

    private fun calculateTargetPosition(position: Vector3f, velocity: Vector3f): Vector3i {
        val predictedX = position.x + velocity.x * lookaheadTime
        val predictedZ = position.z + velocity.z * lookaheadTime
        val predictedY = if (towerMode) {
            floor(position.y).toInt()
        } else {
            floor(position.y - 1).toInt()
        }

        return Vector3i.from(
            floor(predictedX).toInt(),
            predictedY,
            floor(predictedZ).toInt()
        )
    }

    private fun canPlaceBlock(pos: Vector3i): Boolean {
        if (world.getBlockIdAt(pos) != blockMapping.airId) return false
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBlockPlaceTime < placeRate) return false
        return true
    }

    private fun selectBlockFromInventory(): Int? {
        return playerInventory.searchForItemInHotbar { item ->
            item.blockDefinition != null && item.getCount() > 0
        }
    }

    private fun switchToHotbarSlot(slot: Int) {
        val hotbarPacket = PlayerHotbarPacket().apply {
            selectedHotbarSlot = slot
            containerId = 0
            selectHotbarSlot = true
        }
        session.serverBound(hotbarPacket)
    }

    private fun adjustHeadRotation(packet: PlayerAuthInputPacket) {
        val newPitch = 80f
        packet.rotation = Vector3f.from(newPitch, packet.rotation.y, packet.rotation.z)
    }

    private fun placeBlock(pos: Vector3i, slot: Int, packet: PlayerAuthInputPacket) {
        val itemInHand = playerInventory.content[slot]
        val blockDefinition = itemInHand.blockDefinition ?: return

        val transactionPacket = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            actionType = 0
            blockPosition = pos
            blockFace = 1
            hotbarSlot = slot
            this.itemInHand = itemInHand
            playerPosition = packet.position
            clickPosition = Vector3f.from(0.5f, 1f, 0.5f)
            this.blockDefinition = blockDefinition
        }

        session.serverBound(transactionPacket)
        lastBlockPlaceTime = System.currentTimeMillis()
        session.localPlayer.swing()
        updateInventoryAfterPlace(slot, itemInHand)
    }

    private fun updateInventoryAfterPlace(slot: Int, item: ItemData) {
        val newCount = item.getCount() - 1
        val newItem = if (newCount > 0) {
            ItemData.builder()
                .definition(item.getDefinition())
                .damage(item.getDamage())
                .count(newCount)
                .tag(item.getTag())
                .canPlace(*item.getCanPlace())
                .canBreak(*item.getCanBreak())
                .blockingTicks(item.getBlockingTicks())
                .blockDefinition(item.getBlockDefinition())
                .usingNetId(item.isUsingNetId())
                .netId(item.getNetId())
                .build()
        } else {
            ItemData.AIR
        }
        playerInventory.content[slot] = newItem

        val slotPacket = InventorySlotPacket().apply {
            containerId = 0
            this.slot = slot
            this.item = newItem
        }
        session.clientBound(slotPacket)
    }

    override fun afterClientBound(packet: org.cloudburstmc.protocol.bedrock.packet.BedrockPacket) {
        if (packet is InventoryTransactionPacket && packet.transactionType == InventoryTransactionType.ITEM_USE) {
            val slot = packet.hotbarSlot
            val item = packet.itemInHand
            if (item != null && item != ItemData.AIR && packet.actionType == 0) {
                val currentItem = playerInventory.content[slot]
                val newItem = if (currentItem == ItemData.AIR) {
                    item
                } else {
                    ItemData.builder()
                        .definition(currentItem.getDefinition())
                        .damage(currentItem.getDamage())
                        .count(currentItem.getCount() + 1)
                        .tag(currentItem.getTag())
                        .canPlace(*currentItem.getCanPlace())
                        .canBreak(*currentItem.getCanBreak())
                        .blockingTicks(currentItem.getBlockingTicks())
                        .blockDefinition(currentItem.getBlockDefinition())
                        .usingNetId(currentItem.isUsingNetId())
                        .netId(currentItem.getNetId())
                        .build()
                }
                playerInventory.content[slot] = newItem

                val slotPacket = InventorySlotPacket().apply {
                    containerId = 0
                    this.slot = slot
                    this.item = newItem
                }
                session.clientBound(slotPacket)
            }
        }
    }
}
