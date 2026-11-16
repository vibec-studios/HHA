package dev.henny.hha.logic.boss

import dev.henny.hha.HhaSounds
import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Fx
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.damage.DamageTypes
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max

/**
 * Abwehr gegen Mace-Sprünge (Überarbeitung 2, PLAN 4.3), für alle Bosse und Wächter gleich.
 *
 * - **Stufe 1, passiv** ([Guard.onHit]): Sturztreffer außerhalb offener Fenster zählen je Boss und Spieler abnehmend
 *   (Faktoren [SMASH_FACTORS] für `z = 0, 1, 2, ≥ 3`); alle `boss_smash_decay` Ticks ohne Sturztreffer sinkt `z` um 1.
 *   In offenen Fenstern zählen sie voll, erhöhen `z` nicht und werden nie abgewehrt.
 * - **Stufe 2, aktiv** ([Guard.counterDue]): Steht `z ≥ 2` an oder war ein Spieler 40 von 100 Ticks „über ihm“, spielt
 *   der Boss nach dem laufenden Angriff statt der Erholung seinen Konter (Ansage-Kreis 12 Ticks, [Reach.SKY], Abwurf).
 *   Danach [VIGILANT_TICKS] Ticks wachsam: Sturztreffer prallen ab. Abklingzeit [COOLDOWN_TICKS].
 * - **Stufe 3** ([reaches]): Höhenregel für jeden Angriff, siehe [Reach].
 *
 * `/hha toggle boss_anti_air false` schaltet Stufe 1 und 2 ab; die Höhenregel bleibt.
 */
object BossDefense {

    /** Wie hoch über dem Boden der Ansage ein Angriff noch trifft (Füße des Spielers). */
    enum class Reach {
        /** Bis 0,6 über dem Boden: Wellen und Ringe zum Überspringen. */
        LOW,

        /** Bis Bosshöhe + 1,5, mindestens 4,5: Hiebe, Kegel, Stöße, Linien, Geschosse (Standard). */
        BODY,

        /** Unbegrenzt: Säulen, Blitze, Einschläge von oben, Geysire, saalweite Urteile. */
        SKY,
    }

    /** Faktor je Stand des Zählers `z` (0, 1, 2, ≥ 3). */
    val SMASH_FACTORS = floatArrayOf(1f, 0.6f, 0.3f, 0.1f)

    /** [Reach.LOW]: höchstens so hoch über dem Boden. */
    const val LOW_HEIGHT = 0.6

    /** [Reach.BODY]: Bosshöhe plus so viel, … */
    const val BODY_EXTRA = 1.5

    /** … aber mindestens so hoch. */
    const val BODY_MIN = 4.5

    /** „Über ihm“: waagerecht näher als halbe Breite plus so viel … */
    const val OVER_RADIUS = 3.5

    /** … und Füße höher als Boss-Y + dieser Anteil seiner Höhe. */
    const val OVER_HEIGHT = 0.6

    /** Auslöser: so viele Ticks „über ihm“ … */
    const val OVER_TICKS = 40

    /** … innerhalb so vieler Ticks. */
    const val OVER_WINDOW = 100

    /** Auslöser: ab diesem Zählerstand. */
    const val TRIGGER_COUNT = 2

    /** Ansage des Konters (Kreis um ihn, Höhe unbegrenzt). */
    const val COUNTER_TICKS = 12

    /** Radius des Konter-Kreises (plus halbe Breite des Bosses). */
    const val COUNTER_RADIUS = 3.5

    /** Nach dem Konter so lange wachsam: Sturztreffer prallen ab. */
    const val VIGILANT_TICKS = 100

    /** Abklingzeit des Konters ab seinem Beginn. */
    const val COOLDOWN_TICKS = 200

    /** Abwurf: waagerecht nach außen … */
    const val THROW_OUT = 0.9

    /** … und nach unten. */
    const val THROW_DOWN = 1.1

    /** Gemeinsame Aktion der Wächter für den Konter (`kit.act`, PLAN 10.2). */
    const val ANTI_AIR = 20

    /** Einmaliger Hinweis, wenn ein Sturztreffer abprallt. */
    const val HINT_KEY = "hha.boss.hint.anti_air"

