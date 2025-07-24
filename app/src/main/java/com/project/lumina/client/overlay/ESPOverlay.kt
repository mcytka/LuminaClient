package com.project.lumina.client.overlay

import android.graphics.Paint
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import com.project.lumina.client.game.entity.Entity
import com.project.lumina.client.game.entity.Item
import com.project.lumina.client.game.entity.MobList
import com.project.lumina.client.game.entity.Player
import org.cloudburstmc.math.vector.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.pow

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
        
        val config = LocalConfiguration.current
        val screenWidth = config.screenWidthDp
        val screenHeight = config.screenHeightDp

        Canvas(modifier = Modifier.fillMaxSize()) {
            entities.forEach { entity ->
                val screenPos = worldToScreen(
                    entityPos = entity.vec3Position,
                    playerPos = playerPosition,
                    playerYaw = playerRotation.y,
                    playerPitch = playerRotation.x,
                    screenWidth = size.width,
                    screenHeight = size.height,
                    fov = fov
                )
                
                screenPos?.let {
                    val distance = sqrt(
                        (entity.posX - playerPosition.x).pow(2) +
                        (entity.posY - playerPosition.y).pow(2) +
                        (entity.posZ - playerPosition.z).pow(2)
                    ).toFloat()
                    
                    // Определяем размер и цвет сущности
                    val (entityWidth, entityHeight) = getEntitySize(entity)
                    val color = getEntityColor(entity)
                    
                    drawEntityESP(
                        position = it,
                        distance = distance,
                        entityWidth = entityWidth,
                        entityHeight = entityHeight,
                        color = color
                    )
                }
            }
        }
    }

    // Определение размера сущности на основе ее типа
    private fun getEntitySize(entity: Entity): Pair<Float, Float> {
        return when {
            entity is Player -> Pair(0.6f, 1.8f) // Ширина 0.6, высота 1.8
            entity is Item -> Pair(0.25f, 0.25f)  // Для предметов
            else -> Pair(0.5f, 0.5f)              // Для других сущностей
        }
    }

    // Определение цвета сущности на основе ее типа
    private fun getEntityColor(entity: Entity): Color {
        return when {
            entity is Player -> Color.Red
            entity is Item -> Color.Yellow
            else -> Color.Cyan
        }
    }

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

    @Suppress("FunctionName")
    private fun Canvas.drawEntityESP(
        position: Offset,
        distance: Float,
        entityWidth: Float,
        entityHeight: Float,
        color: Color
    ) {
        // Коэффициент масштабирования в зависимости от расстояния
        val scaleFactor = 1000f / distance.coerceAtLeast(1f)
        
        // Размер на экране (сохраняем пропорции)
        val screenWidth = (entityWidth * scaleFactor).coerceIn(10f, 100f)
        val screenHeight = (entityHeight * scaleFactor).coerceIn(10f, 200f)
        
        // Рисуем контурный прямоугольник (хитбокс)
        drawRect(
            color = color,
            topLeft = Offset(position.x - screenWidth/2, position.y - screenHeight/2),
            size = Size(screenWidth, screenHeight),
            style = Stroke(width = 2f),
            alpha = 0.8f
        )
        
        // Рисуем центральную точку
        drawCircle(
            color = color,
            radius = 3f,
            center = position,
            alpha = 0.9f
        )
        
        // Отображаем расстояние
        val paint = Paint().apply {
            this.color = android.graphics.Color.WHITE
            textSize = 30f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        
        drawContext.canvas.nativeCanvas.drawText(
            "%.1fm".format(distance),
            position.x,
            position.y - screenHeight/2 - 15,
            paint
        )
    }
}
