// ESPElement.kt
package com.project.lumina.client.game.module.render

import android.util.Log // <-- Добавлен импорт для логирования
import com.project.lumina.client.R
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.ui.opengl.ESPOverlayGLSurface // Предполагаемый путь
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.sqrt

class ESPElement(iconResId: Int = R.drawable.baseline_visibility_24) : Element(
    name = "ESP",
    category = CheatCategory.Render,
    iconResId = iconResId,
    displayNameResId = R.string.module_esp_display_name
) {

    private var glSurface: ESPOverlayGLSurface? = null
    private var rangeValue by floatValue("Range", 50f, 10f..100f)
    private var playersOnly by boolValue("Players Only", true)
    private var maxTargets by intValue("Max Targets", 10, 1..50)
    private var multiTarget by boolValue("Multi Target", true)

    override fun onEnabled() {
        Log.d("ESP", "onEnabled called") // <-- Логирование
        super.onEnabled()
        if (isSessionCreated) {
            glSurface = ESPOverlayGLSurface(session)
            Log.d("ESP", "GLSurface created: ${glSurface != null}") // <-- Логирование
            if (glSurface != null) {
                session.overlayManager.showCustomOverlay(glSurface!!)
                Log.d("ESP", "Overlay shown") // <-- Логирование
            }
        }
    }

    override fun onDisabled() {
        Log.d("ESP", "onDisabled called") // <-- Логирование
        super.onDisabled()
        glSurface?.let { surface ->
            session.overlayManager.removeCustomOverlay(surface)
            Log.d("ESP", "Overlay removed") // <-- Логирование
        }
        glSurface = null
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        // Log.d("ESP", "beforePacketBound called, isEnabled: $isEnabled, isSessionCreated: $isSessionCreated") // <-- Логирование (может быть слишком много)
        if (!isEnabled || !isSessionCreated) return

        val packet = interceptablePacket.packet
        if (packet is PlayerAuthInputPacket) {
            Log.d("ESP", "PlayerAuthInputPacket received") // <-- Логирование
            val position = packet.position ?: Vector3f.ZERO
            val rotationYaw = packet.rotation?.y ?: 0f
            val rotationPitch = packet.rotation?.x ?: 0f

            Log.d("ESP", "Player pos: $position, Yaw: $rotationYaw, Pitch: $rotationPitch") // <-- Логирование

            // Получаем список ближайших сущностей
            val entities = searchForClosestEntities().map { it.vec3Position }
            Log.d("ESP", "Found ${entities.size} entity positions to render") // <-- Логирование

            // Обновляем позицию игрока и список сущностей в оверлее
            glSurface?.let { surface ->
                Log.d("ESP", "Updating GLSurface") // <-- Логирование
                surface.updatePlayerPosition(position, rotationYaw, rotationPitch)
                surface.updateEntities(entities)
            } ?: Log.d("ESP", "GLSurface is null, cannot update") // <-- Логирование
        }
    }


    private fun searchForClosestEntities(): List<Entity> {
        Log.d("ESP", "searchForClosestEntities called") // <-- Логирование
        Log.d("ESP", "Total entities in level: ${session.level.entityMap.size}") // <-- Логирование

        val entities = session.level.entityMap.values.filter { entity ->
            val distance = entity.vec3Position?.let { pos ->
                session.localPlayer.vec3Position?.let { playerPos ->
                    sqrt((pos.x - playerPos.x).toDouble().pow(2.0) +
                            (pos.y - playerPos.y).toDouble().pow(2.0) +
                            (pos.z - playerPos.z).toDouble().pow(2.0))
                }
            } ?: Double.MAX_VALUE

            distance <= rangeValue.toDouble() && entity.isTarget()
        }.sortedBy { entity ->
            entity.vec3Position?.let { pos ->
                session.localPlayer.vec3Position?.let { playerPos ->
                    sqrt((pos.x - playerPos.x).toDouble().pow(2.0) +
                            (pos.y - playerPos.y).toDouble().pow(2.0) +
                            (pos.z - playerPos.z).toDouble().pow(2.0))
                }
            } ?: Double.MAX_VALUE
        }

        Log.d("ESP", "Entities after filtering (range=$rangeValue, playersOnly=$playersOnly): ${entities.size}") // <-- Логирование
        // Дополнительный лог для каждой сущности (опционально, может быть много)
        // entities.forEach { entity ->
        //     Log.d("ESP", "Entity: ${entity.javaClass.simpleName} at ${entity.vec3Position}")
        // }

        return if (multiTarget) entities.take(maxTargets) else entities.take(1)
    }

    // Предполагаем, что класс Entity определен где-то в проекте и имеет метод isTarget()
    // и свойство vec3Position. Ниже примерная реализация.
    abstract class Entity {
        abstract val vec3Position: Vector3f?

        open fun isTarget(): Boolean {
            Log.d("ESP", "Entity.isTarget called for ${this.javaClass.simpleName}") // <-- Логирование
            // Примерная логика, замените на реальную из вашего проекта
            // Например, проверка на тип сущности (Player, Mob и т.д.)
            // и, возможно, на isBot(), если такая функция существует.
            // val result = this is Player && (!playersOnly || !this.isBot())
            // Log.d("ESP", "isTarget result for ${this.javaClass.simpleName}: $result") // <-- Логирование
            // return result
            return false // Заглушка, замените на реальную логику
        }

        // Примерная реализация distance, если её нет
        fun distance(other: Entity): Double {
            val thisPos = this.vec3Position ?: return Double.MAX_VALUE
            val otherPos = other.vec3Position ?: return Double.MAX_VALUE
            return sqrt((thisPos.x - otherPos.x).toDouble().pow(2.0) +
                    (thisPos.y - otherPos.y).toDouble().pow(2.0) +
                    (thisPos.z - otherPos.z).toDouble().pow(2.0))
        }
    }

    // Предполагаем, что класс Player существует и наследуется от Entity
    // open class Player : Entity() {
    //     // Реализация isBot(), если она есть
    //     open fun isBot(): Boolean {
    //         // Логика определения бота
    //         return false // Заглушка
    //     }
    // }

    // Предполагаем, что LocalPlayer существует и наследуется от Player
    // class LocalPlayer : Player() {
    //     // ...
    // }
}
