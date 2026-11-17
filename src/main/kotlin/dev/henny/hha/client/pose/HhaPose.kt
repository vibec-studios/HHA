package dev.henny.hha.client.pose

import dev.henny.hha.HhaItems
import dev.henny.hha.HhaSounds
import dev.henny.hha.api.rpg.HhaRpg
import dev.henny.hha.client.vfx.fx.FxClient
import dev.henny.hha.client.vfx.fx.FxRegistry
import dev.henny.hha.logic.Vfx
import dev.henny.hha.stage.Pose
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey
import net.minecraft.client.MinecraftClient
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.entity.model.BipedEntityModel
import net.minecraft.client.render.entity.state.BipedEntityRenderState
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.world.ClientWorld
import net.minecraft.component.DataComponentTypes
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.PlayerLikeEntity
import net.minecraft.item.ItemStack
import net.minecraft.sound.SoundCategory
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.random.Random
import kotlin.math.sin

/**
 * Posen und Kostüm der Spieler als Darsteller (PLAN 6.5) und der Figuren in der Lobby (P18). Die Mixins
 * `PlayerEntityRendererMixin` ([extract], Ende von `updateRenderState`) und `BipedEntityModelMixin` ([apply], Ende von
 * `setAngles`) rufen hier an. **Ohne Eintrag verändert HhaPose nichts** (normaler Modus unberührt).
 *
 * Ablauf je Bild: [extract] rechnet aus dem Eintrag des Wesens (Pose, Beginn, Überblendung) einen [Frame] und hängt
 * ihn an den Render-Zustand ([KEY]); [apply] legt ihn nach Vanillas Winkeln auf das Modell. Weil derselbe Zustand an
 * Körper- und Rüstungsmodelle geht, folgen Rüstungsteile von selbst. Der Zustand wird je Bild neu gefüllt; nichts
 * hier hängt an einem Zustand von gestern.
 *
 * Winkel in Bogenmaß, Modellkonvention: Kopf/Arm/Bein-`pitch` negativ = nach vorn/oben, Körper-`pitch` positiv =
 * vorgebeugt, rechter Arm `roll` positiv / linker negativ = abgespreizt. [DROP] in Modell-Pixeln nach unten.
 * Tabelle: [angles], Bericht P05.
 */
object HhaPose {

    // ---------------------------------------------------------------------------------------------
    // Konstanten
    // ---------------------------------------------------------------------------------------------

    /** Überblendung zwischen zwei Posen (auch aus Vanilla und zurück). */
    const val BLEND_TICKS = 6f

    /** Kreisfrequenz des Schwebe-Sinus (rad/Tick): eine Schwingung ≈ 5,2 s. */
    const val FLOAT_FREQ = 0.06f

    /** Kreisfrequenz bei `FLOAT_FAST` (rad/Tick). */
    const val FLOAT_FAST_FREQ = 0.16f

    /** Phasenversatz je Figur (mal [seed]); so schweben Figuren nicht im Gleichtakt. */
    const val SEED_PHASE = 1.7f

    /** Wie weit die Beine bei vorgebeugtem Körper nach hinten rücken (Pixel je sin(Neigung)); Hüfte unter dem Hals. */
    const val LEAN_LEG_SHIFT = 9.6f

    /** Auflösen: Anteil der Dauer bis zum Höhepunkt (dort fällt die Rüstung weg). */
    const val DISSOLVE_PEAK = 0.6f

    /** Auflösen: alle so viele Ticks steigen Funken auf. */
    const val DISSOLVE_SPARK_EVERY = 5

    /** Auflösen: Funken je Stoß (≤ 14, `BossFx.SPARKS_PER_CALL`). */
    const val DISSOLVE_SPARKS = 6

    /** Auflösen: Radius der Lichtschale (Blöcke). */
    const val DISSOLVE_DOME_RADIUS = 1.25f

