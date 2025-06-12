package com.project.lumina.client.game.module.world

import com.project.lumina.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.NetBound
import com.project.lumina.client.constructors.SliderValue
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket
import kotlin.math.floor
import kotlin.math.roundToInt

class ScaffoldElement : Element(
    "Scaffold",
    CheatCategory.World,
    R.drawable.ic_cube_outline_black_24dp,
    displayNameResId = R.string.scaffold_display_name
) {

    private var lastPlaceTick = 0L
    private val delay by SliderValue("Delay", 50, 0, 200, 1)

    override fun onEnabled() {
        super.onEnabled()
        lastPlaceTick = 0L
    }

    override fun onPacketBound(packet: BedrockPacket) {
        if (!isEnabled || !::session.isInitialized) return
        val player = session.localPlayer
        val world = session.world

        if (player.inventoriesServerAuthoritative || player.movementServerAuthoritative) {
            return
        }

        if (packet is PlayerAuthInputPacket) {
            val playerPos = packet.position
            val playerMotion = packet.motion
            val playerRotation = packet.rotation
            val tick = packet.tick

            val targetBlockPos = Vector3i(
                floor(playerPos.x).roundToInt(),
                floor(playerPos.y - 1).roundToInt(),
                floor(playerPos.z).roundToInt()
            )

            val blockUnderPlayer = world.getBlockId(targetBlockPos.x, targetBlockPos.y, targetBlockPos.z)

            if (blockUnderPlayer == 0 && (playerMotion.x != 0f || playerMotion.y != 0f || playerMotion.z != 0f)) {
                if (tick - lastPlaceTick >= delay) {
                    val blockToPlace = player.inventory.searchForItemIndexed { _, item ->
                        item.definition.runtimeId != 0 && item.definition.isBlock && item.count > 0
                    }

                    if (blockToPlace != null) {
                        val itemToUse = player.inventory.content[blockToPlace]
                        val originalHotbarSlot = player.inventory.heldItemSlot

                        if (blockToPlace != originalHotbarSlot) {
                            session.localPlayer.inventory.heldItemSlot = blockToPlace
                            session.serverBound(PlayerHotbarPacket().apply {
                                selectedHotbarSlot = blockToPlace
                                containerId = ContainerId.INVENTORY
                                selectHotbarSlot = true
                            })
                        }

                        val predictedPos = Vector3f(
                            playerPos.x + playerMotion.x * 0.1f,
                            playerPos.y - 1f,
                            playerPos.z + playerMotion.z * 0.1f
                        )
                        val finalBlockPos = Vector3i(
                            floor(predictedPos.x).roundToInt(),
                            floor(predictedPos.y).roundToInt(),
                            floor(predictedPos.z).roundToInt()
                        )

                        val newPitch = 90f

                        val transaction = ItemUseTransaction().apply {
                            actionType = 0
                            blockPosition = finalBlockPos
                            blockFace = 1
                            hotbarSlot = player.inventory.heldItemSlot
                            itemInHand = itemToUse
                            playerPosition = playerPos
                            clickPosition = Vector3f(0.5f, 0.5f, 0.5f)
                            blockDefinition = itemToUse.definition
                        }

                        session.serverBound(InventoryTransactionPacket().apply {
                            transactionType = InventoryTransactionType.ITEM_USE
                            runtimeEntityId = player.runtimeEntityId
                            itemUseTransaction = transaction
                            actions.add(
                                InventoryActionData(
                                    InventorySource.fromContainerWindowId(ContainerId.INVENTORY),
                                    blockToPlace,
                                    itemToUse,
                                    itemToUse.toBuilder().count(itemToUse.count - 1).build()
                                )
                            )
                        })

                        world.setBlockIdAt(finalBlockPos, itemToUse.definition.runtimeId)
                        lastPlaceTick = tick

                        if (blockToPlace != originalHotbarSlot) {
                            session.localPlayer.inventory.heldItemSlot = originalHotbarSlot
                            session.serverBound(PlayerHotbarPacket().apply {
                                selectedHotbarSlot = originalHotbarSlot
                                containerId = ContainerId.INVENTORY
                                selectHotbarSlot = true
                            })
                        }

                        packet.rotation = Vector3f(newPitch, playerRotation.y, playerRotation.z)
                    }
                }
            }
        }
    }
}
