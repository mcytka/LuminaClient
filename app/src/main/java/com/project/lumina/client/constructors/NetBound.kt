// NetBound.kt
package com.project.lumina.client.constructors

import android.util.Log
import com.project.lumina.client.application.AppContext
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.LocalPlayer
import com.project.lumina.client.game.entity.Player // Убедитесь, что Player импортирован
import com.project.lumina.client.game.entity.Entity // Убедитесь, что Entity импортирован
import com.project.lumina.client.game.event.EventManager
import com.project.lumina.client.game.event.EventPacketInbound
import com.project.lumina.client.game.registry.BlockMapping
import com.project.lumina.client.game.registry.BlockMappingProvider
import com.project.lumina.client.game.world.Level
import com.project.lumina.client.game.world.World
import com.project.lumina.client.overlay.MiniMapOverlay
import com.project.lumina.client.overlay.OverlayModuleList
import com.project.lumina.client.overlay.PacketNotificationOverlay
import com.project.lumina.client.overlay.Position
import com.project.lumina.client.overlay.SessionStatsOverlay
import com.project.lumina.client.overlay.SpeedometerOverlay
import com.project.lumina.relay.LuminaRelaySession
import com.project.lumina.client.game.registry.ItemMapping
import com.project.lumina.client.game.registry.ItemMappingProvider
import com.project.lumina.client.game.registry.LegacyBlockMapping
import com.project.lumina.client.game.registry.LegacyBlockMappingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import java.util.Collections
import java.util.UUID // <--- Добавьте этот импорт
import java.util.concurrent.ConcurrentHashMap

@Suppress("MemberVisibilityCanBePrivate")
class NetBound(val luminaRelaySession: LuminaRelaySession) : ComposedPacketHandler, com.project.lumina.client.game.event.Listenable {

    override val eventManager = EventManager()

    val world = World(this)
    val level = Level(this)
    val localPlayer = LocalPlayer(this)

    private val proxyPlayerNames: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    val protocolVersion: Int
        get() = luminaRelaySession.server.codec.protocolVersion

    private val mappingProviderContext = AppContext.instance

    private val blockMappingProvider = BlockMappingProvider(mappingProviderContext)
    private val itemMappingProvider = ItemMappingProvider(mappingProviderContext)
    private val legacyBlockMappingProvider = LegacyBlockMappingProvider(mappingProviderContext)

    lateinit var blockMapping: BlockMapping
    lateinit var itemMapping: ItemMapping
    lateinit var legacyBlockMapping: LegacyBlockMapping

    private var startGameReceived = false
    private val pendingPackets = mutableListOf<BedrockPacket>()

    private val mainScope = CoroutineScope(Dispatchers.Main)

    private var playerPosition = Position(0f, 0f)
    private var playerRotation = 0f
    private val entityPositions = mutableMapOf<Long, Position>()
    private var minimapEnabled = false
    private var minimapUpdateScheduled = false
    private var minimapSize = 100f
    private var minimapZoom = 1.0f
    private var minimapDotSize = 5
    private var tracersEnabled = false

