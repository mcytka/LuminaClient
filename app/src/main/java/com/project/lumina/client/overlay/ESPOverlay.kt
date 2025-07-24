package com.project.lumina.client.overlay

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset // <<-- ВЕРНУЛИ ЭТОТ ИМПОРТ!

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView

import com.project.lumina.client.game.entity.Entity
import com.project.lumina.client.game.entity.Item
import com.project.lumina.client.game.entity.MobList
import com.project.lumina.client.game.entity.Player
import org.cloudburstmc.math.vector.Vector3f

// Добавляем импорты kotlin.math для функций cos, sin, tan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.sqrt // Убедитесь, что этот импорт есть, если sqrt используется
import kotlin.math.pow  // Убедитесь, что этот импорт есть, если pow используется


// Класс ESPOverlay остается почти таким же, меняется только Content()
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
    private var entities by mutableStateOf(emptyList<Entity>())
    private var fov by mutableStateOf(70f)

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
            overlayInstance.entities = entityList
        }

        fun setFov(newFov: Float) {
            overlayInstance.fov = newFov
        }
    }

    @Composable
    override fun Content() {
        if (!shouldShowOverlay) return

        // Вместо Compose Canvas используем AndroidView
        AndroidView(
            modifier = Modifier.fillMaxSize(), // Занимает весь доступный размер
            factory = { context ->
                // Создаем и возвращаем наш CustomESPView
                CustomESPView(context)
            },
            update = { customView ->
                // Обновляем данные в CustomESPView каждый раз, когда состояния меняются
                customView.updateESPData(
                    ESPData(
                        playerPosition = playerPosition,
                        playerRotation = playerRotation,
                        entities = entities,
                        fov = fov
                    )
                )
            }
        )
    }

    // Ваша функция worldToScreen остается здесь, так как она не зависит от Canvas API
    private fun worldToScreen(
        entityPos: Vector3f,
        playerPos: Vector3f,
        playerYaw: Float,
        playerPitch: Float,
        screenWidth: Float,
        screenHeight: Float,
        fov: Float
    ): Offset? {
        // Разница позиций
        val dx = entityPos.x - playerPos.x
        val dy = entityPos.y - playerPos.y
        val dz = entityPos.z - playerPos.z

        // Учет вращения игрока
        val yawRad = Math.toRadians(playerYaw.toDouble()).toFloat()
        val pitchRad = Math.toRadians(playerPitch.toDouble()).toFloat()

        // Поворот по горизонтали
        val x1 = dx * cos(yawRad) - dz * sin(yawRad)
        val z1 = dx * sin(yawRad) + dz * cos(yawRad)

        // Поворот по вертикали
        val y1 = dy * cos(pitchRad) - z1 * sin(pitchRad)
        val z2 = dy * sin(pitchRad) + z1 * cos(pitchRad)

        // Если объект позади камеры - пропускаем
        if (z2 < 0.1f) return null

        // Проекция на экран
        val fovRad = Math.toRadians(fov.toDouble()).toFloat()
        val scale = (screenWidth / 2) / tan(fovRad / 2)

        val screenX = (x1 / z2) * scale + screenWidth / 2
        val screenY = screenHeight / 2 - (y1 / z2) * scale

        return Offset(screenX, screenY)
    }
}
