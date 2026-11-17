package dev.henny.hha.logic.boss

import dev.henny.hha.config.HhaConfig
import net.minecraft.server.network.ServerPlayerEntity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Stärke und Skalierung aller Bosse, Wächter und Gegner (Überarbeitung 2, PLAN 4.1/4.2). **Die einzige Stelle**, die
 * `hermes_health_per_player`, `hermes_damage_per_player` und `hermes_max_hit` liest; die Kampfpakete rechnen nur hierüber.
 *
 * Reihenfolge für eingehenden Schaden (für alle Kämpfe gleich, siehe `tmp/overhaul2/API.md`):
 * 1. Rohschaden × eigener Trefferfaktor (Schild von vorn, Rücken, Ventil …),
 * 2. [BossDefense.Guard.onHit] (Wachsamkeit → 0, Smash-Folge 1,0/0,6/0,3/0,1; in offenen Fenstern immer voll),
 * 3. × Faktor des offenen Fensters (neue Bosse 1,8; Wächter wie bisher 1,5),
 * 4. Deckel [hitCap] (in effektiven Punkten),
 * 5. [toBar]: ÷ (Zähigkeit × [health]) → was die Lebensleiste verliert.
 */
object BossScaling {

    /** Schadensregler je Familie: alles im Olymp über `hermes_damage`, alles im Tartaros über `hades_damage`. */
    enum class Family(val damageKey: String) {
        OLYMP("hermes_damage"),
        TARTAROS("hades_damage"),
    }

    /** Deckel-Stufe: Bosse 4 % (offen 6 %), Wächter das 1,5-Fache davon, normale Gegner ohne Deckel. */
    enum class Tier(val capFactor: Float) {
        BOSS(1f),
        GUARDIAN(1.5f),
        MOB(0f),
    }

    /** Höchstens so viele Mitkämpfer zählen (PLAN 4.1). */
    const val MAX_CROWD = 8

    /** Deckel in offenen Fenstern: das 1,5-Fache. */
    const val OPEN_CAP = 1.5f

    /**
     * Grundverstärkung aller Gegner seit dem 16.11. (Rückmeldung des Nutzers: „viel zu schwach“): 1,6-faches Leben,
     * 1,35-facher Schaden, unabhängig von gespeicherten Config-Werten. Feinjustierung weiter über `boss_strength` und
     * `hermes_damage`/`hades_damage`.
     */
    const val HEALTH_BOOST = 1.6f
    const val DAMAGE_BOOST = 1.35f

    /** Pausen werden höchstens auf drei Viertel verkürzt. */
    const val MIN_PAUSE_FACTOR = 0.75

    /** `hermes_max_hit` ist in Promille des effektiven Grundlebens angegeben (Standard 40 → 4 %). */
    private const val PER_MILLE = 1000f

    /**
     * `n`: lebende Mitkämpfer, nicht Kreativ und nicht Zuschauer, mindestens 1 und höchstens [MAX_CROWD]. Die Liste
     * liefert der Kampf (z. B. alle in der Arena); hier wird nur gefiltert und begrenzt.
     */
    fun crowd(players: Collection<ServerPlayerEntity>): Int =
        players.count { it.isAlive && !it.isSpectator && !it.isCreative }.coerceIn(1, MAX_CROWD)

    /** Lebensfaktor `H(n) = boss_strength · (1 + hermes_health_per_player · (n − 1))`; teilt den eingehenden Schaden. */
    fun health(n: Int): Float {
        val crowd = n.coerceIn(1, MAX_CROWD)
        return HEALTH_BOOST * HhaConfig.numF("boss_strength") * (1f + HhaConfig.numF("hermes_health_per_player") * (crowd - 1)) *
            dev.henny.hha.rpg.RpgRules.bossHealthFactor() // Überarbeitung 3 (P06): nur in RPG-Welten ≠ 1
    }

    /**
     * Schadensfaktor `D(n) = min(1 + hermes_damage_per_player · (n − 1), boss_damage_player_cap)`, mal dem Regler der
     * [family] (`hermes_damage` bzw. `hades_damage`). Gilt für jeden Treffer eines Gegners auf einen Spieler.
     */
    fun damage(n: Int, family: Family): Float {
        val crowd = n.coerceIn(1, MAX_CROWD)
        val perPlayer = min(1f + HhaConfig.numF("hermes_damage_per_player") * (crowd - 1), HhaConfig.numF("boss_damage_player_cap"))
        return DAMAGE_BOOST * perPlayer * HhaConfig.numF(family.damageKey) *
            dev.henny.hha.rpg.RpgRules.bossDamageFactor() // Überarbeitung 3 (P06): nur in RPG-Welten ≠ 1
    }

    /** Pause zwischen zwei Angriffen: `ticks × max(0,75, 1 − boss_tempo_per_player · (n − 1))`. Ansagen ändern sich nie. */
    fun pause(ticks: Int, n: Int): Int {
        val crowd = n.coerceIn(1, MAX_CROWD)
        val factor = max(MIN_PAUSE_FACTOR, 1.0 - HhaConfig.num("boss_tempo_per_player") * (crowd - 1))
        return (ticks * factor).roundToInt().coerceAtLeast(0)
    }

    /**
     * Höchster Schaden eines einzelnen Treffers **in effektiven Punkten** (vor [toBar]):
     * `effectiveBase · hermes_max_hit / 1000 · Stufe`, in offenen Fenstern × [OPEN_CAP]. [effectiveBase] ist das
     * effektive Grundleben solo und vor `boss_strength` (Tabelle PLAN 4.1, z. B. Hermes 1400). [Tier.MOB] hat keinen
     * Deckel ([Float.MAX_VALUE]).
     */
    fun hitCap(effectiveBase: Float, tier: Tier, open: Boolean): Float {
        if (tier == Tier.MOB) return Float.MAX_VALUE
        val cap = effectiveBase * HhaConfig.numF("hermes_max_hit") / PER_MILLE * tier.capFactor
        return if (open) cap * OPEN_CAP else cap
    }

    /** Was die Lebensleiste von [effective] Schaden verliert: `effective ÷ (toughness · H(n))`. */
    fun toBar(effective: Float, toughness: Float, n: Int): Float = effective / (toughness.coerceAtLeast(0.01f) * health(n))
}