    private val versionName by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContext.instance.packageManager.getPackageInfo(
            AppContext.instance.packageName, 0
        ).versionName
    }

    // <<< ИЗМЕНЕНИЕ: Тип ключа теперь UUID >>>
    private val lastKnownPlayerPositions = ConcurrentHashMap<UUID, Vector3f>()
    // <<< КОНЕЦ ИЗМЕНЕНИЯ >>>

    fun clientBound(packet: BedrockPacket) {
        luminaRelaySession.clientBound(packet)
    }

    fun serverBound(packet: BedrockPacket) {
        luminaRelaySession.serverBound(packet)
    }

    override fun beforePacketBound(packet: BedrockPacket): Boolean {
        if (packet is TextPacket && packet.type == TextPacket.Type.CHAT) {
            proxyPlayerNames.add(packet.sourceName)
        }

        if (packet is StartGamePacket && !startGameReceived) {
            startGameReceived = true

            try {
                blockMapping = blockMappingProvider.craftMapping(protocolVersion)
                itemMapping = itemMappingProvider.craftMapping(protocolVersion)
                legacyBlockMapping = legacyBlockMappingProvider.craftMapping(protocolVersion)

                Log.i("GameSession", "✅ Loaded mappings for protocol $protocolVersion")
            } catch (e: Exception) {
                Log.e("GameSession", "❌ Failed to load mappings for protocol $protocolVersion", e)
            }
        }

        localPlayer.onPacketBound(packet)
        world.onPacket(packet)
        level.onPacketBound(packet) // Здесь Level обновляет entityMap

        // <<< ИЗМЕНЕНИЕ: Обновляем lastKnownPlayerPositions, используя player.uuid >>>
        level.entityMap.values.filterIsInstance<Player>().forEach { player ->
            player.uuid?.let { uuid -> // Безопасно получаем UUID, если он не null
                lastKnownPlayerPositions[uuid] = player.vec3Position
            }
        }
        // <<< КОНЕЦ ИЗМЕНЕНИЯ >>>


        val event = EventPacketInbound(this, packet)
        eventManager.emit(event)

        if (event.isCanceled()) return true

        val interceptablePacket = InterceptablePacket(packet)
        for (module in GameManager.elements) {
            module.beforePacketBound(interceptablePacket)
            if (interceptablePacket.isIntercepted) return true
        }

        displayClientMessage("[Lumina V4]", TextPacket.Type.TIP)

        return false
    }

    override fun afterPacketBound(packet: BedrockPacket) {
        for (module in GameManager.elements) {
            module.afterPacketBound(packet)
        }
    }

    override fun onDisconnect(reason: String) {
        localPlayer.onDisconnect()
        level.onDisconnect()
        proxyPlayerNames.clear()
        // <<< ДОБАВЛЕНИЕ: Очистка при отключении >>>
        clearLastKnownPositions()
        // <<< КОНЕЦ ДОБАВЛЕНИЯ >>>

        for (module in GameManager.elements) {
            module.onDisconnect(reason)
        }
    }

    fun displayClientMessage(message: String, type: TextPacket.Type = TextPacket.Type.RAW) {
        val textPacket = TextPacket()
        textPacket.type = type
        textPacket.isNeedsTranslation = false
        textPacket.sourceName = ""
        textPacket.message = message
        textPacket.xuid = ""
        textPacket.platformChatId = ""
        textPacket.filteredMessage = ""
        clientBound(textPacket)
    }

    fun launchOnMain(block: suspend CoroutineScope.() -> Unit) {
        mainScope.launch {
            block()
        }
    }

    suspend fun showSessionStatsOverlay(initialStats: List<String>): SessionStatsOverlay =
        withContext(Dispatchers.Main) {
            try {
                val overlay = SessionStatsOverlay.showSessionStats(initialStats)
                overlay
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }

    fun showNotification(title: String, subtitle: String, ResId: Int) {
        mainScope.launch {
            try {
                PacketNotificationOverlay.showNotification(
                    title = title,
                    subtitle = subtitle,
                    iconRes = ResId,
                    duration = 1000L
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun showSpeedometer(position: Vector3f) {
        mainScope.launch {
            try {
                SpeedometerOverlay.showOverlay()
                SpeedometerOverlay.updatePosition(position)
            } catch (e: Exception) {
                // Handle or log exception
            }
        }
    }

    fun updatePlayerPosition(x: Float, z: Float) {
        playerPosition = Position(x, z)
        if (minimapEnabled) {
            scheduleMinimapUpdate()
        }
    }

    fun updatePlayerRotation(yaw: Float) {
        playerRotation = yaw
        if (minimapEnabled) {
            scheduleMinimapUpdate()
        }
    }

    fun updateEntityPosition(entityId: Long, x: Float, z: Float) {
        entityPositions[entityId] = Position(x, z)
        if (minimapEnabled && !minimapUpdateScheduled) {
            scheduleMinimapUpdate()
        }
    }

    fun updateMinimapSize(size: Float) {
        minimapSize = size
        if (minimapEnabled) {
            mainScope.launch {
                MiniMapOverlay.setMinimapSize(size)
                updateMinimap()
            }
        }
    }

    fun updateMinimapZoom(zoom: Float) {
        minimapZoom = zoom
        if (minimapEnabled) {
            mainScope.launch {
                MiniMapOverlay.overlayInstance.minimapZoom = zoom
                updateMinimap()
            }
        }
    }

    fun updateDotSize(dotSize: Int) {
        minimapDotSize = dotSize
        if (minimapEnabled) {
            mainScope.launch {
                MiniMapOverlay.overlayInstance.minimapDotSize = dotSize
                updateMinimap()
            }
        }
    }

    private fun scheduleMinimapUpdate() {
        if (!minimapUpdateScheduled) {
            minimapUpdateScheduled = true
            mainScope.launch {
                updateMinimap()
                minimapUpdateScheduled = false
            }
        }
    }

    fun enableMinimap(enable: Boolean) {
        if (enable != minimapEnabled) {
            minimapEnabled = enable
            if (enable) {
                mainScope.launch {
                    MiniMapOverlay.setOverlayEnabled(true)
                    MiniMapOverlay.setMinimapSize(minimapSize)
                    updateMinimap()
                }
            } else {
                mainScope.launch {
                    MiniMapOverlay.setOverlayEnabled(false)
                }
            }
        }
    }

    private fun updateMinimap() {
        try {
            MiniMapOverlay.setCenter(playerPosition.x, playerPosition.y)
            MiniMapOverlay.setPlayerRotation(playerRotation)

            MiniMapOverlay.overlayInstance.minimapZoom = minimapZoom
            MiniMapOverlay.overlayInstance.minimapDotSize = minimapDotSize

            val targets = entityPositions.values.toList()

            val finalTargets = if (targets.isEmpty()) {
                targets
            } else {
                targets
            }

            MiniMapOverlay.setTargets(finalTargets)
            MiniMapOverlay.showOverlay()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearEntityPositions() {
        entityPositions.clear()
        if (minimapEnabled) {
            scheduleMinimapUpdate()
        }
    }

    fun showMinimap(centerX: Float, centerZ: Float, targets: List<Position>) {
        mainScope.launch {
            MiniMapOverlay.setOverlayEnabled(true)
            MiniMapOverlay.setMinimapSize(minimapSize)
            MiniMapOverlay.setCenter(centerX, centerZ)
            MiniMapOverlay.setTargets(targets)
            MiniMapOverlay.showOverlay()

            minimapEnabled = true
            playerPosition = Position(centerX, centerZ)
        }
    }

    fun enableArrayList(boolean: Boolean) {
        OverlayModuleList.setOverlayEnabled(enabled = boolean)
    }

    fun setArrayListMode(boolean: Boolean) {
        OverlayModuleList.setCapitalizeAndMerge(boolean)
    }

    fun arrayListUi(string: String) {
        OverlayModuleList.setDisplayMode(string)
    }

    fun isProxyPlayer(playerName: String): Boolean {
        return proxyPlayerNames.contains(playerName)
    }

    // <<< ИЗМЕНЕНИЕ: Получение всех сущностей для ESP, включая "призраков" >>>
    fun getAllEntitiesForEsp(): List<Entity> {
        val currentEntities = level.entityMap.values.toMutableList()
        // Изменено на использование UUID
        val currentActivePlayerUUIDs = currentEntities.filterIsInstance<Player>().mapNotNull { it.uuid }.toSet()

        val ghostPlayers = lastKnownPlayerPositions.mapNotNull { (uuid, position) -> // uuid теперь UUID
            if (uuid !in currentActivePlayerUUIDs) {
                // Этот игрок больше не находится в level.entityMap, но у нас есть его последняя известная позиция.
                // Ищем имя игрока в playerMap по UUID.
                val playerInMap = level.playerMap[uuid] // Ищем по UUID напрямую
                val playerName = playerInMap?.name ?: "Unknown"

                // Создаем "призрачный" объект Player для отображения.
                // runtimeEntityId можно установить в 0, uniqueEntityId (Long) можно установить в 0L.
                // Передаем фактический UUID.
                val ghostPlayer = Player(0, 0L, uuid, playerName).apply { // Передаем uuid напрямую, 0L для uniqueEntityId (Entity's Long ID)
                    this.posX = position.x
                    this.posY = position.y
                    this.posZ = position.z
                    this.lastKnownPosition = position // Устанавливаем lastKnownPosition
                    this.isDisappeared = true // Помечаем как исчезнувшую
                }
                ghostPlayer
            } else {
                null
            }
        }
        return currentEntities + ghostPlayers
    }
    // <<< КОНЕЦ ИЗМЕНЕНИЯ >>>

    // <<< НОВЫЙ МЕТОД: Очистка сохраненных позиций >>>
    fun clearLastKnownPositions() {
        lastKnownPlayerPositions.clear()
    }
    // <<< КОНЕЦ НОВОГО МЕТОДА >>>
}