    /** Ist die Abwehr (Stufe 1 und 2) an? */
    fun enabled(): Boolean = HhaConfig.enabled("boss_anti_air")

    /**
     * Höhenregel: Trifft ein Angriff mit [reach], dessen Ansage auf [floorY] liegt, den [player]? [bossHeight] ist die
     * Höhe der Trefferbox des Bosses (für [Reach.BODY]).
     */
    fun reaches(player: Entity, floorY: Double, reach: Reach, bossHeight: Double = 3.0): Boolean {
        val above = player.y - floorY
        return when (reach) {
            Reach.LOW -> above <= LOW_HEIGHT
            Reach.BODY -> above <= max(BODY_MIN, bossHeight + BODY_EXTRA)
            Reach.SKY -> true
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Sturztreffer
    // ---------------------------------------------------------------------------------------------

    /** Spieler, deren Flächenschaden gerade als Sturztreffer zählt (Shockwave, Magma Stomp). Nur Server-Thread. */
    private val marked = HashSet<UUID>()

    /** Ab jetzt zählt Schaden von [player] als Sturztreffer, bis [clearFallStrike]. */
    fun markFallStrike(player: PlayerEntity) {
        marked.add(player.uuid)
    }

    fun clearFallStrike(player: PlayerEntity) {
        marked.remove(player.uuid)
    }

    /** Führt [block] aus, während Schaden von [player] als Sturztreffer zählt (für Aufprall-Flächenschaden). */
    inline fun <T> fallStrike(player: PlayerEntity, block: () -> T): T {
        markFallStrike(player)
        try {
            return block()
        } finally {
            clearFallStrike(player)
        }
    }

    /** Ist [source] ein Sturztreffer? Mace-Smash (auch Heavens/Hells Mace) oder von [markFallStrike] markiert. */
    fun isFallStrike(source: DamageSource): Boolean {
        if (source.isOf(DamageTypes.MACE_SMASH)) return true
        val attacker = source.attacker as? PlayerEntity ?: return false
        return attacker.uuid in marked
    }

    // ---------------------------------------------------------------------------------------------
    // Zustand je Boss
    // ---------------------------------------------------------------------------------------------

    /**
     * Abwehr eines Bosses: Zähler je Spieler, Beobachtung „über ihm“, Konter-Auslöser, Wachsamkeit, Abklingzeit.
     * Ein Kampf hält genau eine Instanz je Boss-Körper. Ablauf je Tick: [tick]; je Treffer [onHit] (vor dem Deckel) und,
     * wenn der Treffer durchging, [landed]; Konter: [counterDue] → [beginCounter] → Ansage → [counterHit] je Getroffenem →
     * [endCounter].
     */
    class Guard(private val boss: LivingEntity) {

        private class Watch {
            var count = 0
            var quiet = 0
            val over = BooleanArray(OVER_WINDOW)
            var overCount = 0
            var cursor = 0
            var hinted = false
        }

        private val watches = HashMap<UUID, Watch>()
        private var time = 0L
        private var cooldownUntil = 0L
        private var vigilantUntil = 0L
        private var countering = false

        /** Ist er gerade wachsam (Sturztreffer prallen ab)? */
        val vigilant: Boolean get() = time < vigilantUntil

        /** Läuft gerade sein Konter? */
        val inCounter: Boolean get() = countering

        /** Radius des Konter-Kreises für diesen Boss (plus [extra], z. B. Zeus 0,5). */
        fun counterRadius(extra: Double = 0.0): Double = COUNTER_RADIUS + boss.width * 0.5 + extra

        /** Jeden Tick mit allen Mitkämpfern aufrufen. */
        fun tick(players: Collection<ServerPlayerEntity>) {
            time++
            val decay = HhaConfig.num("boss_smash_decay").toInt().coerceAtLeast(1)
            val present = HashSet<UUID>()
            for (player in players) {
                present.add(player.uuid)
                val watch = watches.getOrPut(player.uuid) { Watch() }
                if (watch.count > 0 && ++watch.quiet >= decay) {
                    watch.count--
                    watch.quiet = 0
                }
                val nowOver = isOver(player)
                if (watch.over[watch.cursor]) watch.overCount--
                watch.over[watch.cursor] = nowOver
                if (nowOver) watch.overCount++
                watch.cursor = (watch.cursor + 1) % OVER_WINDOW
            }
            watches.keys.retainAll(present)
        }

        /** Steht [player] gerade „über ihm“ (PLAN 4.3)? */
        fun isOver(player: ServerPlayerEntity): Boolean {
            if (player.isOnGround) return false
            val flat = hypot(player.x - boss.x, player.z - boss.z)
            return flat < boss.width * 0.5 + OVER_RADIUS && player.y > boss.y + OVER_HEIGHT * boss.height
        }

        /** Ist ein Konter fällig (Auslöser erfüllt, nicht in Abklingzeit, Abwehr an)? */
        fun counterDue(): Boolean {
            if (!enabled() || countering || time < cooldownUntil) return false
            return watches.values.any { it.count >= TRIGGER_COUNT || it.overCount >= OVER_TICKS }
        }

        /**
         * Faktor der Abwehr für einen Treffer, **vor** dem Deckel: [amount] mal Smash-Faktor, 0 bei Abprall (dann mit
         * Abprall-Ton und einmaligem Hinweis). In offenen Fenstern ([open]) immer [amount].
         */
        fun onHit(source: DamageSource, amount: Float, open: Boolean): Float {
            if (open || !enabled() || !isFallStrike(source)) return amount
            val player = source.attacker as? ServerPlayerEntity ?: return amount
            if (vigilant) {
                (boss.entityWorld as? ServerWorld)?.playSound(null, boss.x, boss.y, boss.z, HhaSounds.DEFLECT, SoundCategory.HOSTILE, 1.6f, 0.8f)
                val watch = watches.getOrPut(player.uuid) { Watch() }
                if (!watch.hinted) {
                    watch.hinted = true
                    player.sendMessage(Text.translatable(HINT_KEY).formatted(Formatting.GOLD), true)
                }
                return 0f
            }
            val count = watches[player.uuid]?.count ?: 0
            return amount * SMASH_FACTORS[count.coerceIn(0, SMASH_FACTORS.size - 1)]
        }

        /** Der Treffer ist durchgegangen: Ein Sturztreffer außerhalb offener Fenster erhöht `z`. */
        fun landed(source: DamageSource, open: Boolean) {
            if (open || !enabled() || !isFallStrike(source)) return
            val player = source.attacker as? ServerPlayerEntity ?: return
            val watch = watches.getOrPut(player.uuid) { Watch() }
            watch.count++
            watch.quiet = 0
        }

        /** Der Konter beginnt (Ansage): Abklingzeit läuft ab jetzt. */
        fun beginCounter() {
            countering = true
            cooldownUntil = time + COOLDOWN_TICKS
        }

        /** Getroffen vom Konter: Abwurf nach außen und unten, Fallhöhe auf 0. Den Schaden macht der Kampf. */
        fun counterHit(player: ServerPlayerEntity) {
            val away = Vec3d(player.x - boss.x, 0.0, player.z - boss.z).let { if (it.lengthSquared() < 1e-4) boss.rotationVector.multiply(1.0, 0.0, 1.0) else it }.normalize()
            Fx.launchPlayer(player, away.multiply(THROW_OUT).add(0.0, -THROW_DOWN, 0.0))
            player.fallDistance = 0.0
            watches[player.uuid]?.let {
                java.util.Arrays.fill(it.over, false)
                it.overCount = 0
            }
        }

        /** Der Konter ist vorbei: [VIGILANT_TICKS] Ticks wachsam. */
        fun endCounter() {
            countering = false
            vigilantUntil = time + VIGILANT_TICKS
        }

        /** Zählerstand eines Spielers (für Tests und Anzeigen). */
        fun count(player: ServerPlayerEntity): Int = watches[player.uuid]?.count ?: 0

        /** Alles vergessen (Leine, Neustart). */
        fun reset() {
            watches.clear()
            countering = false
            vigilantUntil = 0L
            cooldownUntil = 0L
        }
    }
}
