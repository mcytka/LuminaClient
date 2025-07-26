//ESPOverlay.kt
package com.project.lumina.client.overlay

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset // Этот импорт все еще нужен, так как CustomESPView использует Offset

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView

import com.project.lumina.client.game.entity.Entity
import com.project.lumina.client.game.entity.Item
import com.project.lumina.client.game.entity.MobList
import com.project.lumina.client.game.entity.Player // Убедитесь, что Player импортирован
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
    private var use3dBoxes by mutableStateOf(false) // <<< ДОБАВЛЕНО: Состояние переключателя >>>

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

        fun updateEntities(entityList: List<Entity>) {
            // Преобразуем List<Entity> в List<ESPRenderEntity>
            overlayInstance.entities = entityList.map { entity ->
                ESPRenderEntity(
                    entity = entity,
                    username = (entity as? Player)?.username // Приводим к Player и получаем username
                )
            }
        }

        fun setFov(newFov: Float) {
            overlayInstance.fov = newFov
        }

        // <<< ДОБАВЛЕНО: Функция для обновления состояния use3dBoxes >>>
        fun setUse3dBoxes(value: Boolean) {
            overlayInstance.use3dBoxes = value
        }
        // <<< КОНЕЦ ДОБАВЛЕНИЯ >>>
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
                        use3dBoxes = use3dBoxes // <<< ДОБАВЛЕНО: Передаем состояние >>>
                    )
                )
            }
        )
    }
}
