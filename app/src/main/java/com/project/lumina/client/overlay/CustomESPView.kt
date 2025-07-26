// CustomESPView.kt
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
    val username: String?,
    val health: Float,
    val maxHealth: Float
)

data class ESPData(
    val playerPosition: Vector3f,
    val playerRotation: Vector3f, // rotation.x = pitch, rotation.y = yaw
    val entities: List<ESPRenderEntity>,
    val fov: Float,
    val use3dBoxes: Boolean,
    val showPlayerInfo: Boolean
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

    @SuppressLint("DefaultLocale")
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

        val viewProjMatrix = Matrix4f.createPerspective(
            fov,
            screenWidth / screenHeight,
            0.1f,
            128f
        )
        .mul(
            Matrix4f.createTranslation(playerPosition)
                .mul(rotateY(-playerYaw - 180f))
                .mul(rotateX(-playerPitch))
                .invert()
        )

        entities.forEach { renderEntity ->
            val entity = renderEntity.entity
            val username = renderEntity.username
            val health = renderEntity.health
            val maxHealth = renderEntity.maxHealth

            val (entityWidth, entityHeight) = getEntitySize(entity)

            val entityCenterX = entity.vec3Position.x
            val entityCenterZ = entity.vec3Position.z

            val entityFeetY = entity.vec3Position.y - 1.62f
            val entityHeadY = entityFeetY + entityHeight

            val halfWidth = entityWidth / 2f
            val halfDepth = entityWidth / 2f

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
            var anyVertexBehindCamera = false

            val screenPositions = boxVertices.mapNotNull { vertex ->
                val screenPos = worldToScreen(vertex, viewProjMatrix, screenWidth.toInt(), screenHeight.toInt())
                if (screenPos == null) {
                    anyVertexBehindCamera = true
                }
                screenPos
            }

            if (anyVertexBehindCamera) {
                return@forEach
            }

            if (screenPositions.isEmpty()) {
                return@forEach
            }

            screenPositions.forEach { screenPos ->
                minX_screen = minX_screen.coerceAtMost(screenPos.x)
                minY_screen = minY_screen.coerceAtMost(screenPos.y)
                maxX_screen = maxX_screen.coerceAtLeast(screenPos.x)
                maxY_screen = maxY_screen.coerceAtLeast(screenPos.y)
            }

            val margin = 10f
            if (maxX_screen <= -margin ||
                minX_screen >= screenWidth + margin ||
                maxY_screen <= -margin ||
                minY_screen >= screenHeight + margin) {
                return@forEach
            }

            val distance = sqrt(
                (entity.posX - playerPosition.x).pow(2) +
                (entity.posY - playerPosition.y).pow(2) +
                (entity.posZ - playerPosition.z).pow(2)
            ).toFloat()

            val color = getEntityColor(entity)
            paint.color = color

            if (data.use3dBoxes) {
                draw3DBox(canvas, paint, screenPositions)
            } else {
                draw2DBox(canvas, paint, minX_screen, minY_screen, maxX_screen, maxY_screen, color)
            }

            if (data.showPlayerInfo) {
                if (username != null || distance > 0 || health > 0) {
                    drawEntityInfo(
                        canvas,
                        paint,
                        username,
                        distance,
                        minX_screen,
                        minY_screen,
                        maxX_screen,
                        maxY_screen, // <<< ДОБАВЛЕНО: передача maxY_screen
                        health,
                        maxHealth
                    )
                }
            }
        }
    }

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

    private fun worldToScreen(pos: Vector3f, viewProjMatrix: Matrix4f, screenWidth: Int, screenHeight: Int): Vector2f? {
        val clipX = viewProjMatrix.get(0, 0) * pos.x + viewProjMatrix.get(0, 1) * pos.y + viewProjMatrix.get(0, 2) * pos.z + viewProjMatrix.get(0, 3)
        val clipY = viewProjMatrix.get(1, 0) * pos.x + viewProjMatrix.get(1, 1) * pos.y + viewProjMatrix.get(1, 2) * pos.z + viewProjMatrix.get(1, 3)
        val clipZ = viewProjMatrix.get(2, 0) * pos.x + viewProjMatrix.get(2, 1) * pos.y + viewProjMatrix.get(2, 2) * pos.z + viewProjMatrix.get(2, 3)
        val clipW = viewProjMatrix.get(3, 0) * pos.x + viewProjMatrix.get(3, 1) * pos.y + viewProjMatrix.get(3, 2) * pos.z + viewProjMatrix.get(3, 3)

        if (clipW < 0.1f) return null

        val inverseW = 1f / clipW
        val ndcX = clipX * inverseW
        val ndcY = clipY * inverseW
        val screenX = screenWidth / 2f + (0.5f * ndcX * screenWidth)
        val screenY = screenHeight / 2f - (0.5f * ndcY * screenHeight)
        return Vector2f.from(screenX, screenY)
    }

    private fun getEntitySize(entity: Entity): Pair<Float, Float> {
        return when (entity) {
            is Player, is LocalPlayer -> Pair(0.6f, 1.8f)
            is Item -> Pair(0.25f, 0.25f)
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

    private fun draw2DBox(canvas: Canvas, paint: Paint, minX: Float, minY: Float, maxX: Float, maxY: Float, color: Int) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.alpha = (0.9f * 255).toInt()
        canvas.drawRect(minX, minY, maxX, maxY, paint)
    }

    private fun draw3DBox(canvas: Canvas, paint: Paint, screenPositions: List<Vector2f>) {
        if (screenPositions.size != 8) {
            return
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.alpha = (0.9f * 255).toInt()

        drawLine(canvas, paint, screenPositions[0], screenPositions[3])
        drawLine(canvas, paint, screenPositions[3], screenPositions[7])
        drawLine(canvas, paint, screenPositions[7], screenPositions[4])
        drawLine(canvas, paint, screenPositions[4], screenPositions[0])

        drawLine(canvas, paint, screenPositions[1], screenPositions[2])
        drawLine(canvas, paint, screenPositions[2], screenPositions[6])
        drawLine(canvas, paint, screenPositions[6], screenPositions[5])
        drawLine(canvas, paint, screenPositions[5], screenPositions[1])

        drawLine(canvas, paint, screenPositions[0], screenPositions[1])
        drawLine(canvas, paint, screenPositions[3], screenPositions[2])
        drawLine(canvas, paint, screenPositions[7], screenPositions[6])
        drawLine(canvas, paint, screenPositions[4], screenPositions[5])
    }

    private fun drawLine(canvas: Canvas, paint: Paint, p1: Vector2f, p2: Vector2f) {
        canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
    }

    @SuppressLint("DefaultLocale")
    private fun drawEntityInfo(
        canvas: Canvas,
        paint: Paint,
        username: String?,
        distance: Float,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,     // <<< ДОБАВЛЕННЫЙ ПАРАМЕТР
        health: Float,
        maxHealth: Float
    ) {
        val bgPaint = Paint().apply {
            color = AndroidColor.argb(160, 0, 0, 0)
            style = Paint.Style.FILL
        }

        val outlinePaint = Paint().apply {
            color = AndroidColor.BLACK
            textSize = 30f
            textAlign = Paint.Align.CENTER
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        val textPaint = Paint().apply {
            color = AndroidColor.WHITE
            textSize = 30f
            textAlign = Paint.Align.CENTER
            style = Paint.Style.FILL
        }

        val info = buildString {
            if (username != null) {
                append(username)
            }
            if (distance > 0) {
                if (isNotEmpty()) append(" | ")
                append("%.1fm".format(distance))
            }
            if (health > 0) {
                if (isNotEmpty()) append(" | ")
                append("HP: ${health.toInt()}/${maxHealth.toInt()}")
            }
        }

        if (info.isEmpty()) return

        val textX = (minX + maxX) / 2
        val textY = minY - 10

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

        canvas.drawText(info, textX, textY, outlinePaint)
        canvas.drawText(info, textX, textY, textPaint)

        // Отрисовка полоски здоровья под боксом
        if (health > 0 && maxHealth > 0) {
            val healthBarHeight = 5f
            val healthBarY = maxY + 5f // <<< ИСПОЛЬЗУЕМ ПЕРЕДАННЫЙ maxY
            val healthRatio = health / maxHealth
            val healthBarWidth = maxX - minX

            paint.color = AndroidColor.argb(150, 50, 50, 50)
            paint.style = Paint.Style.FILL
            canvas.drawRect(minX, healthBarY, maxX, healthBarY + healthBarHeight, paint)

            paint.color = AndroidColor.GREEN
            canvas.drawRect(minX, healthBarY, minX + healthBarWidth * healthRatio, healthBarY + healthBarHeight, paint)

            paint.color = AndroidColor.BLACK
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            canvas.drawRect(minX, healthBarY, maxX, healthBarY + healthBarHeight, paint)
        }
    }
}
