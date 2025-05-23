package com.project.lumina.client.game.module.motion

import com.project.lumina.client.R
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.RequestAbilityPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket

class FlyElement(iconResId: Int = R.drawable.ic_feather_black_24dp) : Element(
    name = "Fly",
    category = CheatCategory.Motion,
    iconResId,
    displayNameResId = R.string.module_fly_display_name
) {

    private var flySpeed by floatValue("Speed", 0.3f, 0.05f..1.0f)
    private var verticalSpeed by floatValue("Vertical Speed", 0.3f, 0.1f..1.0f)

    // Пакет для включения способностей к полету на стороне клиента
    private val enableFlyAbilitiesPacket = UpdateAbilitiesPacket().apply {
        playerPermission = PlayerPermission.OPERATOR
        commandPermission = CommandPermission.OWNER
        abilityLayers.add(AbilityLayer().apply {
            layerType = AbilityLayer.Type.BASE
            abilitiesSet.addAll(Ability.entries.toTypedArray())
            abilityValues.addAll(
                listOf(
                    Ability.BUILD,
                    Ability.MINE,
                    Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS,
                    Ability.ATTACK_PLAYERS,
                    Ability.ATTACK_MOBS,
                    Ability.OPERATOR_COMMANDS,
                    Ability.MAY_FLY, // Ключевая способность для полета
                    Ability.FLY_SPEED,
                    Ability.WALK_SPEED
                )
            )
            walkSpeed = 0.1f
            flySpeed = this@FlyElement.flySpeed
        })
    }

    override fun onEnabled() { // <-- Изменено с onEnable() на onEnabled()
        super.onEnabled()
        // Инициализируем FlightHandler при включении модуля
                session?.let { 
            it.localPlayer?.let { localPlayer ->
                FlightHandler.initialize(it.luminaRelaySession, localPlayer)
            }
        } // <-- Передаем luminaRelaySession
        // Отправляем пакеты способностей при включении, чтобы клиент мог летать
        session?.localPlayer?.let { localPlayer -> // <-- Безопасный доступ к localPlayer
            enableFlyAbilitiesPacket.uniqueEntityId = localPlayer.uniqueEntityId
            session?.clientBound(enableFlyAbilitiesPacket)
        }
    }

    override fun onDisabled() { // <-- Изменено с onDisable() на onDisabled()
        super.onDisabled()
        // При деактивации модуля FlightElement мы не отключаем FlightHandler
        // и не отбираем способность MAY_FLY, как было запрошено.
        // FlightHandler управляет своим состоянием полета на основе флагов START_FLYING/STOP_FLYING.
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        val packet = interceptablePacket.packet

        // Перехватываем RequestAbilityPacket, чтобы клиент мог летать
        if (packet is RequestAbilityPacket && packet.ability == Ability.FLYING) {
            interceptablePacket.intercept()
            return
        }

        // Перехватываем UpdateAbilitiesPacket, чтобы контролировать способности клиента
        if (packet is UpdateAbilitiesPacket) {
            interceptablePacket.intercept()
            return
        }

        // Если это пакет ввода игрока, всегда обрабатываем его для управления FlightHandler
        if (packet is PlayerAuthInputPacket) {
            // Проверяем флаги START_FLYING и STOP_FLYING
            if (packet.inputData.contains(PlayerAuthInputData.START_FLYING)) {
                FlightHandler.startFlight() // Сигнализируем FlightHandler о начале полета
            }
            if (packet.inputData.contains(PlayerAuthInputData.STOP_FLYING)) {
                FlightHandler.stopFlight() // Сигнализируем FlightHandler об окончании полета
            }

            // Передаем управление полетом FlightHandler'у
            // FlightHandler сам решит, нужно ли обрабатывать движение, основываясь на своем внутреннем состоянии isFlyingActive
            session?.localPlayer?.let { localPlayer ->
                FlightHandler.handlePlayerInput(packet, flySpeed, verticalSpeed)
            }

            // Важно: перехватываем PlayerAuthInputPacket, чтобы предотвратить
            // отправку обычного движения, которое может конфликтовать с FlightHandler
            interceptablePacket.intercept() 
        }
    }
}
