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
// import kotlin.math.tan // Больше не нужен
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
    val entities: List<ESPRenderEntity>,
    val fov: Float // FOV остается здесь, но не используется для расчета масштаба в worldToScreen
)

class CustomESPView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) { // ИСПРАВЛЕНИЕ: здесь нет дублирования типов

    private var espData: ESPData? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG) // Исправлена опечатка

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
        // val fov = data.fov // FOV больше не используется напрямую в onDraw для передачи в worldToScreen

        val screenWidth = width.toFloat()
        val screenHeight = height.toFloat()

        entities.forEach { renderEntity ->
            val entity = renderEntity.entity
            val username = renderEntity.username

            val screenPos = worldToScreen(
                entity = entity,
                playerPos = playerPosition,
                playerYaw = playerRotation.y,
                playerPitch = playerRotation.x,
                screenWidth = screenWidth,
                screenHeight = screenHeight
                // fov больше не передается в worldToScreen, так как он там не используется
            )

            screenPos?.let { // Проверяем, что screenPos не null (то есть сущность видна)
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
                    username = username
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
        screenHeight: Float
        // FOV удален из сигнатуры, так как он не используется для расчетов здесь
    ): Offset? {
        val cameraHeightOffset = 1.62f // Высота глаз игрока над ногами в Minecraft
        val playerCameraY = playerPos.y + cameraHeightOffset

        val (_, entityTotalHeight) = getEntitySize(entity)
        val entityCenterY = entity.vec3Position.y + (entityTotalHeight / 2)

        // Log.d("ESPDebug", "Screen Width: $screenWidth, Screen Height: $screenHeight") // Удален
        // Log.d("ESPDebug", "--- worldToScreen Debug ---") // Удален
        // Log.d("ESPDebug", "Player Pos: ${playerPos.x}, ${playerPos.y}, ${playerPos.z}") // Удален
        // Log.d("ESPDebug", "Entity Pos: ${entity.vec3Position.x}, ${entity.vec3Position.y}, ${entity.vec3Position.z}") // Удален
        // Log.d("ESPDebug", "Relative (dx, dy, dz - original): $dx, $dy, $dz") // Удален
        // Log.d("ESPDebug", "Player Yaw/Pitch (raw deg): $playerYaw, $playerPitch") // Удален

        val dx = entity.vec3Position.x - playerPos.x
        val dy = entityCenterY - playerCameraY
        val dz = entity.vec3Position.z - playerPos.z

        val yawRad = Math.toRadians(-playerYaw.toDouble()).toFloat() // Инверсия Yaw
        val pitchRad = Math.toRadians(-playerPitch.toDouble()).toFloat() // Инверсия Pitch

        // Log.d("ESPDebug", "Yaw/Pitch (rad, WITH inv): $yawRad, $pitchRad") // Удален

        val x1 = dx * cos(yawRad) - dz * sin(yawRad)
        val z1 = dx * sin(yawRad) + dz * cos(yawRad)

        // Log.d("ESPDebug", "After Yaw Rotation (x1, z1): $x1, $z1") // Удален

        val y1 = dy * cos(pitchRad) - z1 * sin(pitchRad)
        val z2 = dy * sin(pitchRad) + z1 * cos(pitchRad)

        // Log.d("ESPDebug", "After Pitch Rotation (y1, z2): $y1, z2: $z2") // Удален

        if (z2 < 0.2f) { // Увеличиваем clipping plane немного, чтобы избежать артефактов
            Log.d("ESPDebug", "Entity behind camera (z2: $z2) or too close to clipping plane")
            return null
        }

        // *** ВОЗВРАЩЕНИЕ к УТОЧНЕННЫМ Эмпирическим коэффициентам масштаба ***
        val adjustedXScale = 265f 
        val adjustedYScale = 35.0f 
        // ***************************************************
        
        val screenX = (-x1 / z2) * adjustedXScale + screenWidth / 2 
        val screenY = screenHeight / 2 - (y1 / z2) * adjustedYScale

        // *** Проверка нахождения на экране после преобразования ***
        val margin = 100f 
        if (screenX < -margin || screenX > screenWidth + margin ||
            screenY < -margin || screenY > screenHeight + margin) {
            Log.d("ESPDebug", "Entity out of screen bounds after projection: X=$screenX, Y=$screenY")
            return null
        }

        Log.d("ESPDebug", "Final Screen Coords (X, Y): $screenX, $screenY")
        // Log.d("ESPDebug", "--- End worldToScreen Debug ---") // Удален

        return Offset(screenX, screenY)
    }

    private fun drawEntityESP(
        canvas: Canvas,
        position: Offset,
        distance: Float,
        entityWidth: Float,
        entityHeight: Float,
        color: Int,
        username: String?
    ) {
        val baseScale = 1200f 
        val scaleFactor = baseScale / distance.coerceAtLeast(1f) 
        val screenWidthPx = (entityWidth * scaleFactor).coerceIn(20f, 150f) 
        val screenHeightPx = (entityHeight * scaleFactor).coerceIn(40f, 300f)

        val rectTop = position.y - screenHeightPx / 2
        val rectBottom = position.y + screenHeightPx / 2

        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f 
        paint.alpha = (0.9f * 255).toInt() 
        canvas.drawRect(
            position.x - screenWidthPx / 2,
            rectTop,
            position.x + screenWidthPx / 2,
            rectBottom,
            paint
        )

        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = AndroidColor.WHITE
        canvas.drawCircle(
            position.x,
            position.y,
            5f, 
            paint
        )

        username?.let {
            paint.color = AndroidColor.WHITE
            paint.textSize = 35f 
            paint.textAlign = Paint.Align.CENTER
            paint.alpha = 255
            canvas.drawText(
                it, 
                position.x,
                rectTop - 45, 
                paint
            )
        }

        paint.color = AndroidColor.WHITE
        paint.textSize = 35f 
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
