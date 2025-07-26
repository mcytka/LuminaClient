// ESPOverlay.kt
package com.project.lumina.client.overlay

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView

import com.project.lumina.client.game.entity.Entity
import com.project.lumina.client.game.entity.Item
import com.project.lumina.client.game.entity.MobList
import com.project.lumina.client.game.entity.Player
import org.cloudburstmc.math.vector.Vector3f


class ESPOverlay : OverlayWindow() {
    private val _layoutParams by lazy {
        super.layoutParams.apply {
            flags = flags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
            format = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    override val layoutParams: WindowManager.LayoutParams
        get() = _layoutParams

    private var playerPosition by mutableStateOf(Vector3f.ZERO)
    private var playerRotation by mutableStateOf(Vector3f.ZERO)
    private var entities by mutableStateOf(emptyList<ESPRenderEntity>())
    private var fov by mutableStateOf(70f)
    private var use3dBoxes by mutableStateOf(false)
    private var showPlayerInfo by mutableStateOf(true) // <<< ДОБАВЛЕНО: Поле для новой настройки >>>

    companion object {
        val overlayInstance by lazy { ESPOverlay() }
        private var shouldShowOverlay = false

        fun showOverlay() {
            shouldShowOverlay = true
            try {
                OverlayManager.showOverlayWindow(overlayInstance)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun dismissOverlay() {
            shouldShowOverlay = false
            try {
                OverlayManager.dismissOverlayWindow(overlayInstance)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun updatePlayerData(position: Vector3f, pitch: Float, yaw: Float) {
            overlayInstance.playerPosition = position
            overlayInstance.playerRotation = Vector3f.from(pitch, yaw, 0f)
        }

        // <<< ИЗМЕНЕНИЯ ЗДЕСЬ: Заполнение health и maxHealth >>>
        fun updateEntities(entityList: List<Entity>) {
            overlayInstance.entities = entityList.map { entity ->
                ESPRenderEntity(
                    entity = entity,
                    username = (entity as? Player)?.username,
                    health = entity.health, // Получаем текущее здоровье из Entity
                    maxHealth = entity.maxHealth // Получаем максимальное здоровье из Entity
                )
            }
        }
        // <<< КОНЕЦ ИЗМЕНЕНИЙ >>>

        fun setFov(newFov: Float) {
            overlayInstance.fov = newFov
        }

        fun setUse3dBoxes(value: Boolean) {
            overlayInstance.use3dBoxes = value
        }

        // <<< ДОБАВЛЕНО: Функция для установки значения showPlayerInfo >>>
        fun setShowPlayerInfo(value: Boolean) {
            overlayInstance.showPlayerInfo = value
        }
        // <<< КОНЕЦ ДОБАВЛЕННОЙ ФУНКЦИИ >>>
    }

    @Composable
    override fun Content() {
        if (!shouldShowOverlay) return

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                CustomESPView(context)
            },
            update = { customView ->
                customView.updateESPData(
                    ESPData(
                        playerPosition = playerPosition,
                        playerRotation = playerRotation,
                        entities = entities,
                        fov = fov,
                        use3dBoxes = use3dBoxes,
                        showPlayerInfo = showPlayerInfo // <<< ПЕРЕДАЧА НОВОЙ НАСТРОЙКИ >>>
                    )
                )
            }
        )
    }
}
