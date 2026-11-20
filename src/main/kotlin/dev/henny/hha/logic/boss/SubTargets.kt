package dev.henny.hha.logic.boss

import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.SubTargetEntity
import dev.henny.hha.logic.Vfx
import net.minecraft.entity.damage.DamageSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.abs

/*
 * Teilziele mit eigener Unterleiste (Überarbeitung 4, PLAN 4.1, 7.6; Besitzer P04).
 *
 * Regeln (PLAN 4.1, Pflicht):
 * - Ein Teilziel hat **kein eigenes Handeln**. Diese Datei bietet nichts, womit es angreifen könnte.
 * - Golden nur, solange es Treffer nimmt ([SubTarget.open]); sonst prallt jeder Treffer ab.
 * - Leben über `BossScaling.health` (die Gruppe skaliert beim Anlegen), Deckel je Treffer ([SubTarget.capPerHit]).
 * - **Ein Teilziel je Angreifer und Tick** über die ganze Gruppe (Schutz gegen Flächenschaden, Mace-Schockwelle, REF_VOID 2.9).
 *
 * Trefferreihenfolge ([SubTargetGroup.apply], für Wesen und für [SubTarget.hit] gleich):
 * 1. Gruppe offen, Teilziel offen und heil, sonst 0;
 * 2. ein Teilziel je Angreifer und Tick, sonst 0;
 * 3. `handler.onHit` darf kürzen oder 0 liefern (nur mit Schadensquelle);
 * 4. Deckel `maxHealth · capPerHit`;
 * 5. bei 0 Leben: gebrochen → Ton `arena.sub_break`, Wesen weg, `handler.onBroken`; sonst Ton `arena.sub_hit`
 *    (höchstens einer je [HIT_SOUND_GAP] Ticks und Teilziel).
 *
 * Die Unterleisten gehen über `Cine.subBars` hinaus: nur bei Änderung, höchstens alle [SEND_GAP] Ticks, dazu alle
 * [HEARTBEAT] Ticks zur Sicherheit (ein `Cine.reset` mitten im Kampf leert sie am Client).
 */

/** Was der Kampf mit Treffern und Bruch eines Teilziels macht. */
interface SubTargetHandler {
    /** Treffer auf [target]: gibt den Schaden zurück, der zählen soll (0 = abgewehrt). Darf kürzen, nie handeln. */
    fun onHit(target: SubTarget, source: DamageSource, amount: Float): Float

    /** [target] ist gebrochen (Fenster öffnen, Angriff streichen …). */
    fun onBroken(target: SubTarget)
}

/**
 * Ein Teilziel. [maxHealth] Leben (bereits skaliert, wenn über [SubTargetGroup.add] angelegt), [capPerHit] Deckel je
 * Treffer als Anteil der vollen Leiste (0,12 = höchstens 12 % je Treffer), [notches] Kerben der Unterleiste (Risse im
 * Amboss, Treffer an einer Fessel).
 */
class SubTarget(val id: String, val nameKey: String, val maxHealth: Float = 1f, val capPerHit: Float = 1f, val notches: Int = 0) {

