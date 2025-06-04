package com.project.lumina.client.game.module.world

import com.project.lumina.client.R
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.LocalPlayer
import com.project.lumina.client.game.inventory.PlayerInventory
import com.project.lumina.client.game.world.World
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransaction
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.action.PlaceAction
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket
import kotlin.math.floor
import kotlin.math.roundToInt

class ScaffoldElement : Element(
    name = "Scaffold Test",
    category = CheatCategory.World,
    displayNameResId = R.string.module_scaffold_display_name,
    iconResId = R.drawable.ic_cube_outline_black_24dp
) {

    private var placeDelay = intValue("Place Delay", 100, 50..500)
    private var placeRange = floatValue("Place Range", 4.0f, 1.0f..6.0f)
    private var lastPlaceTime = 0L

    private val player: LocalPlayer
        get() = session.localPlayer

    private val inventory: PlayerInventory
        get() = player.inventory as PlayerInventory

    private val world: World
        get() = session.world

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        if (packet is PlayerAuthInputPacket) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastPlaceTime < placeDelay) return

            val blockPos = findValidBlockPlacementPosition() ?: return

            val blockSlot = findBlockInHotbar() ?: return

            if (player.heldItemSlot != blockSlot) {
                switchToSlot(blockSlot)
            }

            placeBlock(blockPos, blockSlot)
            lastPlaceTime = currentTime
        }
    }

    private fun findValidBlockPlacementPosition(): Vector3i? {
        val pos = player.vec3Position
        val baseX = floor(pos.x).toInt()
        val baseY = floor(pos.y).toInt() - 1
        val baseZ = floor(pos.z).toInt()

        // Check blocks around the player within placeRange for empty space to place block
        for (dx in -placeRange.toInt()..placeRange.toInt()) {
            for (dy in -1..1) {
                for (dz in -placeRange.toInt()..placeRange.toInt()) {
                    val checkPos = Vector3i.from(baseX + dx, baseY + dy, baseZ + dz)
                    val blockId = world.getBlockIdAt(checkPos)
                    if (blockId == 0) { // empty space
                        // Check if block below is solid to place on
                        val belowPos = Vector3i.from(checkPos.x, checkPos.y - 1, checkPos.z)
                        val belowBlockId = world.getBlockIdAt(belowPos)
                        if (belowBlockId != 0) {
                            return checkPos
                        }
                    }
                }
            }
        }
        return null
    }

    private fun findBlockInHotbar(): Int? {
        return inventory.searchForItemInHotbar { item ->
            item.isBlock()
        }
    }

    private fun switchToSlot(slot: Int) {
        val packet = PlayerHotbarPacket().apply {
            selectedHotbarSlot = slot
        }
        session.serverBound(packet)
    }

    private fun placeBlock(blockPos: Vector3i, hotbarSlot: Int) {
        val placeAction = PlaceAction(
            hotbarSlot,
            InventorySource.hotbar(),
            blockPos,
            0.5f, 0.5f, 0.5f, // click position in block space (center)
            1 // face (top)
        )
        val transaction = InventoryTransaction(
            InventoryTransactionType.ITEM_USE,
            listOf(placeAction)
        )
        val packet = InventoryTransactionPacket().apply {
            transactions.add(transaction)
        }
        session.serverBound(packet)
    }

    private fun PlayerInventory.ItemData.isBlock(): Boolean {
        val id = this.identifier.lowercase()
        return id.contains("block") || id.contains("stone") || id.contains("dirt")
    }
}