    /** Ab dieser ID sind Wesen Bühnen-Puppen (`StagePuppets`); ihr Namensschild bleibt aus. */
    const val PUPPET_ID_BASE = 0x7F000000

    // Reihenfolge der Werte in einem Winkel-Feld.
    const val HEAD = 0
    const val BODY = 3
    const val RIGHT_ARM = 6
    const val LEFT_ARM = 9
    const val RIGHT_LEG = 12
    const val LEFT_LEG = 15
    const val DROP = 18
    const val SIZE = 19

    // ---------------------------------------------------------------------------------------------
    // Zustand
    // ---------------------------------------------------------------------------------------------

    /**
     * Was [apply] auflegt: [target] (null = Vanilla-Winkel) und [from] (null = Vanilla) mit Mischung [mix] 0..1.
     * Unveränderlich; je Bild neu.
     */
    class Frame(val target: FloatArray?, val from: FloatArray?, val mix: Float)

    @JvmField
    val KEY: RenderStateDataKey<Frame> = RenderStateDataKey.create { "hha:pose" }

    /** Eine gesetzte Pose. [since]/[until] in Wesens-Zeit (`state.age`), NaN = beim nächsten Bild festlegen. */
    private class Entry(val pose: Pose, val ticks: Int, val from: FloatArray?) {
        var since = Float.NaN
        var until = Float.POSITIVE_INFINITY

        /** Zuletzt gezeigte Winkel der Ziel-Pose (für die Überblendung beim nächsten Wechsel). */
        var lastTarget: FloatArray? = null
    }

    private val poses = HashMap<Int, Entry>()

    /** Ausblenden nach dem Ende einer Pose (zurück zu Vanilla). */
    private class Release(val from: FloatArray) {
        var since = Float.NaN
    }

    private val releases = HashMap<Int, Release>()

    private val costumes = HashMap<Int, String>()

    /** Laufendes Auflösen je Wesen: Beginn (Uhr [clock]), Dauer, Stil. */
    private class Dissolve(val start: Int, val ticks: Int, val style: Vfx.Style)

    private val dissolves = HashMap<Int, Dissolve>()

    /** Eigene Uhr für das Auflösen (Ticks, aus [tick]). */
    private var clock = 0

    /**
     * Schweben (P20, Marke `pose.lift:` der Bühne): Hebung des Wesens in Blöcken von [from] nach [to] über [ticks]
     * (Kurve OUT, `ticks ≤ 1` = sofort), Beginn [start] auf der Uhr [clock]. Nur Darstellung: Der Renderer verschiebt
     * die ganze Figur (`PlayerEntityRendererMixin`), das Wesen bleibt auf dem Server, wo es ist.
     */
    private class Lift(val from: Float, val to: Float, val start: Int, val ticks: Int)

    private val lifts = HashMap<Int, Lift>()

    /** Hebung dieses Bildes in Blöcken (nur gesetzt, wenn ≠ 0); liest `PlayerEntityRendererMixin`. */
    @JvmField
    val LIFT_KEY: RenderStateDataKey<Float> = RenderStateDataKey.create { "hha:lift" }

    /** Über so viele Blöcke Hebung blendet der Bodenschatten der Figur aus. */
    const val LIFT_SHADOW_FADE = 4f

    // ---------------------------------------------------------------------------------------------
    // Öffentlich
    // ---------------------------------------------------------------------------------------------

    /** Setzt [pose] auf einen von Hand gefüllten Zustand (Lobby, Puppen): [time] in Ticks, [seed] entkoppelt Figuren. */
    fun put(state: PlayerEntityRenderState, pose: Pose, time: Float, seed: Int) {
        state.setData(KEY, if (pose == Pose.NONE) null else Frame(angles(pose, time, seed), null, 1f))
    }

