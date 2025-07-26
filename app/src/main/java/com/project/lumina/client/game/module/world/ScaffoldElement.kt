/*/package com.project.lumina.client.game.module.world

import android.util.Log
import com.project.lumina.client.constructors.Module
import com.project.lumina.client.constructors.NetBound
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.constructors.Category
import com.project.lumina.client.game.event.EventPacketInbound
import com.project.lumina.client.game.event.EventTick
import com.project.lumina.client.game.event.Listen
import com.project.lumina.client.game.inventory.ItemData
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.BlockFace // Импорт BlockFace из CloudburstMC
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.BlockPlacePacket // Используем BlockPlacePacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket // Для смены слота
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemUseAction

import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.roundToInt // Для округления углов

class ScaffoldElement(val session: NetBound) : Module("Scaffold", "Automatically places blocks under your feet.", Category.WORLD) {

    // Вспомогательная функция для получения ItemData из инвентаря.
    private fun getBlockItemInHotbar(): ItemData? {
        for (i in 0..8) {
            val item = session.localPlayer.inventory.getItemInHotbar(i)
            if (item != null && item.definition.isBlock && item.count > 0) {
                if (item.definition.runtimeId != 0) { // Простая проверка на не-воздух
                    return item
                }
            }
        }
        session.displayClientMessage("§c[Scaffold] No suitable block found in hotbar!", TextPacket.Type.CHAT)
        return null
    }

    // Вспомогательная функция для переключения слота хотбара
    private fun selectHotbarSlot(slot: Int) {
        if (session.localPlayer.inventory.selectedHotbarSlot != slot) {
            val playerActionPacket = PlayerActionPacket().apply {
                this.runtimeEntityId = session.localPlayer.runtimeEntityId
                this.position = Vector3i.from(session.localPlayer.vec3PositionFeet.floor())
                this.face = 0
                this.action = PlayerActionPacket.Action.CHANGE_SLOT
                this.resultPosition = Vector3i.ZERO
                this.resultBlockRuntimeId = 0
                this.int2 = slot
            }
            session.serverBound(playerActionPacket)
            Log.d("Scaffold", "Switched to hotbar slot $slot")
            session.localPlayer.inventory.selectedHotbarSlot = slot
        }
    }


    @Listen
    fun onTick(event: EventTick) {
        if (!isEnabled) return

        val playerPos = session.localPlayer.vec3PositionFeet
        val targetBlockPos = Vector3i.from(
            floor(playerPos.x).toInt(),
            floor(playerPos.y - 1).toInt(), // Позиция, куда мы хотим поставить блок (должна быть воздухом)
            floor(playerPos.z).toInt()
        )

        // Проверяем, является ли целевая позиция воздухом в нашем локальном кэше
        if (session.world.isAir(targetBlockPos)) {
            Log.d("Scaffold", "Target block at $targetBlockPos is air (ID: ${session.world.getBlockIdAt(targetBlockPos)})")

            val blockToPlace = getBlockItemInHotbar()
            if (blockToPlace == null) {
                Log.w("Scaffold", "No block to place found in hotbar.")
                return
            }

            // Находим опорный блок и грань для установки, учитывая yaw игрока
            val (clickedBlockPos, clickedFace) = findPlacementReference(targetBlockPos, session.localPlayer.vec3Rotation.y)

            if (clickedBlockPos != null && clickedFace != null) {
                val hotbarSlot = session.localPlayer.inventory.getHotbarSlotForItem(blockToPlace)
                if (hotbarSlot != -1) {
                    selectHotbarSlot(hotbarSlot)
                } else {
                    session.displayClientMessage("§c[Scaffold] Block not in hotbar!", TextPacket.Type.CHAT)
                    return
                }

                Log.d("Scaffold", "Attempting to place block at $targetBlockPos by clicking on $clickedBlockPos, face $clickedFace")

                val placePacket = BlockPlacePacket().apply {
                    this.blockPosition = clickedBlockPos // ПОЗИЦИЯ ТВЕРДОГО ОПОРНОГО БЛОКА
                    this.face = clickedFace.id.toByte() // ГРАНЬ ТВЕРДОГО ОПОРНОГО БЛОКА
                    this.hotbarSlot = session.localPlayer.inventory.selectedHotbarSlot.toByte()
                    this.heldItem = blockToPlace
                    this.playerPosition = playerPos
                    this.clickPosition = Vector3f.from(0.5f, 0.5f, 0.5f) // Центр грани
                    this.runtimeBlockId = blockToPlace.definition.runtimeId
                    this.action = ItemUseAction.PLACE
                }
                session.serverBound(placePacket)
                session.displayClientMessage("§a[Scaffold] Placed block at $targetBlockPos", TextPacket.Type.CHAT)

                // Опционально: Обновить локальный кэш мира немедленно.
                // session.world.setBlockIdAt(targetBlockPos, blockToPlace.definition.runtimeId)

            } else {
                Log.w("Scaffold", "Could not find a solid reference block for placement at $targetBlockPos.")
                session.displayClientMessage("§e[Scaffold] No solid block to place against.", TextPacket.Type.CHAT)
            }
        } else {
            Log.d("Scaffold", "Target block at $targetBlockPos is NOT air (ID: ${session.world.getBlockIdAt(targetBlockPos)}), no action.")
        }
    }


    /**
     * Ищет твердый опорный блок (на который кликаем) и грань для размещения нового блока.
     * Опорный блок должен быть непосредственно под игроком, а грань определяется направлением взгляда.
     *
     * @param targetPos Позиция, куда мы хотим поставить новый блок (должна быть воздухом).
     * @param playerYaw Угол поворота игрока по горизонтали (yaw).
     * @return Pair<Vector3i, BlockFace>? где первый элемент - позиция опорного блока,
     * второй - грань клика. Null, если не найдено.
     */
    private fun findPlacementReference(targetPos: Vector3i, playerYaw: Float): Pair<Vector3i, BlockFace>? {
        // Позиция блока, на котором стоит игрок (это наш основной кандидат на опорный блок)
        val standingBlockPos = Vector3i.from(targetPos.x, targetPos.y, targetPos.z) // Это и есть targetPos из onTick
        // Нам нужен блок, на котором игрок *действительно* стоит, а не где должен появиться новый блок.
        // То есть, блок под ногами игрока, который является твердым.
        // Если playerPos.y это высота глаз, то playerPos.y - 1 это блок ног, а playerPos.y - 2 это блок под ногами.
        // Поэтому опорный блок - это floor(playerY - 1).

        val referenceBlockPos = Vector3i.from(
            floor(session.localPlayer.vec3PositionFeet.x).toInt(),
            floor(session.localPlayer.vec3PositionFeet.y - 1).toInt(), // Блок, на котором стоит игрок
            floor(session.localPlayer.vec3PositionFeet.z).toInt()
        )

        // Проверяем, что блок под ногами игрока действительно твердый
        if (session.world.isAir(referenceBlockPos)) {
            Log.w("ScaffoldDebug", "Player is standing on air at $referenceBlockPos. Cannot place block without solid ground.")
            // Если игрок в воздухе, Scaffold не может работать по этой логике.
            // Возможно, здесь нужна дополнительная логика для прыжков или спуска.
            return null
        }

        // Определяем грань, на которую кликаем, основываясь на угле поворота игрока (yaw)
        // Minecraft Yaw: -180 (North) до 180 (North)
        // 0 = South, 90 = West, 180/-180 = North, -90 = East

        val roundedYaw = (playerYaw % 360 + 360) % 360 // Нормализуем yaw от 0 до 360
        val cardinalDirection = when {
            roundedYaw >= 45 && roundedYaw < 135 -> BlockFace.WEST // Игрок смотрит на запад, ставим на восток (кликаем на восточную грань блока под ногами)
            roundedYaw >= 135 && roundedYaw < 225 -> BlockFace.NORTH // Игрок смотрит на север, ставим на юг (кликаем на южную грань)
            roundedYaw >= 225 && roundedYaw < 315 -> BlockFace.EAST // Игрок смотрит на восток, ставим на запад (кликаем на западную грань)
            else -> BlockFace.SOUTH // Игрок смотрит на юг, ставим на север (кликаем на северную грань)
        }

        // Логика: если игрок смотрит на SOUTH, он хочет поставить блок вперед (на NORTH от себя).
        // Поэтому он кликает на NORTH грань блока, на котором стоит.
        val faceToClick = when (cardinalDirection) {
            BlockFace.NORTH -> BlockFace.SOUTH // Если смотрим на NORTH, кликаем на SOUTH грань опорного блока
            BlockFace.SOUTH -> BlockFace.NORTH // Если смотрим на SOUTH, кликаем на NORTH грань опорного блока
            BlockFace.EAST -> BlockFace.WEST   // Если смотрим на EAST, кликаем на WEST грань опорного блока
            BlockFace.WEST -> BlockFace.EAST   // Если смотрим на WEST, кликаем на EAST грань опорного блока
            else -> BlockFace.UP // Fallback, если что-то пошло не так, или для особых случаев (например, если игрок смотрит прямо вверх/вниз)
        }


        Log.d("ScaffoldDebug", "Player Yaw: $playerYaw, Rounded Yaw: $roundedYaw, Detected Face to Click: $faceToClick")
        return Pair(referenceBlockPos, faceToClick)
    }

    // Обработка пакетов (если нужно)
    @Listen
    fun onPacketInbound(event: EventPacketInbound) {
        if (!isEnabled) return
        // Если вы хотите, чтобы Scaffold реагировал на какие-то пакеты, добавьте логику здесь.
    }
}
\*\
