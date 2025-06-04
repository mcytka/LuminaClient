package com.project.lumina.client.game.module.world

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData // Исправлено
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource // Исправлено
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType // Исправлено
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

class ScaffoldElement(iconResId: Int = R.drawable.ic_cube_outline_black_24dp) : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId,
    displayNameResId = R.string.module_scaffold_display_name
) {

    private var lastPlayerPosition: Vector3f = Vector3f.ZERO
    private var lastPlayerRotation: Vector3f = Vector3f.ZERO // pitch, yaw

    private var isScaffoldActive by boolValue("Scaffold Active", false)
    // Теперь используем правильную stringValue без параметра options
    private var preferredBlocks by stringValue("Preferred Blocks (identifiers, comma-separated)", "minecraft:planks,minecraft:wool,minecraft:stone")

    private val playerInventory: MutableMap<Int, ItemData> = mutableMapOf()

    private fun findScaffoldBlock(): ItemData? {
        val preferredBlockIdentifiers = preferredBlocks.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val inventoryIndices = playerInventory.keys.sorted()

        // First, try to find preferred blocks
        for (slotIndex in inventoryIndices) {
            val itemData = playerInventory[slotIndex] ?: continue

            if (itemData.count > 0 && itemData.definition != null) {
                val itemIdentifier = itemData.definition.identifier // e.g., "minecraft:planks"
                // Check if the item is a block by attempting to get its block definition
                val blockDefinition = session.blockMapping.getDefinition(session.blockMapping.getRuntimeByIdentifier(itemIdentifier))

                if (blockDefinition != null && blockDefinition.identifier != "minecraft:air") {
                    // It's a block and not air
                    if (preferredBlockIdentifiers.contains(itemIdentifier)) {
                        return itemData // Found a preferred block
                    }
                }
            }
        }

        // If no preferred blocks found, find any usable block
        for (slotIndex in inventoryIndices) {
            val itemData = playerInventory[slotIndex] ?: continue

            if (itemData.count > 0 && itemData.definition != null) {
                val itemIdentifier = itemData.definition.identifier
                val blockDefinition = session.blockMapping.getDefinition(session.blockMapping.getRuntimeByIdentifier(itemIdentifier))

                if (blockDefinition != null && blockDefinition.identifier != "minecraft:air") {
                    return itemData // Found any usable block
                }
            }
        }
        return null // No suitable block found
    }

    override fun onEnabled() {
        super.onEnabled()
    }

    override fun onDisabled() {
        super.onDisabled()
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        when (packet) {
            is PlayerAuthInputPacket -> {
                lastPlayerPosition = packet.position
                lastPlayerRotation = packet.rotation

                // Basic Scaffold logic: if active and player is not on ground, try to place a block
                if (isScaffoldActive && !session.localPlayer.isOnGround) {
                    val playerFootPos = session.localPlayer.vec3Position
                    // Target block position is one block below the player's feet
                    val targetBlockPos = Vector3i.from(
                        playerFootPos.x.toInt(),
                        playerFootPos.y.toInt() - 1,
                        playerFootPos.z.toInt()
                    )

                    // Check if the target block position is air using the World cache
                    if (session.world.getBlockIdAt(targetBlockPos) == session.blockMapping.airId) {
                        val blockToPlace = findScaffoldBlock()
                        if (blockToPlace != null) {
                            placeBlock(targetBlockPos, blockToPlace)
                        }
                    }
                }
            }
            is InventoryContentPacket -> {
                // Update player inventory with full content
                playerInventory.clear()
                packet.contents.forEachIndexed { index, item ->
                    playerInventory[index] = item
                }
            }
            is InventorySlotPacket -> {
                // Update specific inventory slot
                playerInventory[packet.slot] = packet.item
            }
        }
    }

    override fun afterPacketBound(packet: org.cloudburstmc.protocol.bedrock.packet.BedrockPacket) {
        if (!isEnabled) return
        super.afterPacketBound(packet)
    }

    override fun onDisconnect(reason: String) {
        super.onDisconnect(reason)
        lastPlayerPosition = Vector3f.ZERO
        lastPlayerRotation = Vector3f.ZERO
        playerInventory.clear()
    }

    private fun placeBlock(targetPosition: Vector3i, itemToPlace: ItemData) {
        val transaction = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            
            // Set fields directly on InventoryTransactionPacket for ITEM_USE
            actionType = ItemUseTransaction.ActionType.PLACE.ordinal // Correctly using ordinal for ActionType
            blockPosition = targetPosition
            blockFace = 1 // Face UP (Y-axis positive) for placing on the side of the block below
            hotbarSlot = session.localPlayer.inventory.heldItemSlot // Исправлено
            itemInHand = session.localPlayer.inventory.hand // Исправлено
            playerPosition = session.localPlayer.vec3Position
            headPosition = session.localPlayer.vec3Rotation // Maps to player's head rotation
            clickPosition = Vector3f.from(0.5f, 0.5f, 0.5f) // Typical click position on block surface

            // Set the blockDefinition for the transaction packet. Get it from the item's runtime ID.
            blockDefinition = session.blockMapping.getDefinition(itemToPlace.definition.runtimeId)

            // This action describes the change in inventory (one item consumed)
            actions.add(InventoryActionData(
                InventorySource.fromContainerWindowId(ContainerId.INVENTORY),
                session.localPlayer.inventory.heldItemSlot, // Исправлено
                itemToPlace, // fromItem: The item before placing
                itemToPlace.toBuilder().count(itemToPlace.count - 1).build() // toItem: The item after placing (one less count)
            ))
        }
        session.serverBound(transaction)
    }
}