    /**
     * Pose [pose] für das Wesen [entityId] über [ticks] (≤ 0 = bis [clearPoses]). Blendet aus der zuletzt gezeigten
     * Pose über [BLEND_TICKS]; [Pose.NONE] blendet zurück zu Vanilla.
     */
    fun pose(entityId: Int, pose: Pose, ticks: Int = 0) {
        val previous = poses.remove(entityId)
        val shown = previous?.lastTarget
        if (pose == Pose.NONE) {
            if (shown != null) releases[entityId] = Release(shown)
            return
        }
        releases.remove(entityId)
        poses[entityId] = Entry(pose, ticks, shown)
    }

    /** Alle Posen sofort aus (Szenenende). */
    fun clearPoses() {
        poses.clear()
        releases.clear()
    }

    /** Kostüm [set] (`heaven`, `hell`, Addon-Weg; "" = aus) für das Wesen [entityId]. */
    fun costume(entityId: Int, set: String) {
        if (set.isEmpty()) {
            costumes.remove(entityId)
            dissolves.remove(entityId)
        } else {
            costumes[entityId] = set
        }
    }

    /** Alle Kostüme (und laufendes Auflösen) aus. */
    fun clearCostumes() {
        costumes.clear()
        dissolves.clear()
    }

    /** Trägt [entityId] gerade ein Kostüm? */
    fun hasCostume(entityId: Int): Boolean = entityId in costumes

    /**
     * Das Kostüm von [entityId] löst sich über [ticks] auf: Lichtschale (`core.dome`) und aufsteigende Funken
     * (`core.sparks`) – Himmel/Addon: Licht ([Vfx.Style.HEAVEN]), Unterwelt: Glut ([Vfx.Style.FIRE]) –, Ton
     * `rpg.dissolve`; die Rüstung fällt auf dem Höhepunkt ([DISSOLVE_PEAK]) weg. Ohne Kostüm: nichts.
     */
    fun dissolve(entityId: Int, ticks: Int) {
        val set = costumes[entityId] ?: return
        if (ticks <= 0) {
            costumes.remove(entityId)
            return
        }
        val style = if (set == "hell") Vfx.Style.FIRE else Vfx.Style.HEAVEN
        dissolves[entityId] = Dissolve(clock, ticks, style)
        val world = MinecraftClient.getInstance().world ?: return
        val entity = world.getEntityById(entityId) ?: return
        val center = entity.entityPos.add(0.0, entity.height * 0.5, 0.0)
        FxRegistry.play(world, "core.dome", style, center, a = DISSOLVE_DOME_RADIUS, life = ticks, entity = entity)
        MinecraftClient.getInstance().soundManager.play(
            PositionedSoundInstance(HhaSounds.RPG_DISSOLVE, SoundCategory.PLAYERS, 0.9f, 1f, Random.create(), center.x, center.y, center.z),
        )
    }

    /**
     * [entityId] schwebt von [from] nach [to] Blöcken über [ticks] (Kurve OUT, `ticks ≤ 1` = sofort); [elapsed] Ticks
     * sind schon vergangen (Nachzügler). `0 → 0` hebt die Hebung auf.
     */
    fun lift(entityId: Int, from: Float, to: Float, ticks: Int, elapsed: Int = 0) {
        if (from == 0f && to == 0f) {
            lifts.remove(entityId)
            return
        }
        lifts[entityId] = Lift(from, to, clock - elapsed.coerceAtLeast(0), ticks)
    }

    /** Alle Hebungen sofort auf 0 (Szenenende). */
    fun clearLifts() = lifts.clear()

    /** Hebung von [entityId] in Blöcken zur Bildzeit ([tickDelta]); 0 ohne Eintrag. */
    @JvmStatic
    fun liftOf(entityId: Int, tickDelta: Float): Float {
        val l = lifts[entityId] ?: return 0f
        if (l.ticks <= 1) return l.to
        val k = ((clock - l.start + tickDelta) / l.ticks).coerceIn(0f, 1f)
        val out = 1f - (1f - k) * (1f - k) * (1f - k)
        return l.from + (l.to - l.from) * out
    }

