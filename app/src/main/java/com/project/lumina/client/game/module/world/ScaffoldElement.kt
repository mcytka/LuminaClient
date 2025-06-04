package com.project.lumina.client.game.module.world

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.InventoryActionData
import org.cloudburstmc.protocol.bedrock.data.inventory.InventorySource
import org.cloudburstmc.protocol.bedrock.data.inventory.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction

class ScaffoldElement(iconResId: Int = R.drawable.ic_sword_cross_black_24dp) : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId,
    displayNameResId = R.string.module_scaffold_display_name
) {

    private var lastPlayerPosition: Vector3f = Vector3f.ZERO
    private var lastPlayerRotation: Vector3f = Vector3f.ZERO

    private var isScaffoldActive by boolValue("Scaffold Active", false)

    private val playerInventory: MutableMap<Int, ItemData> = mutableMapOf()

    private fun findScaffoldBlock(): ItemData? {
        for ((slot, itemData) in playerInventory) {
            if (itemData.count > 0 && itemData.definition != null) {
                val blockDefinition = session.blockMapping.getBlockDefinition(itemData.definition.runtimeId)
                if (blockDefinition != null) {
                    return itemData
                }
            }
        }
        return null
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

                if (isScaffoldActive && !session.localPlayer.isOnGround) {
                    val playerFootPos = session.localPlayer.vec3Position
                    val targetBlockPos = Vector3i.from(
                        playerFootPos.x.toInt(),
                        playerFootPos.y.toInt() - 1,
                        playerFootPos.z.toInt()
                    )

                    if (session.world.getBlockId(targetBlockPos.x, targetBlockPos.y, targetBlockPos.z) == 0) {
                        val blockToPlace = findScaffoldBlock()
                        if (blockToPlace != null) {
                            placeBlock(targetBlockPos, blockToPlace)
                        }
                    }
                }
            }
            is InventoryContentPacket -> {
                playerInventory.clear()
                packet.contents.forEachIndexed { index, item ->
                    playerInventory[index] = item
                }
            }
            is InventorySlotPacket -> {
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
            itemUseTransaction = ItemUseTransaction().apply {
                actionType = ItemUseTransaction.ActionType.PLACE
                blockPosition = targetPosition
                blockFace = 1
                hotbarSlot = session.localPlayer.selectedHotbarSlot
                heldItem = session.localPlayer.handItem
                playerPosition = session.localPlayer.vec3Position
                playerRotation = session.localPlayer.vec3Rotation
                clickPosition = Vector3f.from(0.5f, 0.5f, 0.5f)
            }

            actions.add(InventoryActionData().apply {
                source = InventorySource.fromContainerId(ContainerId.INVENTORY)
                slot = session.localPlayer.selectedHotbarSlot
                oldItem = itemToPlace
                newItem = itemToPlace.toBuilder().count(itemToPlace.count - 1).build()
            })
        }
        session.serverBound(transaction)
    }
}