    /** Nimmt Treffer und ist golden (Zustand 1). Beim Öffnen setzt die Gruppe eine goldene Rune unter das Wesen. */
    var open: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value && !broken) group?.opened(this)
        }

    var health: Float = maxHealth
        private set

    val fraction: Float get() = if (maxHealth <= 0f) 0f else (health / maxHealth).coerceIn(0f, 1f)
    val broken: Boolean get() = health <= 0f

    /** Zustand für die Unterleiste: 0 ruht, 1 golden/verwundbar, 2 gebrochen. */
    val state: Int get() = when {
        broken -> STATE_BROKEN
        open -> STATE_OPEN
        else -> STATE_IDLE
    }

    /**
     * Ort für Klang und Rune bei Teilzielen **ohne** Wesen (z. B. der Amboss, Bia). Mit Wesen gilt dessen Lage. Der
     * Kampf darf es jederzeit setzen; ohne Ort spielt der Treffer-Klang beim Angreifer.
     */
    var anchor: Vec3d? = null

    internal var group: SubTargetGroup? = null
    internal var lastHitSound = Long.MIN_VALUE / 2

    /**
     * Treffer ohne eigenes Wesen (Amboss, Bia). Mit [source] laufen Angreifer-Sperre und `handler.onHit` mit; ohne
     * [source] hat der Kampf schon entschieden, dass der Treffer zählt (nur „offen“ und Deckel gelten). Liefert den
     * gezählten Schaden (0 = abgewehrt). Ohne Gruppe zählen nur „offen“ und Deckel.
     */
    fun hit(amount: Float, source: DamageSource? = null): Float {
        val g = group ?: return applyRaw(amount)
        return g.apply(this, amount, source)
    }

    /** Leben abziehen (Deckel inklusive), ohne Klang und Rückruf. Liefert den abgezogenen Wert. */
    internal fun applyRaw(amount: Float): Float {
        if (!open || broken || amount <= 0f) return 0f
        val taken = amount.coerceAtMost(maxHealth * capPerHit).coerceAtMost(health)
        health -= taken
        if (health < BROKEN_EPSILON) health = 0f
        return taken
    }

    companion object {
        const val STATE_IDLE = 0
        const val STATE_OPEN = 1
        const val STATE_BROKEN = 2

        /** Restleben darunter zählt als gebrochen (Rundung bei Deckel-Anteilen). */
        private const val BROKEN_EPSILON = 1e-4f
    }
}

/**
 * Teilziele eines Kampfes. [viewers] sind die Spieler, die die Unterleisten sehen (meist die Kampfteilnehmer); ihre
 * Anzahl beim Anlegen bestimmt die Skalierung. Der Kampf ruft [tick] jeden Server-Tick und [close] an **jedem** Ende
 * (Sieg, Niederlage, Abbruch, Neustart); mehrfaches [close] schadet nicht. Ohne [tick] verschwinden die Wesen nach
 * `SubTargetEntity.ORPHAN_TICKS`, die Leisten am Client nach `PhaseBar.SUB_TIMEOUT`.
 */
class SubTargetGroup(val world: ServerWorld, val viewers: () -> Collection<ServerPlayerEntity>, val handler: SubTargetHandler) {

    private val targets = ArrayList<SubTarget>()
    private val entities = HashMap<String, SubTargetEntity>()

    /** Angreifer → Welt-Tick seines letzten gezählten Treffers (ein Teilziel je Angreifer und Tick). */
    private val lastHit = HashMap<UUID, Long>()

    /** Zuletzt gesendet: Leisten und Empfänger. */
    private var sentBars: List<Cine.SubBar> = emptyList()
    private var sentTo: Set<ServerPlayerEntity> = emptySet()
    private var sentAt = Long.MIN_VALUE / 2

    var closed: Boolean = false
        private set

    val all: List<SubTarget> get() = targets

    /**
     * Farbthema der ruhenden Leisten (Zustand 0) wie bei `Cine.phaseBar` (0 Grün, 1 Gold, ab 10 `Vfx.Style`-Ordinal
     * + 10). −1 = Thema der Hauptleiste übernehmen bzw. gedämpftes Rot ohne Hauptleiste.
     */
    var theme: Int = -1

    /**
     * Neues Teilziel. [health] ist das Leben **solo vor Skalierung**; die Gruppe rechnet einmalig
     * `health · BossScaling.health(n)` mit n = Zahl der [viewers] jetzt. [capPerHit] = Deckel je Treffer als Anteil der
     * vollen Leiste. Höchstens vier Teilziele erscheinen als Leiste (die ersten vier in Anlege-Reihenfolge).
     */
    fun add(id: String, nameKey: String, health: Float, capPerHit: Float, notches: Int = 0): SubTarget {
        val scaled = health * BossScaling.health(viewers().size.coerceAtLeast(1))
        return SubTarget(id, nameKey, scaled, capPerHit.coerceIn(0.01f, 1f), notches).also {
            it.group = this
            targets.add(it)
        }
    }