    /** Größte Hebung des laufenden Eintrags (für die Sichtbox, ohne Bildzeit); 0 ohne Eintrag. */
    @JvmStatic
    fun liftBound(entityId: Int): Float = lifts[entityId]?.let { maxOf(it.from, it.to, 0f) } ?: 0f

    /** Je Client-Tick (aus `StagePlayer.tick`): Uhr, Funken und Ende des Auflösens, abgelaufene Posen. */
    fun tick(world: ClientWorld?) {
        clock++
        // Gelandete Hebungen (Ziel 0, abgelaufen) fallen weg, damit die Sichtbox wieder normal ist.
        if (lifts.isNotEmpty()) lifts.values.removeIf { it.to == 0f && clock - it.start >= it.ticks }
        if (dissolves.isEmpty()) return
        val iterator = dissolves.entries.iterator()
        while (iterator.hasNext()) {
            val (id, d) = iterator.next()
            val age = clock - d.start
            val entity = world?.getEntityById(id)
            if (entity != null && age % DISSOLVE_SPARK_EVERY == 0 && age < d.ticks) {
                FxRegistry.play(
                    world, "core.sparks", d.style, entity.entityPos.add(0.0, entity.height * (0.2 + 0.6 * (age.toFloat() / d.ticks)), 0.0),
                    Vec3d(0.0, 1.0, 0.0), a = DISSOLVE_SPARKS.toFloat(), b = 0.22f, life = 26,
                )
            }
            if (age >= d.ticks * DISSOLVE_PEAK) costumes.remove(id)
            if (age >= d.ticks) iterator.remove()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Mixin-Haken
    // ---------------------------------------------------------------------------------------------

    /** Aus dem Mixin: Pose und Kostüm aus dem Wesen in den Render-Zustand übernehmen. */
    @JvmStatic
    fun extract(entity: PlayerLikeEntity, state: PlayerEntityRenderState) {
        // Läuft in jedem Bild für jeden Spieler: hinter der Schutzschicht, damit ein Fehler kein Bild kippt.
        FxClient.guarded("pose.extract") { extractNow(entity, state) }
    }

    /** Aus dem Mixin: Hebung dieses Bildes in den Render-Zustand (ohne Eintrag: nichts). */
    @JvmStatic
    fun extractLift(entity: PlayerLikeEntity, state: PlayerEntityRenderState, tickDelta: Float) {
        if (lifts.isEmpty() && state.getData(LIFT_KEY) == null) return
        FxClient.guarded("pose.lift") {
            val lift = liftOf(entity.id, tickDelta)
            state.setData(LIFT_KEY, if (lift != 0f) lift else null)
        }
    }

    private fun extractNow(entity: PlayerLikeEntity, state: PlayerEntityRenderState) {
        val id = entity.id
        if (id >= PUPPET_ID_BASE) state.displayName = null
        costumes[id]?.let { dress(state, it) }
        val time = state.age
        val entry = poses[id]
        if (entry != null) {
            state.setData(KEY, frameOf(id, entry, time))
            return
        }
        val release = releases[id]
        if (release != null) {
            if (release.since.isNaN()) release.since = time
            val k = ((time - release.since) / BLEND_TICKS).coerceIn(0f, 1f)
            if (k >= 1f || time < release.since) {
                releases.remove(id)
                state.setData(KEY, null)
            } else {
                state.setData(KEY, Frame(null, release.from, smooth(k)))
            }
            return
        }
        if (state.getData(KEY) != null) state.setData(KEY, null)
    }

    private fun frameOf(id: Int, entry: Entry, time: Float): Frame {
        if (entry.since.isNaN() || time < entry.since - 1f) {
            entry.since = time
            entry.until = if (entry.ticks > 0) time + entry.ticks else Float.POSITIVE_INFINITY
        }
        if (time >= entry.until) {
            // Abgelaufen: zurück zu Vanilla, aus der zuletzt gezeigten Pose.
            poses.remove(id)
            val shown = angles(entry.pose, time, id)
            releases[id] = Release(shown).also { it.since = time }
            return Frame(null, shown, 0f)
        }
        val target = angles(entry.pose, time, id)
        entry.lastTarget = target
        val k = ((time - entry.since) / BLEND_TICKS).coerceIn(0f, 1f)
        return Frame(target, entry.from, smooth(k))
    }

    /** Aus dem Mixin: Winkel der Pose auf das Modell legen (nach Vanillas `setAngles`). */
    @JvmStatic
    fun apply(model: BipedEntityModel<*>, state: BipedEntityRenderState) {
        val frame = state.getData(KEY) ?: return
        FxClient.guarded("pose.apply") { applyNow(model, frame) }
    }

    private fun applyNow(model: BipedEntityModel<*>, frame: Frame) {
        val target = frame.target
        val from = frame.from
        val mix = frame.mix
        val vanillaBodyPitch = model.body.pitch
        fun part(p: ModelPart, base: Int) {
            p.pitch = blend(p.pitch, from, target, base, mix)
            p.yaw = blend(p.yaw, from, target, base + 1, mix)
            p.roll = blend(p.roll, from, target, base + 2, mix)
        }
        part(model.head, HEAD)
        part(model.body, BODY)
        part(model.rightArm, RIGHT_ARM)
        part(model.leftArm, LEFT_ARM)
        part(model.rightLeg, RIGHT_LEG)
        part(model.leftLeg, LEFT_LEG)
        // Gesenkte Haltung (Knien, Landen): alle Teile nach unten. Vanilla hat hier 0.
        val drop = blend(0f, from, target, DROP, mix)
        if (drop != 0f) {
            for (p in listOf(model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg)) p.originY += drop
        }
        // Vorgebeugt: die Hüfte liegt unter dem Hals, also rücken die Beine nach hinten (+z), wie beim Schleichen.
        val shift = LEAN_LEG_SHIFT * (sin(model.body.pitch) - sin(vanillaBodyPitch))
        if (shift != 0f) {
            model.rightLeg.originZ += shift
            model.leftLeg.originZ += shift
        }
    }

    private fun blend(vanilla: Float, from: FloatArray?, target: FloatArray?, i: Int, mix: Float): Float {
        val a = from?.get(i) ?: vanilla
        val b = target?.get(i) ?: vanilla
        return a + (b - a) * mix
    }

    private fun smooth(k: Float): Float = k * k * (3f - 2f * k)

    // ---------------------------------------------------------------------------------------------
    // Posen-Tabelle
    // ---------------------------------------------------------------------------------------------

    /**
     * Winkel der Pose [pose] zur Zeit [time] (Ticks) für die Figur [seed]. Ruhige Posen haben einen kleinen Atem-Sinus,
     * die Schwebe-Posen einen langsamen Sinus je Figur. Werte im Bericht P05 (Posen-Tabelle).
     */
    fun angles(pose: Pose, time: Float, seed: Int): FloatArray {
        val a = FloatArray(SIZE)
        val phase = seed * SEED_PHASE
        val s = sin(time * FLOAT_FREQ + phase)
        val c = sin(time * FLOAT_FREQ * 0.73f + phase + 1.3f)
        val breath = sin(time * 0.09f + phase) * 0.03f
        fun set(base: Int, pitch: Float, yaw: Float = 0f, roll: Float = 0f) {
            a[base] = pitch; a[base + 1] = yaw; a[base + 2] = roll
        }
        when (pose) {
            Pose.NONE -> {}
            Pose.FLOAT -> {
                // Arme leicht abgespreizt, Beine versetzt, alles mit langsamem Sinus.
                set(HEAD, -0.08f + s * 0.03f, c * 0.05f)
                set(BODY, 0.02f * c)
                set(RIGHT_ARM, 0.06f + s * 0.05f, 0f, 0.28f + c * 0.05f)
                set(LEFT_ARM, 0.06f - s * 0.05f, 0f, -0.28f - c * 0.05f)
                set(RIGHT_LEG, 0.14f + s * 0.06f, 0f, 0.05f)
                set(LEFT_LEG, -0.10f - s * 0.06f, 0f, -0.05f)
            }
            Pose.FLOAT_FAST -> {
                // Nach vorn geneigt, Arme zurück, Beine schleifen nach.
                val f = sin(time * FLOAT_FAST_FREQ + phase)
                set(HEAD, -0.32f + f * 0.02f)
                set(BODY, 0.35f)
                set(RIGHT_ARM, 0.55f + f * 0.06f, 0f, 0.18f)
                set(LEFT_ARM, 0.55f - f * 0.06f, 0f, -0.18f)
                set(RIGHT_LEG, 0.28f + f * 0.08f, 0f, 0.04f)
                set(LEFT_LEG, 0.38f - f * 0.08f, 0f, -0.04f)
            }
            Pose.LAND -> {
                set(HEAD, -0.20f)
                set(BODY, 0.30f)
                set(RIGHT_ARM, -0.35f, 0f, 0.38f)
                set(LEFT_ARM, -0.30f, 0f, -0.38f)
                set(RIGHT_LEG, -0.30f, 0f, 0.06f)
                set(LEFT_LEG, -0.05f, 0f, -0.06f)
                a[DROP] = 2f
            }
            Pose.KNEEL -> {
                // Auf ein Knie: linker Oberschenkel waagerecht nach vorn, rechtes Bein nach hinten, Kopf gesenkt.
                set(HEAD, 0.28f + breath)
                set(BODY, 0.12f)
                set(RIGHT_ARM, -0.25f, 0f, 0.08f)
                set(LEFT_ARM, -0.55f, 0.25f, -0.05f)
                set(RIGHT_LEG, 0.35f, 0f, 0.04f)
                set(LEFT_LEG, -1.35f, -0.10f, 0f)
                a[DROP] = 5f
            }
            Pose.RISE -> {
                set(HEAD, -0.25f + breath)
                set(BODY, -0.05f)
                set(RIGHT_ARM, 0.12f, 0f, 0.14f)
                set(LEFT_ARM, 0.12f, 0f, -0.14f)
            }
            Pose.LOOK_UP -> {
                set(HEAD, -0.75f + breath)
                set(BODY, -0.12f)
                set(RIGHT_ARM, 0.10f, 0f, 0.12f)
                set(LEFT_ARM, 0.10f, 0f, -0.12f)
            }
            Pose.LOOK_HANDS -> {
                // Beide Hände vor der Brust, der Blick ruht darauf.
                set(HEAD, 0.65f + breath)
                set(BODY, 0.10f)
                set(RIGHT_ARM, -0.95f + breath, -0.40f, 0f)
                set(LEFT_ARM, -0.95f - breath, 0.40f, 0f)
            }
            Pose.ARMS_WIDE -> {
                set(HEAD, -0.30f + breath)
                set(BODY, -0.08f)
                set(RIGHT_ARM, -0.20f, 0f, 1.25f + breath)
                set(LEFT_ARM, -0.20f, 0f, -1.25f - breath)
            }
            Pose.GUARD -> {
                set(HEAD, 0.05f, 0.15f)
                set(BODY, 0.08f, 0.25f)
                set(RIGHT_ARM, -0.90f + breath, -0.20f, 0f)
                set(LEFT_ARM, -0.60f - breath, 0.30f, 0f)
                set(RIGHT_LEG, 0.25f, 0f, 0.08f)
                set(LEFT_LEG, -0.20f, 0f, -0.08f)
                a[DROP] = 1f
            }
            Pose.POINT -> {
                set(HEAD, -0.05f, -0.05f)
                set(RIGHT_ARM, -1.55f, -0.05f, 0f)
                set(LEFT_ARM, 0.10f + breath, 0f, -0.10f)
            }
            Pose.BOW -> {
                set(HEAD, 0.35f)
                set(BODY, 0.55f)
                set(RIGHT_ARM, -0.40f, -0.30f, 0f)
                set(LEFT_ARM, 0.25f, 0f, -0.10f)
            }
            Pose.STAGGER -> {
                val w = sin(time * 0.35f + phase)
                set(HEAD, -0.25f + w * 0.05f, 0.20f)
                set(BODY, -0.20f, 0f, 0.12f + w * 0.04f)
                set(RIGHT_ARM, 0.30f, 0f, 0.60f + w * 0.08f)
                set(LEFT_ARM, -0.40f, 0f, -0.35f - w * 0.08f)
                set(RIGHT_LEG, 0.35f, 0f, 0.06f)
                set(LEFT_LEG, -0.20f, 0f, -0.04f)
            }
            Pose.CHEER -> {
                val b = sin(time * 0.45f + phase)
                set(HEAD, -0.35f)
                set(BODY, -0.08f)
                set(RIGHT_ARM, -2.90f + b * 0.15f, 0f, 0.15f)
                set(LEFT_ARM, -2.60f - b * 0.15f, 0f, -0.25f)
            }
        }
        return a
    }

    // ---------------------------------------------------------------------------------------------
    // Kostüm
    // ---------------------------------------------------------------------------------------------

    private val sets = HashMap<String, Array<ItemStack>>()

    /** Setzt nur die vier Rüstungsplätze im Render-Zustand (Inventar bleibt unberührt). */
    private fun dress(state: PlayerEntityRenderState, set: String) {
        val stacks = sets.getOrPut(set) { stacksOf(set) }
        state.equippedHeadStack = stacks[0]
        state.equippedChestStack = stacks[1]
        state.equippedLegsStack = stacks[2]
        state.equippedFeetStack = stacks[3]
    }

    /** Kopf, Brust, Beine, Füße eines Sets: `heaven`, `hell`, sonst `HhaRpg.Path.costume` (Rückfall: Heaven-Set). */
    private fun stacksOf(set: String): Array<ItemStack> = when (set) {
        "hell" -> arrayOf(ItemStack(HhaItems.HELL_HELMET), ItemStack(HhaItems.HELL_CHESTPLATE), ItemStack(HhaItems.HELL_LEGGINGS), ItemStack(HhaItems.HELL_BOOTS))
        "heaven" -> heavenSet()
        else -> addonSet(set) ?: heavenSet()
    }

    private fun heavenSet() =
        arrayOf(ItemStack(HhaItems.HEAVEN_HELMET), ItemStack(HhaItems.HEAVEN_CHESTPLATE), ItemStack(HhaItems.HEAVEN_LEGGINGS), ItemStack(HhaItems.HEAVEN_BOOTS))

    /**
     * Rüstung eines Addon-Wegs aus `HhaRpg.Path.costume: () -> List<ItemStack>`. Liefert der Weg nichts, gilt der
     * Rückfall (Heaven-Set).
     */
    private fun addonSet(set: String): Array<ItemStack>? {
        val items = HhaRpg.path(set)?.costume?.invoke() ?: return null
        if (items.isEmpty()) return null
        val out = Array(4) { ItemStack.EMPTY }
        for (stack in items) {
            when (stack.get(DataComponentTypes.EQUIPPABLE)?.slot()) {
                EquipmentSlot.HEAD -> out[0] = stack
                EquipmentSlot.CHEST -> out[1] = stack
                EquipmentSlot.LEGS -> out[2] = stack
                EquipmentSlot.FEET -> out[3] = stack
                else -> {}
            }
        }
        return out
    }

    /** Verbindungsende: alles vergessen. */
    fun reset() {
        clearPoses()
        clearCostumes()
        clearLifts()
        sets.clear()
    }
}
