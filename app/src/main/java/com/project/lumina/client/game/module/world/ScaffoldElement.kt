package com.project.lumina.client.game.module.world

import com.project.lumina.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.registry.isBlock
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket
import kotlin.math.floor

class ScaffoldElement : Element(
    name = "Scaffold",
    category = CheatCategory.World,
    iconResId = R.drawable.ic_cube_outline_black_24dp,
    defaultEnabled = false
) {

    private val tower by boolValue("Tower", true)
    private val rotate by boolValue("Rotate", true)
    private val prediction by floatValue("Prediction", 0.25f, 0f..2f)
    private val delay by intValue("Delay", 50, 0..500)

    private var lastPlacementTime = 0L

    /**
     * Перехватываем пакеты, идущие на сервер. Этот метод вызывается только для них,
     * что идеально подходит для нашей задачи.
     */
    override fun beforeServerBound(interceptablePacket: InterceptablePacket) {
        val packet = interceptablePacket.packet

        if (!isEnabled || !isSessionCreated) {
            return
        }

        // Нам нужен только PlayerAuthInputPacket
        if (packet is PlayerAuthInputPacket) {
            // Если другой модуль уже запланировал действие, не вмешиваемся
            if (packet.itemUseTransaction == null) {
                handleScaffoldLogic(packet)
            }
        }
    }

    /**
     * Основная логика чита. Вызывается из beforeServerBound.
     * Эта функция осталась без изменений, так как внутренняя логика была верной.
     */
    private fun handleScaffoldLogic(packet: PlayerAuthInputPacket) {
        if (System.currentTimeMillis() - lastPlacementTime < delay) {
            return
        }

        val player = session.localPlayer
        val world = session.world

        val predictedPos = player.vec3Position.add(packet.delta.mul(prediction))
        val isJumping = packet.inputData.contains(PlayerAuthInputData.JUMPING)
        val isMovingHorizontally = packet.motion.x != 0f || packet.motion.y != 0f

        val targetPos = if (tower && isJumping && !isMovingHorizontally) {
            Vector3i.from(floor(player.posX).toInt(), floor(player.posY - 1).toInt(), floor(player.posZ).toInt())
        } else {
            Vector3i.from(floor(predictedPos.x).toInt(), floor(predictedPos.y - 1).toInt(), floor(predictedPos.z).toInt())
        }

        if (world.getBlockIdAt(targetPos) != session.blockMapping.airId) {
            return
        }

        val blockToPlace = findBestBlock() ?: return
        val anchor = findAnchorBlock(targetPos) ?: return
        val inventory = player.inventory

        if (inventory.heldItemSlot != blockToPlace.slot && blockToPlace.slot in 0..8) {
            session.serverBound(PlayerHotbarPacket().apply {
                selectedHotbarSlot = blockToPlace.slot
                containerId = 0
                selectHotbarSlot = true
            })
            return // Ждем следующего тика, чтобы сервер обработал смену хотбара
        }

        val itemInHand = inventory.content[inventory.heldItemSlot]
        if (!itemInHand.isBlock() || isNonScaffoldBlock(itemInHand)) {
            return
        }

        if (rotate) {
            packet.rotation = Vector3f.from(82f, packet.rotation.y, packet.rotation.z)
        }

        val transaction = ItemUseTransaction().apply {
            actionType = 1 // CLICK_BLOCK
            blockPosition = anchor.pos
            blockFace = anchor.face
            hotbarSlot = inventory.heldItemSlot
            this.itemInHand = itemInHand
            playerPosition = player.vec3Position
            clickPosition = Vector3f.from(0.5, 0.5, 0.5)
            blockDefinition = session.blockMapping.getDefinition(itemInHand.blockDefinition?.runtimeId ?: 0)
        }

        packet.itemUseTransaction = transaction
        packet.inputData.add(PlayerAuthInputData.PERFORM_ITEM_INTERACTION)

        lastPlacementTime = System.currentTimeMillis()

        if (itemInHand.count > 1) {
            inventory.content[inventory.heldItemSlot] = itemInHand.toBuilder().count(itemInHand.count - 1).build()
        } else {
            inventory.content[inventory.heldItemSlot] = ItemData.AIR
        }
    }

    private fun findBestBlock(): BlockPlacementData? {
        val inventory = session.localPlayer.inventory
        for (i in 0 until 36) {
            val item = inventory.content[i]
            if (item.isBlock() && !isNonScaffoldBlock(item)) {
                return BlockPlacementData(i, item)
            }
        }
        return null
    }

    private fun isNonScaffoldBlock(item: ItemData): Boolean {
        val identifier = item.definition.identifier
        return identifier.contains("chest") ||
                identifier.contains("shulker_box") ||
                identifier.contains("furnace") ||
                identifier.contains("crafting_table") ||
                identifier.contains("bed") ||
                identifier.contains("door") ||
                identifier.contains("gate")
    }

    private fun findAnchorBlock(targetPos: Vector3i): AnchorResult? {
        val world = session.world
        val belowPos = targetPos.sub(0, 1, 0)
        if (world.getBlockIdAt(belowPos) != session.blockMapping.airId) {
            return AnchorResult(belowPos, 1) // Face.UP
        }

        val neighbors = listOf(
            targetPos.add(0, 0, 1) to 2,  // Face.NORTH
            targetPos.add(0, 0, -1) to 3, // Face.SOUTH
            targetPos.add(1, 0, 0) to 4,   // Face.WEST
            targetPos.add(-1, 0, 0) to 5,  // Face.EAST
        )

        for ((pos, face) in neighbors) {
            if (world.getBlockIdAt(pos) != session.blockMapping.airId) {
                return AnchorResult(pos, face)
            }
        }
        return null
    }

    private data class BlockPlacementData(val slot: Int, val item: ItemData)
    private data class AnchorResult(val pos: Vector3i, val face: Int)
}
