//CustomESPView.kt
package com.project.lumina.client.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.project.lumina.client.game.entity.Entity
import com.project.lumina.client.game.entity.Item
import com.project.lumina.client.game.entity.Player
import com.project.lumina.client.game.entity.LocalPlayer
import org.cloudburstmc.math.matrix.Matrix4f
import org.cloudburstmc.math.vector.Vector2f
import org.cloudburstmc.math.vector.Vector3f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow
import android.util.Log

data class ESPRenderEntity(
    val entity: Entity,
    val username: String?
)

data class ESPData(
    val playerPosition: Vector3f,
    val playerRotation: Vector3f, // rotation.x = pitch, rotation.y = yaw
    val entities: List<ESPRenderEntity>,
    val fov: Float,
    val use3dBoxes: Boolean // <<< ДОБАВЛЕНО: Флаг для выбора 2D/3D
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

    @SuppressLint("DefaultLocale") // Для форматирования дистанции
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val data = espData ?: return

        val playerPosition = data.playerPosition
        val playerYaw = data.playerRotation.y
        val playerPitch = data.playerRotation.x
        val entities = data.entities
        val fov = data.fov

        val screenWidth = width.toFloat()
        val screenHeight = height.toFloat()

        // --- Создание матрицы вида-проекции ---
        // Матрица перспективы (fov, aspectRatio, nearPlane, farPlane)
        val viewProjMatrix = Matrix4f.createPerspective(
            fov, // FOV в градусах
            screenWidth / screenHeight, // Соотношение сторон
            0.1f, // Ближняя плоскость отсечения
            128f // Дальняя плоскость отсечения (можете настроить)
        )
        .mul(
            // Матрица вида (инвертированная матрица камеры)
            Matrix4f.createTranslation(playerPosition) // Перемещение камеры к игроку
                .mul(rotateY(-playerYaw - 180f)) // Поворот камеры по Yaw (рысканье). Добавляем -180 для соответствия ориентации.
                .mul(rotateX(-playerPitch)) // Поворот камеры по Pitch (тангаж)
                .invert() // Инвертируем, чтобы получить матрицу вида
        )
        // --- Конец создания матрицы ---

        entities.forEach { renderEntity ->
            val entity = renderEntity.entity
            val username = renderEntity.username

            // Определяем размеры сущности
            val (entityWidth, entityHeight) = getEntitySize(entity)

            val entityCenterX = entity.vec3Position.x
            val entityCenterZ = entity.vec3Position.z

            // Предполагаем, что entity.vec3Position.y - это уровень глаз (или верхней части тела) сущности.
            // Вычитаем 1.62f (стандартную высоту глаз над ногами в Minecraft), чтобы получить уровень ног.
            val entityFeetY = entity.vec3Position.y - 1.62f
            // Голова теперь отсчитывается от уровня ног, добавляя полную высоту сущности.
            val entityHeadY = entityFeetY + entityHeight


            // Проектируем 8 вершин bounding box'а сущности
            val halfWidth = entityWidth / 2f
            val halfDepth = entityWidth / 2f // Для MC обычно ширина = глубина

            // Вершины bounding box'а в мировых координатах
            val boxVertices = arrayOf(
                Vector3f.from(entityCenterX - halfWidth, entityFeetY, entityCenterZ - halfDepth),          // 0: Bottom front left
                Vector3f.from(entityCenterX - halfWidth, entityHeadY, entityCenterZ - halfDepth),          // 1: Top front left
                Vector3f.from(entityCenterX + halfWidth, entityHeadY, entityCenterZ - halfDepth),          // 2: Top front right
                Vector3f.from(entityCenterX + halfWidth, entityFeetY, entityCenterZ - halfDepth),          // 3: Bottom front right
                Vector3f.from(entityCenterX - halfWidth, entityFeetY, entityCenterZ + halfDepth),          // 4: Bottom back left
                Vector3f.from(entityCenterX - halfWidth, entityHeadY, entityCenterZ + halfDepth),          // 5: Top back left
                Vector3f.from(entityCenterX + halfWidth, entityHeadY, entityCenterZ + halfDepth),          // 6: Top back right
                Vector3f.from(entityCenterX + halfWidth, entityFeetY, entityCenterZ + halfDepth)           // 7: Bottom back right
            )

            var minX_screen = screenWidth
            var minY_screen = screenHeight
            var maxX_screen = 0f
            var maxY_screen = 0f
            var anyVertexBehindCamera = false // Флаг для отслеживания вершин за камерой

            // Проецируем каждую вершину
            val screenPositions = boxVertices.mapNotNull { vertex ->
                val screenPos = worldToScreen(vertex, viewProjMatrix, screenWidth.toInt(), screenHeight.toInt())
                if (screenPos == null) {
                    anyVertexBehindCamera = true // Если хотя бы одна вершина не проецируется (за камерой)
                }
                screenPos
            }

            // Если хотя бы одна вершина за камерой, пропускаем сущность
            if (anyVertexBehindCamera) {
                return@forEach
            }

            // Если все вершины проецировались, но список пуст (что маловероятно), тоже пропускаем
            if (screenPositions.isEmpty()) {
                return@forEach
            }

            // Вычисляем минимальные/максимальные X/Y на экране для 2D-бокса (все еще нужно для текста)
            screenPositions.forEach { screenPos ->
                minX_screen = minX_screen.coerceAtMost(screenPos.x)
                minY_screen = minY_screen.coerceAtMost(screenPos.y)
                maxX_screen = maxX_screen.coerceAtLeast(screenPos.x)
                maxY_screen = maxY_screen.coerceAtLeast(screenPos.y)
            }

            // Проверяем, находится ли бокс полностью вне экрана (с небольшим запасом)
            val margin = 10f // Маленький отступ, чтобы бокс исчезал сразу за краем
            if (maxX_screen <= -margin ||
                minX_screen >= screenWidth + margin ||
                maxY_screen <= -margin ||
                minY_screen >= screenHeight + margin) {
                return@forEach
            }

            // Если бокс виден, отрисовываем его
            val distance = sqrt(
                (entity.posX - playerPosition.x).pow(2) +
                (entity.posY - playerPosition.y).pow(2) +
                (entity.posZ - playerPosition.z).pow(2)
            ).toFloat()

            val color = getEntityColor(entity)
            paint.color = color // Устанавливаем цвет для бокса

            // >>> ИЗМЕНЕНИЯ ЗДЕСЬ: Выбор между 2D и 3D боксом <<<
            if (data.use3dBoxes) {
                draw3DBox(canvas, paint, screenPositions)
            } else {
                draw2DBox(canvas, paint, minX_screen, minY_screen, maxX_screen, maxY_screen, color)
            }
            // >>> КОНЕЦ ИЗМЕНЕНИЙ <<<

            // Рисуем информацию о сущности (имя и дистанция)
            if (username != null || distance > 0) {
                drawEntityInfo(canvas, paint, username, distance, minX_screen, minY_screen, maxX_screen)
            }
        }
    }

    // --- Вспомогательные функции для матриц ---
    private fun rotateX(angle: Float): Matrix4f {
        val rad = Math.toRadians(angle.toDouble()).toFloat()
        val c = cos(rad)
        val s = sin(rad)

        return Matrix4f.from(
            1f, 0f, 0f, 0f,
            0f, c, -s, 0f,
            0f, s, c, 0f,
            0f, 0f, 0f, 1f
        )
    }

    private fun rotateY(angle: Float): Matrix4f {
        val rad = Math.toRadians(angle.toDouble()).toFloat()
        val c = cos(rad)
        val s = sin(rad)

        return Matrix4f.from(
            c, 0f, s, 0f,
            0f, 1f, 0f, 0f,
            -s, 0f, c, 0f,
            0f, 0f, 0f, 1f
        )
    }
    // --- Конец вспомогательных функций ---

    // worldToScreen теперь использует матрицу
    private fun worldToScreen(pos: Vector3f, viewProjMatrix: Matrix4f, screenWidth: Int, screenHeight: Int): Vector2f? {
        // Проекция точки в Clip Space
        val clipX = viewProjMatrix.get(0, 0) * pos.x + viewProjMatrix.get(0, 1) * pos.y + viewProjMatrix.get(0, 2) * pos.z + viewProjMatrix.get(0, 3)
        val clipY = viewProjMatrix.get(1, 0) * pos.x + viewProjMatrix.get(1, 1) * pos.y + viewProjMatrix.get(1, 2) * pos.z + viewProjMatrix.get(1, 3)
        val clipZ = viewProjMatrix.get(2, 0) * pos.x + viewProjMatrix.get(2, 1) * pos.y + viewProjMatrix.get(2, 2) * pos.z + viewProjMatrix.get(2, 3)
        val clipW = viewProjMatrix.get(3, 0) * pos.x + viewProjMatrix.get(3, 1) * pos.y + viewProjMatrix.get(3, 2) * pos.z + viewProjMatrix.get(3, 3)

        // Отсечение по W: если W < 0.1f (или другое малое положительное число),
        // значит точка находится за ближней плоскостью отсечения или позади камеры.
        if (clipW < 0.1f) return null // Проверяем порог для W.

        val inverseW = 1f / clipW

        // Преобразование из Clip Space в Normalized Device Coordinates (NDC)
        val ndcX = clipX * inverseW
        val ndcY = clipY * inverseW
        val ndcZ = clipZ * inverseW // z-координата также может быть полезна для отладки

        // Преобразование из NDC в Screen Coordinates
        val screenX = screenWidth / 2f + (0.5f * ndcX * screenWidth)
        val screenY = screenHeight / 2f - (0.5f * ndcY * screenHeight) // Инвертируем Y, так как в Android Y увеличивается вниз

        return Vector2f.from(screenX, screenY)
    }

    private fun getEntitySize(entity: Entity): Pair<Float, Float> {
        return when (entity) {
            is Player, is LocalPlayer -> Pair(0.6f, 1.8f) // Стандартная ширина и высота игрока
            is Item -> Pair(0.25f, 0.25f) // Пример для предметов
            else -> Pair(0.5f, 0.5f) // Дефолтные размеры для других сущностей
        }
    }

    private fun getEntityColor(entity: Entity): Int {
        return when {
            entity is Player -> AndroidColor.RED
            entity is Item -> AndroidColor.YELLOW
            else -> AndroidColor.CYAN
        }
    }

    // Раскомментированная функция для отрисовки 2D-бокса
    private fun draw2DBox(canvas: Canvas, paint: Paint, minX: Float, minY: Float, maxX: Float, maxY: Float, color: Int) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.alpha = (0.9f * 255).toInt()
        canvas.drawRect(minX, minY, maxX, maxY, paint)
    }

    // Новая функция для отрисовки 3D-бокса (каркаса)
    private fun draw3DBox(canvas: Canvas, paint: Paint, screenPositions: List<Vector2f>) {
        if (screenPositions.size != 8) {
            // Если не 8 спроецированных вершин, значит что-то пошло не так, не рисуем
            return
        }

        // Устанавливаем стиль для рисования линий бокса
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.alpha = (0.9f * 255).toInt() // Прозрачность

        // Определяем ребра куба по индексам вершин (согласно порядку в boxVertices)
        // Нижняя плоскость: 0-3-7-4-0
        drawLine(canvas, paint, screenPositions[0], screenPositions[3])
        drawLine(canvas, paint, screenPositions[3], screenPositions[7])
        drawLine(canvas, paint, screenPositions[7], screenPositions[4])
        drawLine(canvas, paint, screenPositions[4], screenPositions[0])

        // Верхняя плоскость: 1-2-6-5-1
        drawLine(canvas, paint, screenPositions[1], screenPositions[2])
        drawLine(canvas, paint, screenPositions[2], screenPositions[6])
        drawLine(canvas, paint, screenPositions[6], screenPositions[5])
        drawLine(canvas, paint, screenPositions[5], screenPositions[1])

        // Вертикальные ребра (соединяющие верх и низ)
        drawLine(canvas, paint, screenPositions[0], screenPositions[1]) // 0-1
        drawLine(canvas, paint, screenPositions[3], screenPositions[2]) // 3-2
        drawLine(canvas, paint, screenPositions[7], screenPositions[6]) // 7-6
        drawLine(canvas, paint, screenPositions[4], screenPositions[5]) // 4-5
    }

    // Вспомогательная функция для рисования линии между двумя Vector2f
    private fun drawLine(canvas: Canvas, paint: Paint, p1: Vector2f, p2: Vector2f) {
        canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
    }


    // Функция для отрисовки имени и дистанции над боксом
    @SuppressLint("DefaultLocale")
    private fun drawEntityInfo(canvas: Canvas, paint: Paint, username: String?, distance: Float, minX: Float, minY: Float, maxX: Float) {
        // Background paint for text
        val bgPaint = Paint().apply {
            color = AndroidColor.argb(160, 0, 0, 0) // Semi-transparent black background
            style = Paint.Style.FILL
        }

        // Outline paint
        val outlinePaint = Paint().apply {
            color = AndroidColor.BLACK
            textSize = 30f
            textAlign = Paint.Align.CENTER
            style = Paint.Style.STROKE
            strokeWidth = 4f // Thick outline
        }

        val textPaint = Paint().apply {
            color = AndroidColor.WHITE // Цвет текста.
            textSize = 30f
            textAlign = Paint.Align.CENTER
            style = Paint.Style.FILL
        }

        val info = buildString {
            if (username != null) {
                append(username)
            }
            // Всегда показываем дистанцию, если она больше 0
            if (distance > 0) {
                if (isNotEmpty()) append(" | ")
                append("%.1fm".format(distance))
            }
        }

        // Если нет информации для отображения, выходим
        if (info.isEmpty()) return

        val textX = (minX + maxX) / 2 // Используем minX_screen и maxX_screen для центрирования
        val textY = minY - 10 // Над верхней частью бокса

        val bounds = android.graphics.Rect()
        textPaint.getTextBounds(info, 0, info.length, bounds)

        val padding = 8f
        val bgRect = android.graphics.RectF(
            textX - bounds.width() / 2 - padding,
            textY - bounds.height() - padding,
            textX + bounds.width() / 2 + padding,
            textY + padding
        )
        canvas.drawRoundRect(bgRect, 4f, 4f, bgPaint)

        // Отрисовка текста с обводкой и заливкой
        canvas.drawText(info, textX, textY, outlinePaint)
        canvas.drawText(info, textX, textY, textPaint)
    }
}
