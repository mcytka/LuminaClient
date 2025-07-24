package com.project.lumina.client.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor // Переименовываем, чтобы избежать конфликта с Compose Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.project.lumina.client.game.entity.Entity
import com.project.lumina.client.game.entity.Item
import com.project.lumina.client.game.entity.Player
import org.cloudburstmc.math.vector.Vector3f
// Добавляем импорты kotlin.math для функций, используемых здесь
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import androidx.compose.ui.geometry.Offset // Используем Compose Offset, так как он удобен для координат

// Data-класс для передачи всех необходимых данных в View
data class ESPData(
    val playerPosition: Vector3f,
    val playerRotation: Vector3f,
    val entities: List<Entity>,
    val fov: Float
)

// Пользовательский View для отрисовки ESP
class CustomESPView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var espData: ESPData? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG) // Создаем Paint один раз

    init {
        // Очень важно для View, который сам рисует
        setWillNotDraw(false)
        // Устанавливаем прозрачный фон для View, чтобы он не перекрывал другие элементы
        setBackgroundColor(AndroidColor.TRANSPARENT)
    }

    // Метод для обновления данных извне (из Compose)
    fun updateESPData(data: ESPData) {
        this.espData = data
        invalidate() // Запрашиваем перерисовку View
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val data = espData ?: return // Если данных нет, ничего не рисуем

        val playerPosition = data.playerPosition
        val playerRotation = data.playerRotation
        val entities = data.entities
        val fov = data.fov

        // Ширина и высота самого View для расчетов
        val screenWidth = width.toFloat()
        val screenHeight = height.toFloat()

        entities.forEach { entity ->
            val screenPos = worldToScreen(
                entityPos = entity.vec3Position,
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
                val color = getEntityColor(entity) // Теперь возвращает Int

                drawEntityESP(
                    canvas = canvas, // Передаем нативный Canvas
                    position = it,
                    distance = distance,
                    entityWidth = entityWidth,
                    entityHeight = entityHeight,
                    color = color
                )
            }
        }
    }

    // Определение размера сущности на основе ее типа (скопировано из вашего ESPOverlay)
    private fun getEntitySize(entity: Entity): Pair<Float, Float> {
        return when {
            entity is Player -> Pair(0.6f, 1.8f) // Ширина 0.6, высота 1.8
            entity is Item -> Pair(0.25f, 0.25f)  // Для предметов
            else -> Pair(0.5f, 0.5f)              // Для других сущностей
        }
    }

    // Определение цвета сущности на основе ее типа (скопировано из вашего ESPOverlay)
    // Используем Int
    private fun getEntityColor(entity: Entity): Int { // <<-- ТИП ИЗМЕНЕН НА Int
        return when {
            entity is Player -> AndroidColor.RED
            entity is Item -> AndroidColor.YELLOW
            else -> AndroidColor.CYAN
        }
    }

    // Логика worldToScreen остается прежней (скопировано из вашего ESPOverlay)
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

    // Метод отрисовки ESP-элементов, использующий android.graphics.Canvas и Paint
    private fun drawEntityESP(
        canvas: Canvas, // Получаем Canvas для рисования
        position: Offset,
        distance: Float,
        entityWidth: Float,
        entityHeight: Float,
        color: Int // <<-- ТИП ИЗМЕНЕН НА Int
    ) {
        // Коэффициент масштабирования в зависимости от расстояния
        val scaleFactor = 1000f / distance.coerceAtLeast(1f)

        // Размер на экране (сохраняем пропорции)
        val screenWidthPx = (entityWidth * scaleFactor).coerceIn(10f, 100f)
        val screenHeightPx = (entityHeight * scaleFactor).coerceIn(10f, 200f)

        // Рисуем контурный прямоугольник (хитбокс)
        paint.color = color
        paint.style = Paint.Style.STROKE // Контурный стиль
        paint.strokeWidth = 2f
        paint.alpha = (0.8f * 255).toInt() // Alpha для Android.graphics.Paint от 0 до 255
        canvas.drawRect(
            position.x - screenWidthPx / 2,
            position.y - screenHeightPx / 2,
            position.x + screenWidthPx / 2,
            position.y + screenHeightPx / 2,
            paint
        )

        // Рисуем центральную точку
        paint.style = Paint.Style.FILL // Залитый стиль
        paint.alpha = (0.9f * 255).toInt()
        canvas.drawCircle(
            position.x,
            position.y,
            3f,
            paint
        )

        // Отображаем расстояние
        paint.color = AndroidColor.WHITE
        paint.textSize = 30f
        paint.textAlign = Paint.Align.CENTER
        paint.alpha = 255 // Полная непрозрачность для текста
        canvas.drawText(
            "%.1fm".format(distance),
            position.x,
            position.y - screenHeightPx / 2 - 15, // Располагаем текст над прямоугольником
            paint
        )
    }
}
