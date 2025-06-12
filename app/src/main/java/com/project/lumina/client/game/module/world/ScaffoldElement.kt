package com.project.lumina.client.game.module.world

import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.NetBound
import com.project.lumina.client.game.entity.LocalPlayer
import com.project.lumina.client.game.inventory.PlayerInventory
import com.project.lumina.client.game.world.World
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.sqrt

class ScaffoldElement : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId = com.project.lumina.client.R.drawable.ic_cube_outline_black_24dp,
    defaultEnabled = false
) {

    private lateinit var session: NetBound
    private lateinit var player: LocalPlayer
    private lateinit var world: World
    private lateinit var inventory: PlayerInventory

    private var lastPlaceTime = 0L
    private val placeDelay = 100L // milliseconds between block placements

    override fun onEnabled() {
        super.onEnabled()
        if (!isSessionCreated) return
        player = session.localPlayer
        world = session.world
        inventory = player.inventory
        lastPlaceTime = 0L
    }

    override fun onDisabled() {
        super.onDisabled()
    }

    override fun beforePacketBound(interceptablePacket: com.project.lumina.client.game.InterceptablePacket) {
        val packet = interceptablePacket.packet
        if (packet is PlayerAuthInputPacket) {
            handlePlayerInput(packet)
        }
    }

    private fun handlePlayerInput(packet: PlayerAuthInputPacket) {
        val pos = packet.position
        val motion = packet.motion
        val onGround = player.isOnGround

        if (shouldPlaceBlock(pos, motion, onGround)) {
            val targetPos = calculateTargetPosition(pos, motion)
            val blockFace = selectBlockFace(targetPos, pos)
            val blockSlot = selectBlockFromInventory()

            if (blockSlot != null && canPlaceBlock()) {
                placeBlock(targetPos, blockFace, blockSlot)
            }
        }
    }

    private fun shouldPlaceBlock(position: Vector3f, motion: Vector3f, onGround: Boolean): Boolean {
        val blockBelowPos = Vector3i.from(
            floor(position.x).toInt(),
            floor(position.y - 1).toInt(),
            floor(position.z).toInt()
        )
        val blockIdBelow = world.getBlockIdAt(blockBelowPos)
        val isAirBelow = blockIdBelow == 0

        val horizontalSpeed = sqrt(motion.x * motion.x + motion.z * motion.z)
        return horizontalSpeed > 0.1f && isAirBelow && !onGround
    }

    private fun calculateTargetPosition(position: Vector3f, motion: Vector3f): Vector3i {
        val lookaheadTime = 0.2f
        val predictedX = position.x + motion.x * lookaheadTime
        val predictedY = position.y - 1.5f // account for eye height offset
        val predictedZ = position.z + motion.z * lookaheadTime
        return Vector3i.from(floor(predictedX).toInt(), floor(predictedY).toInt(), floor(predictedZ).toInt())
    }

    private fun selectBlockFace(targetPos: Vector3i, playerPos: Vector3f): Int {
        val dx = playerPos.x - targetPos.x
        val dz = playerPos.z - targetPos.z

        // Faces: 0=bottom,1=top,2=north,3=south,4=west,5=east
        return when {
            abs(dx) > abs(dz) -> if (dx > 0) 4 else 5
            else -> if (dz > 0) 2 else 3
        }
    }

    private fun selectBlockFromInventory(): Int? {
        return inventory.searchForItemInHotbar { it.isBlock() }
    }

    private fun canPlaceBlock(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPlaceTime < placeDelay) {
            return false
        }
        lastPlaceTime = currentTime
        return true
    }

    private fun placeBlock(targetPos: Vector3i, blockFace: Int, blockSlot: Int) {
        val packet = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            actionType = 0 // CLICK_BLOCK
            blockPosition = targetPos
            this.blockFace = blockFace
            hotbarSlot = blockSlot
            itemInHand = inventory.content[blockSlot]
            playerPosition = player.vec3Position
            clickPosition = Vector3f(0.5f, 0.5f, 0.5f)
            runtimeEntityId = player.runtimeEntityId
        }
        session.serverBound(packet)
    }

    override fun fromJson(jsonElement: kotlinx.serialization.json.JsonElement) {
        super.fromJson(jsonElement)
    }

    override fun toJson(): kotlinx.serialization.json.JsonElement {
        return super.toJson()
    }
}
