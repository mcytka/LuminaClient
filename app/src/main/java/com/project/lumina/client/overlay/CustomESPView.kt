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

// Новый Data-класс для сущностей, которые будут отрисовываться
data class ESPRenderEntity(
    val entity: Entity,
    val username: String? // Nullable, так как не у всех Entity есть username
)

// Data-класс для передачи всех необходимых данных в View
data class ESPData(
    val playerPosition: Vector3f,
    val playerRotation: Vector3f,
    val entities: List<ESPRenderEntity>, // <<-- ИЗМЕНЕНО
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
        val entities = data.entities // Теперь это List<ESPRenderEntity>
        val fov = data.fov

        val screenWidth = width.toFloat()
        val screenHeight = height.toFloat()

        entities.forEach { renderEntity -> // <<-- ИЗМЕНЕНО: используем renderEntity
            val entity = renderEntity.entity // Получаем саму Entity
            val username = renderEntity.username // Получаем username

            val screenPos = worldToScreen(
                entity = entity, // Передаем Entity
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
                    color = color,
                    username = username // <<-- ПЕРЕДАЕМ USERNAME
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
        fov: Float // Это теперь будет интерпретироваться как ВЕРТИКАЛЬНЫЙ FOV
    ): Offset? {
        val cameraHeightOffset = 1.62f // Высота глаз игрока над ногами в Minecraft
        val playerCameraY = playerPos.y + cameraHeightOffset

        val (_, entityTotalHeight) = getEntitySize(entity)
        val entityCenterY = entity.vec3Position.y + (entityTotalHeight / 2)

        Log.d("ESPDebug", "Screen Width: $screenWidth, Screen Height: $screenHeight") // <-- Лог размера экрана

        val dx = entity.vec3Position.x - playerPos.x
        val dy = entityCenterY - playerCameraY
        val dz = entity.vec3Position.z - playerPos.z // Возвращаем оригинальное вычисление dz

        Log.d("ESPDebug", "--- worldToScreen Debug ---")
        Log.d("ESPDebug", "Player Pos: ${playerPos.x}, ${playerPos.y}, ${playerPos.z}")
        Log.d("ESPDebug", "Entity Pos: ${entity.vec3Position.x}, ${entity.vec3Position.y}, ${entity.vec3Position.z}")
        Log.d("ESPDebug", "Relative (dx, dy, dz - original): $dx, $dy, $dz")

        Log.d("ESPDebug", "Player Yaw/Pitch (raw deg): $playerYaw, $playerPitch")

        val yawRad = Math.toRadians(-playerYaw.toDouble()).toFloat()
        val pitchRad = Math.toRadians(-playerPitch.toDouble()).toFloat()

        Log.d("ESPDebug", "Yaw/Pitch (rad, yaw inv, pitch inv): $yawRad, $pitchRad")

        val x1 = dx * cos(yawRad) - dz * sin(yawRad)
        val z1 = dx * sin(yawRad) + dz * cos(yawRad)

        Log.d("ESPDebug", "After Yaw Rotation (x1, z1): $x1, $z1")

        val y1 = dy * cos(pitchRad) - z1 * sin(pitchRad)
        val z2 = dy * sin(pitchRad) + z1 * cos(pitchRad)

        Log.d("ESPDebug", "After Pitch Rotation (y1, z2): $y1, $z2")

        if (z2 < 0.1f) {
            Log.d("ESPDebug", "Entity behind camera (z2: $z2) or too close to clipping plane")
            return null
        }

        val fovRad = Math.toRadians(fov.toDouble()).toFloat()
        
        // *** ГЛАВНОЕ ИЗМЕНЕНИЕ: Расчет scale на основе ВЕРТИКАЛЬНОГО FOV и ВЫСОТЫ ЭКРАНА ***
        val scale = (screenHeight / 2) / tan(fovRad / 2) // <-- ИЗМЕНЕНО!

        val aspectRatio = screenWidth / screenHeight // Расчет аспектного соотношения
        
        Log.d("ESPDebug", "Debug Scale Calculation: fov: $fov, fovRad: $fovRad, tanHalfFov: ${tan(fovRad / 2)}, screenHeight/2: ${screenHeight / 2}, calculated Scale: $scale")
        Log.d("ESPDebug", "FOV Rad: $fovRad, Scale: $scale, Aspect Ratio: $aspectRatio") // Добавили логирование Aspect Ratio

        // *** ИЗМЕНЕНИЕ: Применение аспектного соотношения к screenX ***
        val screenX = (x1 / z2) * scale * aspectRatio + screenWidth / 2 // <-- ИЗМЕНЕНО!
        val screenY = screenHeight / 2 - (y1 / z2) * scale // Y остается без изменения, т.к. scale уже по Y

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
        color: Int,
        username: String? // <<-- НОВЫЙ ПАРАМЕТР
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

        // Отрисовка никнейма
        username?.let {
            paint.color = AndroidColor.WHITE
            paint.textSize = 30f // Размер текста для никнейма
            paint.textAlign = Paint.Align.CENTER
            paint.alpha = 255
            canvas.drawText(
                it, // Сам никнейм
                position.x,
                rectTop - 45, // Располагаем никнейм над расстоянием
                paint
            )
        }


        // Отрисовка расстояния
        paint.color = AndroidColor.WHITE
        paint.textSize = 30f
        paint.textAlign = Paint.Align.CENTER
        paint.alpha = 255
        canvas.drawText(
            "%.1fm".format(distance),
            position.x,
            rectTop - 15, // Располагаем текст над прямоугольником
            paint
        )
    }
}
