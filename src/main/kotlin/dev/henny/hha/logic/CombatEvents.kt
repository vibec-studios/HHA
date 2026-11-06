package dev.henny.hha.logic

import dev.henny.hha.HhaItems
import dev.henny.hha.config.HhaConfig
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import java.util.UUID

/**
 * Waffen-Passiva:
 * Hell's Sword — Crits segnen dich mit Goldapfel-Absorption (2 Minuten);
 * während des Aktiv-Buffs critten alle Angriffe.
 * Heaven's Sword — ab genug Treffern in Folge (default: der 3. Hit) crittet
 * JEDER Schlag automatisch, bis man selbst von einem Spieler getroffen wird.
 */
object CombatEvents {

    private const val COMBO_TIMEOUT_TICKS = 60L

    /** Heaven's Sword Combo-Zähler: Treffer in Folge + Tick des letzten Treffers. */
    private data class Combo(var hits: Int, var lastHitTick: Long)
    private val combos = HashMap<UUID, Combo>()

    /** Gesegnete Spieler: Dauer-Auto-Crit, endet erst beim Treffer durch einen Spieler. */
    private val blessed = HashSet<UUID>()

    fun init() {
        AttackEntityCallback.EVENT.register { player, world, hand, entity, _ ->
            if (world is ServerWorld && player is ServerPlayerEntity &&
                entity is LivingEntity && hand == Hand.MAIN_HAND
            ) {
                BruteAllies.onOwnerAttack(world, player, entity)
                when {
                    player.mainHandStack.isOf(HhaItems.HELLS_SWORD) -> onHellSwordAttack(world, player, entity)
                    player.mainHandStack.isOf(HhaItems.HEAVENS_SWORD) -> onHeavenSwordAttack(world, player, entity)
                }
            }
            ActionResult.PASS
        }

        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, damageTaken, _ ->
            if (entity is ServerPlayerEntity && damageTaken > 0f &&
                source.attacker is PlayerEntity && source.attacker != entity &&
                blessed.remove(entity.uuid)
            ) {
                entity.entityWorld.playSound(
                    null, entity.blockPos, SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK,
                    SoundCategory.PLAYERS, 0.9f, 0.7f
                )
            }
        }
    }

    private fun onHellSwordAttack(
        world: ServerWorld,
        player: ServerPlayerEntity,
        target: LivingEntity,
    ) {
        val buffed = HhaConfig.enabled("sword_buff") && SwordBuff.isActive(world.server!!, player)
        if (buffed) {
            forceCrit(player)
            Fx.spawn(world, 
                dev.henny.hha.HhaParticles.HELLFIRE,
                player.x, player.y + 1.1, player.z,
                2, 0.3, 0.3, 0.3, 0.02
            )
        }

        val fullyCharged = player.getAttackCooldownProgress(0.5f) > 0.9f
        if (!fullyCharged || (!buffed && !isCritLike(player))) return

        if (buffed) {
            showAutoCritImpact(world, target, holy = false)
        }

        if (HhaConfig.enabled("hell_crit_absorption")) {
            player.addStatusEffect(
                StatusEffectInstance(
                    StatusEffects.ABSORPTION,
                    HhaConfig.num("hell_absorption_duration").toInt(), 0
                )
            )
            Fx.spawn(world, 
                dev.henny.hha.HhaParticles.EMBER_SPARK,
                player.x, player.y + 1.4, player.z,
                6, 0.25, 0.25, 0.25, 0.02
            )
        }
    }

    private fun onHeavenSwordAttack(
        world: ServerWorld,
        player: ServerPlayerEntity,
        target: LivingEntity,
    ) {
        if (player.getAttackCooldownProgress(0.5f) <= 0.9f) return

        var crit = isCritLike(player)
        if (HhaConfig.enabled("combo_crit")) {
            if (player.uuid in blessed) {
                forceCrit(player)
                crit = true
                showAutoCritImpact(world, target, holy = true)
            } else {
                val now = world.server!!.ticks.toLong()
                val combo = combos.getOrPut(player.uuid) { Combo(0, now) }
                if (now - combo.lastHitTick > COMBO_TIMEOUT_TICKS) combo.hits = 0
                combo.lastHitTick = now

                if (combo.hits >= HhaConfig.num("combo_hits").toInt()) {
                    combo.hits = 0
                    blessed.add(player.uuid)
                    forceCrit(player)
                    crit = true
                    showAutoCritImpact(world, target, holy = true)
                    Fx.spawn(world, 
                        dev.henny.hha.HhaParticles.HOLY_SPARK,
                        player.x, player.y + 1.2, player.z,
                        12, 0.3, 0.4, 0.3, 0.04
                    )
                    world.playSound(
                        null, player.blockPos, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT,
                        SoundCategory.PLAYERS, 1.0f, 1.6f
                    )
                } else {
                    combo.hits++
                }
            }
        }

        if (crit) {
            Fx.spawn(world, 
                dev.henny.hha.HhaParticles.HOLY_SPARK,
                player.x, player.y + 2.1, player.z,
                4, 0.2, 0.1, 0.2, 0.0
            )
        }
    }

    /** Deutliche Treffer-Rückmeldung für erzwungene Auto-Crits — bei Mobs wie Spielern. */
    private fun showAutoCritImpact(world: ServerWorld, target: LivingEntity, holy: Boolean) {
        val y = target.y + target.height * 0.6
        Fx.spawn(world, 
            ParticleTypes.CRIT,
            target.x, y, target.z,
            18, 0.35, target.height * 0.3, 0.35, 0.18
        )
        Fx.spawn(world, 
            if (holy) dev.henny.hha.HhaParticles.DIVINE_FLASH else dev.henny.hha.HhaParticles.INFERNAL_BURST,
            target.x, y, target.z,
            1, 0.0, 0.0, 0.0, 0.0
        )
        Vfx.flare(world, if (holy) Vfx.Style.HEAVEN else Vfx.Style.FIRE, target.entityPos.add(0.0, target.height * 0.6, 0.0), 1.8f, 7)
        if (holy) {
            world.playSound(
                null, target.blockPos, SoundEvents.ENTITY_ARROW_HIT_PLAYER,
                SoundCategory.PLAYERS, 0.9f, 1.35f
            )
            world.playSound(
                null, target.blockPos, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT,
                SoundCategory.PLAYERS, 1.0f, 1.15f
            )
            world.playSound(
                null, target.blockPos, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                SoundCategory.PLAYERS, 0.6f, 1.8f
            )
        } else {
            world.playSound(
                null, target.blockPos, SoundEvents.ENTITY_ARROW_HIT_PLAYER,
                SoundCategory.PLAYERS, 0.9f, 0.85f
            )
            world.playSound(
                null, target.blockPos, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT,
                SoundCategory.PLAYERS, 1.0f, 0.8f
            )
        }
    }

    /** Erzwingt die Vanilla-Crit-Bedingungen für den unmittelbar folgenden Angriff. */
    private fun forceCrit(player: ServerPlayerEntity) {
        player.setOnGround(false)
        player.fallDistance = 0.3
        player.isSprinting = false
    }

    /**
     * Großzügigere Crit-Erkennung als Vanilla: Sprung-Angriffe zählen auch beim
     * Sprinten — Hauptsache der Spieler ist fallend in der Luft.
     */
    private fun isCritLike(player: ServerPlayerEntity): Boolean =
        player.fallDistance > 0.0 &&
            !player.isOnGround &&
            !player.isClimbing &&
            !player.isTouchingWater
}
