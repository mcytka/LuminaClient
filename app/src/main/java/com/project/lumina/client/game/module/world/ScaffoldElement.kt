package com.project.lumina.client.game.module.world

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.NetBound
import com.project.lumina.client.game.entity.LocalPlayer
import com.project.lumina.client.game.inventory.PlayerInventory
import com.project.lumina.client.game.world.World
import com.project.lumina.client.game.utils.constants.ItemTags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

class ScaffoldElement : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId = R.drawable.ic_cube_outline_black_24dp,
    defaultEnabled = false
) {

    override lateinit var session: NetBound
    private lateinit var player: LocalPlayer
    private lateinit var world: World
    private lateinit var inventory: PlayerInventory

    private var lastPlaceTime = 0L
    private val placeDelay by intValue("Place Delay (ms)", 100, 50..500)
    private val scaffoldMode by listValue(
        "Mode",
        ScaffoldMode.BRIDGING,
        setOf(ScaffoldMode.BRIDGING, ScaffoldMode.TOWERING)
    )

    enum class ScaffoldMode {
        BRIDGING,
        TOWERING
    }

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
                simulateHeadRotation(packet, targetPos)
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
        val predictedY = when (scaffoldMode) {
            ScaffoldMode.BRIDGING -> position.y - 1.5f
            ScaffoldMode.TOWERING -> position.y - 1.0f
        }
        val predictedZ = position.z + motion.z * lookaheadTime
        return Vector3i.from(floor(predictedX).toInt(), floor(predictedY).toInt(), floor(predictedZ).toInt())
    }

    private fun selectBlockFace(targetPos: Vector3i, playerPos: Vector3f): Int {
        val dx = playerPos.x - targetPos.x
        val dz = playerPos.z - targetPos.z

        return when (scaffoldMode) {
            ScaffoldMode.BRIDGING -> {
                when {
                    abs(dx) > abs(dz) -> if (dx > 0) 4 else 5
                    else -> if (dz > 0) 2 else 3
                }
            }
            ScaffoldMode.TOWERING -> 1 // top face
        }
    }

    private fun selectBlockFromInventory(): Int? {
        return inventory.searchForItemInHotbar { it.itemDefinition.isBlock() }
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
            clickPosition = Vector3f.from(0.5f, 0.5f, 0.5f)
            runtimeEntityId = player.runtimeEntityId
        }
        session.serverBound(packet)
    }

    private fun simulateHeadRotation(packet: PlayerAuthInputPacket, targetPos: Vector3i) {
        val dx = targetPos.x + 0.5f - player.posX
        val dy = targetPos.y + 1.5f - (player.posY + 1.62f) // eye height offset
        val dz = targetPos.z + 0.5f - player.posZ

        val distanceXZ = sqrt(dx * dx + dz * dz)
        val pitch = -Math.toDegrees(atan2(dy.toDouble(), distanceXZ.toDouble())).toFloat()
        val yaw = Math.toDegrees(atan2(dz.toDouble(), dx.toDouble())).toFloat() - 90f

        packet.rotation = Vector3f.from(pitch, yaw, 0f)
    }

    override fun fromJson(jsonElement: kotlinx.serialization.json.JsonElement) {
        super.fromJson(jsonElement)
    }

    override fun toJson(): JsonObject {
        return buildJsonObject {
            put("state", isEnabled)
            put("values", buildJsonObject {
                values.forEach { value ->
                    val key = if (value.name.isNotEmpty()) value.name else value.nameResId.toString()
                    put(key, value.toJson())
                }
            })
        }
    }
}
