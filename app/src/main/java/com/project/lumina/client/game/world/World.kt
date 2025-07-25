package com.project.lumina.client.game.world

import android.util.Log
import com.project.lumina.client.constructors.NetBound // Импортируем NetBound
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket // Импортируем BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket
import java.util.concurrent.ConcurrentHashMap

class World(private val session: NetBound) { // <--- ДОБАВЛЕН КОНСТРУКТОР С NetBound

    // Карта для хранения блоков: ключ - хеш чанка, значение - карта блоков в этом чанке
    private val loadedBlocks = ConcurrentHashMap<Long, ConcurrentHashMap<Vector3i, Int>>()
    private val BLOCK_ID_AIR = 0 // Bedrock Protocol: 0 обычно означает воздух
    private val TAG_WORLD = "LuminaWorld" // Тег для логов мира

    /**
     * Возвращает Block Runtime ID блока по заданной позиции.
     * Если чанк не загружен или блок не найден, возвращает ID воздуха (0).
     */
    fun getBlockIdAt(position: Vector3i): Int {
        val chunkX = position.x shr 4 // Делим на 16, чтобы получить X-координату чанка
        val chunkZ = position.z shr 4 // Делим на 16, чтобы получить Z-координату чанка
        val chunkHash = getChunkHash(chunkX, chunkZ)

        val chunkBlocks = loadedBlocks[chunkHash]
        if (chunkBlocks != null) {
            val blockId = chunkBlocks[position] ?: BLOCK_ID_AIR // Если блок не найден в чанке, считаем воздухом
            // Log.d(TAG_WORLD, "getBlockIdAt($position): Found ID=$blockId in loaded chunk ($chunkX, $chunkZ)")
            return blockId
        }
        // Log.d(TAG_WORLD, "getBlockIdAt($position): Chunk ($chunkX, $chunkZ) not loaded. Returning AIR.")
        return BLOCK_ID_AIR
    }

    /**
     * **НОВЫЙ МЕТОД:** Обрабатывает входящие пакеты и направляет их
     * соответствующим обработчикам World.
     * Этот метод вызывается из NetBound.
     */
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

    /**
     * Обрабатывает LevelChunkPacket для загрузки данных чанка.
     *
     * ВНИМАНИЕ: Это заглушка! Реализация полноценного декодирования ByteBuf packet.data
     * в Block Runtime IDs - это сложная задача, требующая обращения к деталям Bedrock Protocol
     * и, возможно, использования внутренних утилит cloudburstmc.
     * Пока этот метод не будет полностью реализован, World-кэш не будет корректно
     * отражать состояние мира при загрузке новых чанков.
     */
    fun handleLevelChunkPacket(packet: LevelChunkPacket) {
        val chunkX = packet.chunkX
        val chunkZ = packet.chunkZ
        val chunkHash = getChunkHash(chunkX, chunkZ)

        Log.d(TAG_WORLD, "Handling LevelChunkPacket for chunk ($chunkX, $chunkZ). Data size: ${packet.data.readableBytes()} bytes")

        // Создаем новую карту для блоков этого чанка
        val newChunkBlocks = ConcurrentHashMap<Vector3i, Int>()

        // TODO: НЕОБХОДИМО РЕАЛИЗОВАТЬ ДЕКОДИРОВАНИЕ packet.data (ByteBuf) ЗДЕСЬ.
        // Это самый сложный шаг. Вам нужно будет прочитать подчанки, палитры блоков
        // и конвертировать это в отдельные Block Runtime ID для каждой позиции.
        // Пока не реализовано, этот чанк будет считаться пустым, если не будет UpdateBlockPacket.
        // В качестве временной меры, если вы не хотите, чтобы Scaffold ставил блоки в новые чанки,
        // пока они не будут полностью загружены, вы можете просто ничего не добавлять в newChunkBlocks.

        loadedBlocks[chunkHash] = newChunkBlocks
        Log.d(TAG_WORLD, "Finished processing LevelChunkPacket for chunk ($chunkX, $chunkZ). (Decoding logic placeholder)")
    }

    /**
     * Обрабатывает UpdateBlockPacket для обновления отдельного блока в кэше мира.
     * Этот метод КРАЙНЕ ВАЖЕН для Scaffold, так как он позволяет отслеживать
     * изменения блоков (установку/разрушение) в реальном времени.
     */
    fun handleUpdateBlockPacket(packet: UpdateBlockPacket) {
        val pos = packet.blockPosition
        // Получаем Block Runtime ID из BlockDefinition, предоставленного в пакете
        val newBlockRuntimeId = packet.definition.runtimeId // Используем .runtimeId

        val chunkX = pos.x shr 4
        val chunkZ = pos.z shr 4
        val chunkHash = getChunkHash(chunkX, chunkZ)

        val chunkBlocks = loadedBlocks[chunkHash]
        if (chunkBlocks != null) {
            chunkBlocks[pos] = newBlockRuntimeId // Обновляем блок в кэше
            Log.d(TAG_WORLD, "Updated block at $pos to ID $newBlockRuntimeId from UpdateBlockPacket.")
        } else {
            // Это может произойти, если UpdateBlockPacket пришел для чанка,
            // который еще не был загружен LevelChunkPacket (или его обработка не завершена).
            Log.w(TAG_WORLD, "Received UpdateBlockPacket for unloaded chunk ($chunkX, $chunkZ) at $pos. Cannot update.")
            // Опционально: можно добавить блок, даже если чанк не "полностью" загружен,
            // но это может привести к неполным данным о чанке.
            loadedBlocks.getOrPut(chunkHash) { ConcurrentHashMap() }[pos] = newBlockRuntimeId
            Log.d(TAG_WORLD, "Added block $pos (ID $newBlockRuntimeId) to newly created chunk entry.")
        }
    }

    /**
     * Генерирует уникальный хеш для пары координат чанка (X, Z).
     */
    private fun getChunkHash(chunkX: Int, chunkZ: Int): Long {
        return chunkX.toLong() and 0xFFFFFFFFL or (chunkZ.toLong() shl 32)
    }
}