    fun get(id: String): SubTarget? = targets.firstOrNull { it.id == id }

    /** Das Wesen von [target], falls es eines gibt und es noch steht. */
    fun entityOf(target: SubTarget): SubTargetEntity? = entities[target.id]?.takeIf { !it.isRemoved }

    /** Ein schlagbares Wesen ohne KI für [target] an [at] (Füße); [kind] wählt das Aussehen, [size] Größe und Trefferbox. */
    fun spawn(target: SubTarget, at: Vec3d, kind: String, size: Float = 1f): SubTargetEntity {
        entities.remove(target.id)?.discard()
        val entity = SubTargetEntity(HhaEntities.SUB_TARGET, world)
        entity.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f)
        entity.kind = kind
        entity.size = size
        entity.group = this
        entity.target = target
        entity.lastGroupTick = world.time
        entity.syncState()
        if (closed || target.broken) return entity // nicht in die Welt: eine geschlossene Gruppe bekommt keine Wesen
        world.spawnEntity(entity)
        entities[target.id] = entity
        if (target.open) opened(target)
        return entity
    }

    /** Treffer über das Wesen (`SubTargetEntity.damage`). */
    internal fun hitByEntity(target: SubTarget, source: DamageSource, amount: Float): Boolean = apply(target, amount, source) > 0f

    /** Der gemeinsame Trefferweg (Reihenfolge oben in der Datei). Liefert den gezählten Schaden. */
    internal fun apply(target: SubTarget, amount: Float, source: DamageSource?): Float {
        if (closed || !target.open || target.broken || amount <= 0f) return 0f
        val attacker = source?.attacker
        val now = world.time
        if (attacker != null && lastHit[attacker.uuid] == now) return 0f
        val counted = if (source != null) handler.onHit(target, source, amount) else amount
        if (counted <= 0f) return 0f
        val taken = target.applyRaw(counted)
        if (taken <= 0f) return 0f
        if (attacker != null) lastHit[attacker.uuid] = now
        val at = placeOf(target) ?: attacker?.entityPos
        if (target.broken) {
            at?.let { sound(HhaSounds.ARENA_SUB_BREAK, it, 1.6f, 0.9f) }
            broke(target, at)
        } else if (now - target.lastHitSound >= HIT_SOUND_GAP) {
            target.lastHitSound = now
            at?.let { sound(HhaSounds.ARENA_SUB_HIT, it, 1.0f, 1.0f + 0.25f * (1f - target.fraction)) }
        }
        return taken
    }

    /** Wurde geöffnet: Wesen auf Gold, eine goldene Rune unter ihm (nur mit Wesen). */
    internal fun opened(target: SubTarget) {
        val entity = entityOf(target) ?: return
        entity.syncState()
        if (Cine.muted) return
        Vfx.rune(world, Vfx.Style.GOLD, entity.entityPos.add(0.0, RUNE_LIFT, 0.0), (entity.size * RUNE_RADIUS).coerceAtLeast(1f), OPEN_RUNE_TICKS, entity)
    }

    private fun broke(target: SubTarget, at: Vec3d?) {
        val entity = entities.remove(target.id)
        if (entity != null) {
            if (!Cine.muted) {
                val center = entity.entityPos.add(0.0, entity.height * 0.5, 0.0)
                Vfx.flare(world, Vfx.Style.GOLD, center, 1.4f * entity.size, 10)
                BossFx.sparks(world, Vfx.Style.GOLD, center, count = BREAK_SPARKS, speed = 0.45f)
            }
            entity.discard()
        } else if (at != null && !Cine.muted) {
            Vfx.flare(world, Vfx.Style.GOLD, at, 1.2f, 10)
        }
        handler.onBroken(target)
    }

    /** Jeden Server-Tick aus dem Kampf: hält die Wesen am Leben, gleicht Zustände ab, schickt geänderte Leisten. */
    fun tick() {
        if (closed) return
        val now = world.time
        val iterator = entities.values.iterator()
        while (iterator.hasNext()) {
            val entity = iterator.next()
            if (entity.isRemoved) {
                iterator.remove()
                continue
            }
            entity.lastGroupTick = now
            entity.syncState()
        }
        if (now % LAST_HIT_PRUNE == 0L) lastHit.values.removeIf { now - it > 1 }
        syncBars(now)
    }

    /** Leisten nur bei Änderung (oder neuen Zuschauern) senden, höchstens alle [SEND_GAP] Ticks; alle [HEARTBEAT] Ticks sicherheitshalber. */
    private fun syncBars(now: Long) {
        if (now - sentAt < SEND_GAP) return
        val bars = targets.take(Cine.MAX_SUB_BARS).map { Cine.SubBar(it.nameKey, it.fraction, it.state, it.notches) }
        val to = viewers().filter { !it.isDisconnected }.toSet()
        val gone = sentTo - to
        if (gone.isNotEmpty()) Cine.subBars(gone.filter { !it.isDisconnected }, emptyList())
        val changed = to != sentTo || !same(bars, sentBars) || now - sentAt >= HEARTBEAT
        if (!changed) return
        Cine.subBars(to, bars, theme)
        sentBars = bars
        sentTo = to
        sentAt = now
    }

    private fun same(a: List<Cine.SubBar>, b: List<Cine.SubBar>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (x.key != y.key || x.state != y.state || x.notches != y.notches || abs(x.fraction - y.fraction) > FRACTION_EPSILON) return false
        }
        return true
    }

    /** Räumt Wesen und Leisten weg. Mehrfaches Schließen schadet nicht. */
    fun close() {
        if (closed) return
        closed = true
        for (entity in entities.values) entity.discard()
        entities.clear()
        val to = (sentTo + viewers()).filter { !it.isDisconnected }
        if (to.isNotEmpty()) Cine.subBars(to, emptyList())
        sentTo = emptySet()
        sentBars = emptyList()
        lastHit.clear()
    }

    private fun placeOf(target: SubTarget): Vec3d? =
        entityOf(target)?.let { it.entityPos.add(0.0, it.height * 0.5, 0.0) } ?: target.anchor

    private fun sound(event: SoundEvent, at: Vec3d, volume: Float, pitch: Float) {
        if (Cine.muted) return
        world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, volume, pitch)
    }

    companion object {
        /** Höchstens ein Treffer-Klang je Teilziel in so vielen Ticks. */
        const val HIT_SOUND_GAP = 4L

        /** Leisten höchstens alle so viele Ticks senden. */
        const val SEND_GAP = 2L

        /** Sicherheitshalber auch ohne Änderung alle so viele Ticks senden (5 s). */
        const val HEARTBEAT = 100L

        /** Anteile, die sich um weniger unterscheiden, gelten als gleich (ein halbes GUI-Pixel bei 72 px). */
        const val FRACTION_EPSILON = 0.004f

        /** Goldene Rune beim Öffnen: Dauer, Radius je `size`, Abstand über dem Boden. */
        const val OPEN_RUNE_TICKS = 40
        const val RUNE_RADIUS = 0.9f
        const val RUNE_LIFT = 0.05

        const val BREAK_SPARKS = 14
        private const val LAST_HIT_PRUNE = 40L
    }
}

/** Kurzform für Kämpfe: Handler aus zwei Lambdas. */
fun subTargetHandler(onHit: (SubTarget, DamageSource, Float) -> Float = { _, _, amount -> amount }, onBroken: (SubTarget) -> Unit = {}): SubTargetHandler =
    object : SubTargetHandler {
        override fun onHit(target: SubTarget, source: DamageSource, amount: Float): Float = onHit(target, source, amount)
        override fun onBroken(target: SubTarget) = onBroken(target)
    }
