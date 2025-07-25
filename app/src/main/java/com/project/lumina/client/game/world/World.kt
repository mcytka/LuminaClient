package com.project.lumina.client.game.world

import android.util.Log
import com.project.lumina.client.constructors.NetBound
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket
import java.util.concurrent.ConcurrentHashMap

class World(private val session: NetBound) {

    private val loadedBlocks = ConcurrentHashMap<Long, ConcurrentHashMap<Vector3i, Int>>()
    private val BLOCK_ID_AIR = 0
    private val TAG_WORLD = "LuminaWorld"

    fun getBlockIdAt(position: Vector3i): Int {
        val chunkX = position.x shr 4
        val chunkZ = position.z shr 4
        val chunkHash = getChunkHash(chunkX, chunkZ)

        val chunkBlocks = loadedBlocks[chunkHash]
        if (chunkBlocks != null) {
            val blockId = chunkBlocks[position] ?: BLOCK_ID_AIR
            return blockId
        }
        return BLOCK_ID_AIR
    }

    /**
     * НОВЫЙ МЕТОД: Позволяет вручную установить Block Runtime ID по заданной позиции.
     * Вероятно, используется для отладки или симуляции.
     */
    fun setBlockIdAt(position: Vector3i, blockId: Int) {
        val chunkX = position.x shr 4
        val chunkZ = position.z shr 4
        val chunkHash = getChunkHash(chunkX, chunkZ)

        // Получаем или создаем карту блоков для этого чанка
        val chunkBlocks = loadedBlocks.getOrPut(chunkHash) { ConcurrentHashMap() }
        chunkBlocks[position] = blockId
        Log.d(TAG_WORLD, "setBlockIdAt($position): Manually set block ID to $blockId.")
    }

    fun onPacket(packet: BedrockPacket) {
        when (packet) {
            is LevelChunkPacket -> {
                Log.d(TAG_WORLD, "Intercepted LevelChunkPacket via onPacket.")
                handleLevelChunkPacket(packet)
            }
            is UpdateBlockPacket -> {
                Log.d(TAG_WORLD, "Intercepted UpdateBlockPacket via onPacket.")
                handleUpdateBlockPacket(packet)
            }
            // Добавьте другие пакеты, если они влияют на состояние мира (например, BlockActorDataPacket)
            else -> {
                // Log.d(TAG_WORLD, "Ignoring packet type: ${packet.packetType}")
            }
        }
    }

    fun handleLevelChunkPacket(packet: LevelChunkPacket) {
        val chunkX = packet.chunkX
        val chunkZ = packet.chunkZ
        val chunkHash = getChunkHash(chunkX, chunkZ)

        Log.d(TAG_WORLD, "Handling LevelChunkPacket for chunk ($chunkX, $chunkZ). Data size: ${packet.data.readableBytes()} bytes")

        val newChunkBlocks = ConcurrentHashMap<Vector3i, Int>()

        // TODO: НЕОБХОДИМО РЕАЛИЗОВАТЬ ДЕКОДИРОВАНИЕ packet.data (ByteBuf) ЗДЕСЬ.
        // Это самый сложный шаг. Вам нужно будет прочитать подчанки, палитры блоков
        // и конвертировать это в отдельные Block Runtime ID для каждой позиции.
        // Пока не реализовано, этот чанк будет считаться пустым, если не будет UpdateBlockPacket.

        loadedBlocks[chunkHash] = newChunkBlocks
        Log.d(TAG_WORLD, "Finished processing LevelChunkPacket for chunk ($chunkX, $chunkZ). (Decoding logic placeholder)")
    }

    fun handleUpdateBlockPacket(packet: UpdateBlockPacket) {
        val pos = packet.blockPosition
        val newBlockRuntimeId = packet.definition.runtimeId

        val chunkX = pos.x shr 4
        val chunkZ = pos.z shr 4
        val chunkHash = getChunkHash(chunkX, chunkZ)

        val chunkBlocks = loadedBlocks[chunkHash]
        if (chunkBlocks != null) {
            chunkBlocks[pos] = newBlockRuntimeId
            Log.d(TAG_WORLD, "Updated block at $pos to ID $newBlockRuntimeId from UpdateBlockPacket.")
        } else {
            Log.w(TAG_WORLD, "Received UpdateBlockPacket for unloaded chunk ($chunkX, $chunkZ) at $pos. Cannot update.")
            loadedBlocks.getOrPut(chunkHash) { ConcurrentHashMap() }[pos] = newBlockRuntimeId
            Log.d(TAG_WORLD, "Added block $pos (ID $newBlockRuntimeId) to newly created chunk entry.")
        }
    }

    private fun getChunkHash(chunkX: Int, chunkZ: Int): Long {
        return chunkX.toLong() and 0xFFFFFFFFL or (chunkZ.toLong() shl 32)
    }
}
