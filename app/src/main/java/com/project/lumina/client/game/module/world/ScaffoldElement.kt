package com.project.lumina.client.game.module.world

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.LocalPlayer
import com.project.lumina.client.game.inventory.PlayerInventory
import com.project.lumina.client.game.world.Level
import com.project.lumina.client.game.world.World
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket
import kotlin.math.floor

class ScaffoldElement : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId = R.drawable.ic_cube_outline_black_24dp,
    displayNameResId = R.string.module_scaffold_display_name
) {

    private var placeDelay by intValue("Place Delay", 100, 50..500)
    private var placeRange by floatValue("Place Range", 5.0f, 1f..10f)
    private var autoJump by boolValue("Auto Jump", true)
    private var sneakWhilePlacing by boolValue("Sneak While Placing", true)

    private var lastPlaceTime = 0L

    override fun beforePacketBound(packet: InterceptablePacket) {
        if (!isEnabled) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPlaceTime < placeDelay) return

        val player = session.localPlayer
        val world = session.level

        val blockPos = findBlockToPlace(player, world)
        if (blockPos != null) {
            val slot = findBlockInInventory(player.inventory)
            if (slot == -1) {
                // No blocks found in inventory
                return
            }
            if (player.inventory.selectedSlot != slot) {
                player.inventory.selectedSlot = slot
            }
            placeBlock(blockPos, slot)
            lastPlaceTime = currentTime
            if (autoJump && !player.isOnGround) {
                player.jump()
            }
        }
    }

    private fun findBlockToPlace(player: LocalPlayer, world: Level): Vector3f? {
        val pos = player.vec3Position
        val blockBelow = Vector3f.from(floor(pos.x), floor(pos.y) - 1, floor(pos.z))

        if (player.distance(blockBelow) > placeRange) {
            return null
        }

        // Check if blockBelow is empty by checking entities at that position
        val isBlockOccupied = world.entityMap.values.any { entity ->
            val entityPos = entity.vec3Position
            entityPos.x.toInt() == blockBelow.x.toInt() &&
            entityPos.y.toInt() == blockBelow.y.toInt() &&
            entityPos.z.toInt() == blockBelow.z.toInt()
        }

        if (isBlockOccupied) {
            return null
        }

        // Check if there is a supporting block adjacent to blockBelow
        val adjacentPositions = listOf(
            Vector3f.from(blockBelow.x + 1, blockBelow.y, blockBelow.z),
            Vector3f.from(blockBelow.x - 1, blockBelow.y, blockBelow.z),
            Vector3f.from(blockBelow.x, blockBelow.y, blockBelow.z + 1),
            Vector3f.from(blockBelow.x, blockBelow.y, blockBelow.z - 1),
            Vector3f.from(blockBelow.x, blockBelow.y - 1, blockBelow.z)
        )
        val hasSupport = adjacentPositions.any { adjPos ->
            world.entityMap.values.any { entity ->
                val entityPos = entity.vec3Position
                entityPos.x.toInt() == adjPos.x.toInt() &&
                entityPos.y.toInt() == adjPos.y.toInt() &&
                entityPos.z.toInt() == adjPos.z.toInt()
            }
        }

        return if (hasSupport) blockBelow else null
    }

    private fun findBlockInInventory(inventory: PlayerInventory): Int {
        for (i in 0 until inventory.size) {
            val item = inventory.getItem(i)
            if (item != null && item.isBlock()) {
                return i
            }
        }
        return -1
    }

    private fun placeBlock(blockPos: Vector3f, slot: Int) {
        if (sneakWhilePlacing) {
            sendSneak(true)
        }

        val itemInHand = session.localPlayer.inventory.getItem(slot)?.toItemData() ?: ItemData.AIR

        val packet = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            hotbarSlot = slot
            blockPosition = blockPos.toIntVector3()
            blockFace = 1 // Down face
            runtimeEntityId = session.localPlayer.runtimeEntityId
            itemInHand = itemInHand
            playerPosition = session.localPlayer.vec3Position
            clickPosition = session.localPlayer.vec3Position
        }
        session.clientBound(packet)

        if (sneakWhilePlacing) {
            sendSneak(false)
        }
    }

    private fun sendSneak(sneak: Boolean) {
        val action = if (sneak) PlayerActionPacket.Action.START_SNEAK else PlayerActionPacket.Action.STOP_SNEAK
        val packet = PlayerActionPacket().apply {
            this.action = action
            this.blockPosition = null
            this.face = 0
            this.runtimeEntityId = session.localPlayer.runtimeEntityId
        }
        session.clientBound(packet)
    }

    private fun Vector3f.toIntVector3() = org.cloudburstmc.math.vector.Vector3i.from(
        this.x.toInt(),
        this.y.toInt(),
        this.z.toInt()
    )
}
