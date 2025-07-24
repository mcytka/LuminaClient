package com.project.lumina.client.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.project.lumina.client.game.entity.Entity
import com.project.lumina.client.game.entity.Item
import com.project.lumina.client.game.entity.Player
import org.cloudburstmc.math.vector.Vector3f
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import androidx.compose.ui.geometry.Offset
import android.util.Log

// Data-класс для передачи всех необходимых данных в View
data class ESPData(
    val playerPosition: Vector3f,
    val playerRotation: Vector3f,
    val entities: List<Entity>,
    val fov: Float
)

class CustomESPView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var espData: ESPData? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        setBackgroundColor(AndroidColor.TRANSPARENT)
    }

    fun updateESPData(data: ESPData) {
        this.espData = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val data = espData ?: return

        val playerPosition = data.playerPosition
        val playerRotation = data.playerRotation
        val entities = data.entities
        val fov = data.fov

        val screenWidth = width.toFloat()
        val screenHeight = height.toFloat()

        entities.forEach { entity ->
            val screenPos = worldToScreen(
                entity = entity,
                playerPos = playerPosition,
                playerYaw = playerRotation.y,
                playerPitch = playerRotation.x,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                fov = fov
            )

            screenPos?.let {
                val distance = sqrt(
                    (entity.posX - playerPosition.x).pow(2) +
                    (entity.posY - playerPosition.y).pow(2) +
                    (entity.posZ - playerPosition.z).pow(2)
                ).toFloat()

                val (entityWidth, entityHeight) = getEntitySize(entity)
                val color = getEntityColor(entity)

                drawEntityESP(
                    canvas = canvas,
                    position = it,
                    distance = distance,
                    entityWidth = entityWidth,
                    entityHeight = entityHeight,
                    color = color
                )
            }
        }
    }

    private fun getEntitySize(entity: Entity): Pair<Float, Float> {
        return when {
            entity is Player -> Pair(0.6f, 1.8f) // Ширина 0.6, высота 1.8
            entity is Item -> Pair(0.25f, 0.25f)
            else -> Pair(0.5f, 0.5f)
        }
    }

    private fun getEntityColor(entity: Entity): Int {
        return when {
            entity is Player -> AndroidColor.RED
            entity is Item -> AndroidColor.YELLOW
            else -> AndroidColor.CYAN
        }
    }

    private fun worldToScreen(
        entity: Entity,
        playerPos: Vector3f,
        playerYaw: Float,
        playerPitch: Float,
        screenWidth: Float,
        screenHeight: Float,
        fov: Float
    ): Offset? {
        val cameraHeightOffset = 1.62f // Высота глаз игрока над ногами в Minecraft
        val playerCameraY = playerPos.y + cameraHeightOffset

        val (_, entityTotalHeight) = getEntitySize(entity)
        val entityCenterY = entity.vec3Position.y + (entityTotalHeight / 2)

        val dx = entity.vec3Position.x - playerPos.x
        val dy = entityCenterY - playerCameraY
        val dz = entity.vec3Position.z - playerPos.z

        // --- Начало новых изменений ---
        // Разделим углы на 100, предполагая, что они приходят в "centi-градусах".
        // Это самое распространенное масштабирование для углов в сетевых протоколах.
        val scaledPlayerYaw = playerYaw / 100.0f
        val scaledPlayerPitch = playerPitch / 100.0f
        // --- Конец новых изменений ---

        Log.d("ESPDebug", "--- worldToScreen Debug ---")
        Log.d("ESPDebug", "Player Pos: ${playerPos.x}, ${playerPos.y}, ${playerPos.z}")
        Log.d("ESPDebug", "Entity Pos: ${entity.vec3Position.x}, ${entity.vec3Position.y}, ${entity.vec3Position.z}")
        Log.d("ESPDebug", "Relative (dx, dy, dz): $dx, $dy, $dz")

        val yawRad = Math.toRadians(-scaledPlayerYaw.toDouble()).toFloat() // Используем масштабированный угол и инверсию
        val pitchRad = Math.toRadians(scaledPlayerPitch.toDouble()).toFloat() // Используем масштабированный угол

        Log.d("ESPDebug", "Player Yaw/Pitch (raw deg): $playerYaw, $playerPitch")
        Log.d("ESPDebug", "Scaled Player Yaw/Pitch (deg): $scaledPlayerYaw, $scaledPlayerPitch") // Добавленный лог
        Log.d("ESPDebug", "Yaw/Pitch (rad, after inv/scaling): $yawRad, $pitchRad")


        val x1 = dx * cos(yawRad) - dz * sin(yawRad)
        val z1 = dx * sin(yawRad) + dz * cos(yawRad)

        Log.d("ESPDebug", "After Yaw Rotation (x1, z1): $x1, $z1")

        val y1 = dy * cos(pitchRad) - z1 * sin(pitchRad)
        val z2 = dy * sin(pitchRad) + z1 * cos(pitchRad)

        Log.d("ESPDebug", "After Pitch Rotation (y1, z2): $y1, $z2")

        if (z2 < 0.1f) {
            Log.d("ESPDebug", "Entity behind camera (z2: $z2)")
            return null
        }

        val fovRad = Math.toRadians(fov.toDouble()).toFloat()
        val scale = (screenWidth / 2) / tan(fovRad / 2)

        Log.d("ESPDebug", "FOV Rad: $fovRad, Scale: $scale")

        val screenX = (x1 / z2) * scale + screenWidth / 2
        val screenY = screenHeight / 2 - (y1 / z2) * scale

        Log.d("ESPDebug", "Final Screen Coords (X, Y): $screenX, $screenY")
        Log.d("ESPDebug", "--- End worldToScreen Debug ---")

        return Offset(screenX, screenY)
    }

    private fun drawEntityESP(
        canvas: Canvas,
        position: Offset,
        distance: Float,
        entityWidth: Float,
        entityHeight: Float,
        color: Int
    ) {
        val scaleFactor = 1000f / distance.coerceAtLeast(1f)
        val screenWidthPx = (entityWidth * scaleFactor).coerceIn(10f, 100f)
        val screenHeightPx = (entityHeight * scaleFactor).coerceIn(10f, 200f)

        val rectTop = position.y - screenHeightPx / 2
        val rectBottom = position.y + screenHeightPx / 2

        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.alpha = (0.8f * 255).toInt()
        canvas.drawRect(
            position.x - screenWidthPx / 2,
            rectTop,
            position.x + screenWidthPx / 2,
            rectBottom,
            paint
        )

        paint.style = Paint.Style.FILL
        paint.alpha = (0.9f * 255).toInt()
        canvas.drawCircle(
            position.x,
            position.y,
            3f,
            paint
        )

        paint.color = AndroidColor.WHITE
        paint.textSize = 30f
        paint.textAlign = Paint.Align.CENTER
        paint.alpha = 255
        canvas.drawText(
            "%.1fm".format(distance),
            position.x,
            rectTop - 15,
            paint
        )
    }
}
