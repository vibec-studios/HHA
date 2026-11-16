package dev.henny.hha.client.vfx

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import dev.henny.hha.Hha
import dev.henny.hha.HhaBlocks
import dev.henny.hha.HhaSounds
import dev.henny.hha.client.HhaClientConfig
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.block.BlockState
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderSetup
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.VertexFormats
import net.minecraft.client.sound.AbstractSoundInstance
import net.minecraft.client.sound.SoundInstance
import net.minecraft.client.world.ClientWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import net.minecraft.world.chunk.WorldChunk
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Darstellung der beiden Tore als **Parallaxe-Fenster** (PLAN 7.2): Je Portalblock ein deckendes Quad auf der Seite
 * der Kamera, das der Shader `hha:core/portal` mit Tiefenebenen füllt, die sich mit dem Blickwinkel gegeneinander
 * verschieben (Wolkentor: Himmel, Wolkenbank, Insel mit Tempel, Wolkenfetzen; Höllentor: roter Himmel, Palastkuppel
 * mit Ketten, Felssplitter, Glut). Davor wenige Quads echte Geometrie: am Wolkentor vier Lichtkeile und zwei
 * Lichtflecken am Boden (durchscheinend, nicht additiv), am Höllentor sechs dunkle Splitter um den Rand.
 *
 * - **Register:** je Tor Art, Achse, Zellen, Mitte. Gefüllt beim Laden eines Chunks (`ClientChunkEvents`, je Abschnitt
 *   erst `hasAny`) und über `randomDisplayTick` der Blöcke ([note]); ein Tor fällt heraus, wenn sein Chunk entladen
 *   wird oder ein Block fehlt. Höchstens [MAX_DRAWN] Tore werden gezeichnet (nächste zuerst).
 * - **Zeichnen:** eigene Pipelines, unabhängig von `/hhac vfx_3d`; die Fenster vor der durchscheinenden Welt (Glas und
 *   Wasser davor mischen richtig), die Lichtkeile am Ende. Aus mit `/hhac toggle portal_shader false` oder wenn der
 *   Shader nicht lädt (einmal geloggt): Dann bleibt die animierte Blocktextur sichtbar.
 * - **Ton:** Summen der beiden nächsten Tore.
 */
object PortalVfx {

    enum class Kind { OLYMPUS, TARTAROS }

    /** Höchstens so viele Tore gleichzeitig zeichnen. */
    const val MAX_DRAWN = 16

    /** Aufblenden eines neu gefundenen Tors (Ticks). */
    private const val FADE_TICKS = 24f

    /** Größte Torfläche, die das Register annimmt (Zellen). */
    private const val MAX_CELLS = 600

    /** Summen: Reichweite und Zahl gleichzeitig. */
    private const val HUM_RANGE = 34.0
    private const val MAX_HUMS = 2

    /** Stärke der Lichtkeile (Alpha, PLAN 7.2: höchstens 0,22). */
    private const val BEAM_ALPHA = 0.22f

    // ---------------------------------------------------------------------------------------------
    // Pipelines (eigene, nicht in VfxLayers; nicht global registriert, damit ein Shaderfehler nur die Tore trifft)
    // ---------------------------------------------------------------------------------------------

    private val BASE: RenderPipeline.Snippet = RenderPipeline.builder(
        RenderPipelines.TRANSFORMS_PROJECTION_FOG_SNIPPET,
        RenderPipelines.GLOBALS_SNIPPET,
    )
        .withVertexShader(Hha.id("core/portal"))
        .withFragmentShader(Hha.id("core/portal"))
        .withCull(false)
        .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
        .buildSnippet()

    private fun solid(name: String, define: String): RenderPipeline = RenderPipeline.builder(BASE)
        .withLocation(Hha.id("pipeline/$name"))
        .withShaderDefine(define)
        .withoutBlend()
        .withDepthWrite(true)
        .build()

    private val OLYMPUS_PIPELINE = solid("portal_olympus", "PORTAL_OLYMPUS")
    private val TARTAROS_PIPELINE = solid("portal_tartaros", "PORTAL_TARTAROS")
    private val SHARD_PIPELINE = solid("portal_shard", "PORTAL_SHARD")
    private val BEAM_PIPELINE: RenderPipeline = RenderPipeline.builder(BASE)
        .withLocation(Hha.id("pipeline/portal_beam"))
        .withShaderDefine("PORTAL_BEAM")
        .withBlend(BlendFunction.TRANSLUCENT)
        .withDepthWrite(false)
        .build()

    private val PIPELINES = listOf(OLYMPUS_PIPELINE, TARTAROS_PIPELINE, SHARD_PIPELINE, BEAM_PIPELINE)

    private fun layer(name: String, pipeline: RenderPipeline, size: Int, translucent: Boolean = false): RenderLayer {
        val setup = RenderSetup.builder(pipeline).expectedBufferSize(size)
        if (translucent) setup.translucent()
        return RenderLayer.of("hha_$name", setup.build())
    }

    private val OLYMPUS_LAYER = layer("portal_olympus", OLYMPUS_PIPELINE, 16384)
    private val TARTAROS_LAYER = layer("portal_tartaros", TARTAROS_PIPELINE, 16384)
    private val SHARD_LAYER = layer("portal_shard", SHARD_PIPELINE, 2048)
    private val BEAM_LAYER = layer("portal_beam", BEAM_PIPELINE, 2048, translucent = true)

    /** Shader nicht ladbar: einmal geloggt, danach kein neuer Versuch. */
    private var failed = false

    private fun ready(): Boolean {
        if (failed) return false
        return try {
            val device = RenderSystem.getDevice()
            val ok = PIPELINES.all { device.precompilePipeline(it).isValid }
            if (!ok) {
                failed = true
                Hha.LOGGER.error("HHA: Portal-Shader hha:core/portal ließ sich nicht laden; die Tore zeigen nur ihre Blocktextur.")
            }
            ok
        } catch (e: Exception) {
            failed = true
            Hha.LOGGER.error("HHA: Portal-Pipelines ließen sich nicht anlegen; die Tore zeigen nur ihre Blocktextur.", e)
            false
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Register
    // ---------------------------------------------------------------------------------------------

    private class Hum(sound: SoundEvent, pos: Vec3d) : AbstractSoundInstance(sound, SoundCategory.BLOCKS, SoundInstance.createRandom()) {
        init {
            repeat = true
            repeatDelay = 0
            volume = 1.0f
            attenuationType = SoundInstance.AttenuationType.LINEAR
            x = pos.x
            y = pos.y
            z = pos.z
        }
    }

    private class Gate(val kind: Kind, val axis: Direction.Axis, val cells: List<BlockPos>) {
        val keys: Set<Long> = cells.mapTo(HashSet()) { it.asLong() }
        val chunks: Set<Long> = cells.mapTo(HashSet()) { ChunkPos.toLong(it.x shr 4, it.z shr 4) }
        val minT = cells.minOf { along(it) }
        val maxT = cells.maxOf { along(it) }
        val minY = cells.minOf { it.y }
        val maxY = cells.maxOf { it.y }

        /** Lage der Ebene (z bei Achse X, x bei Achse Z): Blockkoordinate. */
        val plane = if (axis == Direction.Axis.X) cells[0].z else cells[0].x
        val halfW = (maxT - minT + 1) / 2.0
        val halfH = (maxY - minY + 1) / 2.0
        val centerT = minT + halfW
        val centerY = minY + halfH
        val center: Vec3d = if (axis == Direction.Axis.X) Vec3d(centerT, centerY, plane + 0.5) else Vec3d(plane + 0.5, centerY, centerT)
        val seed = ((minT * 73_856_093) xor (minY * 19_349_663) xor (plane * 83_492_791)).let { (it ushr 3) and 127 }
        var age = 0
        var hum: Hum? = null

        /** Bodenhöhe vor dem Tor je Seite (+n, −n), für Lichtkeile; `null` = kein Boden gefunden. */
        var groundPlus: Double? = null
        var groundMinus: Double? = null

        /** Wie viele volle Blöcke die Torwand über die Ebene hinaus je Seite dick ist (Splitter liegen davor). */
        var wallPlus = 0
        var wallMinus = 0

        fun along(pos: BlockPos): Int = if (axis == Direction.Axis.X) pos.x else pos.z
    }

    private val gates = ArrayList<Gate>()
    private val index = HashMap<Long, Gate>()
    private var lastWorld: World? = null
    private var validateCursor = 0
    private var initialized = false

    private fun kindOf(state: BlockState): Kind? = when {
        state.isOf(HhaBlocks.OLYMPUS_PORTAL) -> Kind.OLYMPUS
        state.isOf(HhaBlocks.HADES_PORTAL) -> Kind.TARTAROS
        else -> null
    }

    /** Von `HhaClient` beim Start: Chunk-Ereignisse und die beiden Zeichenpunkte. */
    fun init() {
        if (initialized) return
        initialized = true
        ClientChunkEvents.CHUNK_LOAD.register { world, chunk -> scanChunk(world, chunk) }
        ClientChunkEvents.CHUNK_UNLOAD.register { world, chunk -> if (world === lastWorld) dropChunk(chunk.pos.toLong()) }
        WorldRenderEvents.BEFORE_TRANSLUCENT.register { context -> renderSolid(context.consumers()) }
        WorldRenderEvents.END_MAIN.register { context -> renderBeams(context.consumers()) }
    }

    /** Vom Block (randomDisplayTick, nur Client): Tor anmelden, falls noch unbekannt. */
    fun note(world: World, pos: BlockPos) {
        if (world !is ClientWorld) return
        switchWorld(world)
        if (!index.containsKey(pos.asLong())) discover(world, pos)
    }

    private fun switchWorld(world: World) {
        if (world === lastWorld) return
        clear()
        lastWorld = world
    }

    private fun clear() {
        val sounds = MinecraftClient.getInstance().soundManager
        for (gate in gates) gate.hum?.let { sounds.stop(it) }
        gates.clear()
        index.clear()
    }

    private fun scanChunk(world: ClientWorld, chunk: WorldChunk) {
        switchWorld(world)
        val sections = chunk.sectionArray
        val baseX = chunk.pos.startX
        val baseZ = chunk.pos.startZ
        for (i in sections.indices) {
            val section = sections[i] ?: continue
            if (section.isEmpty || !section.hasAny { kindOf(it) != null }) continue
            val baseY = world.sectionIndexToCoord(i) shl 4
            for (y in 0..15) for (z in 0..15) for (x in 0..15) {
                if (kindOf(section.getBlockState(x, y, z)) == null) continue
                val pos = BlockPos(baseX + x, baseY + y, baseZ + z)
                if (!index.containsKey(pos.asLong())) discover(world, pos)
            }
        }
    }

    private fun dropChunk(chunk: Long) {
        val sounds = MinecraftClient.getInstance().soundManager
        val iterator = gates.iterator()
        while (iterator.hasNext()) {
            val gate = iterator.next()
            if (chunk !in gate.chunks) continue
            gate.hum?.let { sounds.stop(it) }
            gate.keys.forEach { index.remove(it) }
            iterator.remove()
        }
    }

    /** Sucht ab [start] alle zusammenhängenden Zellen derselben Art und Achse in der Torebene. */
    private fun discover(world: World, start: BlockPos) {
        val state = world.getBlockState(start)
        val kind = kindOf(state) ?: return
        val axis = state.get(Properties.HORIZONTAL_AXIS)
        val along = if (axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH
        val directions = arrayOf(along, along.opposite, Direction.UP, Direction.DOWN)
        val seen = HashSet<Long>()
        val cells = ArrayList<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        queue.add(start.toImmutable())
        seen.add(start.asLong())
        while (queue.isNotEmpty() && cells.size < MAX_CELLS) {
            val pos = queue.removeFirst()
            cells.add(pos)
            for (direction in directions) {
                val next = pos.offset(direction)
                if (!seen.add(next.asLong())) continue
                val other = world.getBlockState(next)
                if (kindOf(other) == kind && other.get(Properties.HORIZONTAL_AXIS) == axis) queue.add(next)
            }
        }
        val gate = Gate(kind, axis, cells)
        // Überlappende (z. B. halb geladene) Tore gehen im neuen auf; das Aufblenden läuft weiter.
        val sounds = MinecraftClient.getInstance().soundManager
        val iterator = gates.iterator()
        while (iterator.hasNext()) {
            val old = iterator.next()
            if (old.keys.none { it in gate.keys }) continue
            gate.age = max(gate.age, old.age)
            old.hum?.let { sounds.stop(it) }
            old.keys.forEach { index.remove(it) }
            iterator.remove()
        }
        findGround(world, gate)
        measureWall(world, gate)
        gates.add(gate)
        gate.keys.forEach { index[it] = gate }
    }

    private fun findGround(world: World, gate: Gate) {
        if (gate.kind != Kind.OLYMPUS) return
        val normal = normalOf(gate.axis)
        fun probe(side: Int): Double? {
            val front = gate.center.add(normal.multiply(side * 2.0))
            for (drop in 0..5) {
                val pos = BlockPos.ofFloored(front.x, gate.minY - 0.5 - drop, front.z)
                if (world.getBlockState(pos).isSideSolidFullSquare(world, pos, Direction.UP)) return pos.y + 1.0
            }
            return null
        }
        gate.groundPlus = probe(1)
        gate.groundMinus = probe(-1)
    }

    /** Wanddicke über dem Scheitel des Tors (am Dreiweg drei Blöcke, bei den Rücktoren einer). */
    private fun measureWall(world: World, gate: Gate) {
        if (gate.kind != Kind.TARTAROS) return
        val normal = if (gate.axis == Direction.Axis.X) Direction.SOUTH else Direction.EAST
        val column = kotlin.math.floor(gate.centerT).toInt()
        val crown = if (gate.axis == Direction.Axis.X) BlockPos(column, gate.maxY + 1, gate.plane)
        else BlockPos(gate.plane, gate.maxY + 1, column)
        fun depth(side: Int): Int {
            var k = 0
            while (k < 3 && world.getBlockState(crown.offset(normal, side * (k + 1))).isOpaqueFullCube) k++
            return k
        }
        gate.wallPlus = depth(1)
        gate.wallMinus = depth(-1)
    }

    fun tick(world: ClientWorld) {
        switchWorld(world)
        if (gates.isEmpty()) return
        val client = MinecraftClient.getInstance()
        for (gate in gates) gate.age++
        // Zwei Tore je Tick prüfen: fehlt ein Block (bei geladenem Chunk), wird das Tor neu vermessen.
        repeat(min(2, gates.size)) {
            validateCursor = (validateCursor + 1) % gates.size
            val gate = gates[validateCursor]
            val broken = gate.cells.any { world.isChunkLoaded(it.x shr 4, it.z shr 4) && kindOf(world.getBlockState(it)) != gate.kind }
            if (broken) {
                gate.hum?.let { client.soundManager.stop(it) }
                gate.keys.forEach { index.remove(it) }
                gates.remove(gate)
                gate.cells.firstOrNull { kindOf(world.getBlockState(it)) == gate.kind }?.let { discover(world, it) }
                return
            }
        }
        // Summen: nur die beiden nächsten Tore in Reichweite.
        val player = client.player ?: return
        val humming = gates.filter { player.squaredDistanceTo(it.center) < HUM_RANGE * HUM_RANGE }
            .sortedBy { player.squaredDistanceTo(it.center) }
            .take(MAX_HUMS)
        for (gate in gates) {
            val hum = gate.hum
            if (gate in humming) {
                if (hum == null || !client.soundManager.isPlaying(hum)) {
                    val sound = if (gate.kind == Kind.OLYMPUS) HhaSounds.OLYMPUS_PORTAL_HUM else HhaSounds.PORTAL_HUM
                    gate.hum = Hum(sound, gate.center).also { client.soundManager.play(it) }
                }
            } else if (hum != null) {
                client.soundManager.stop(hum)
                gate.hum = null
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Zeichnen
    // ---------------------------------------------------------------------------------------------

    private fun active(): Boolean = gates.isNotEmpty() && HhaClientConfig.enabled("portal_shader") && ready()

    private fun nearest(cam: Vec3d): List<Gate> =
        if (gates.size <= MAX_DRAWN) gates else gates.sortedBy { it.center.squaredDistanceTo(cam) }.take(MAX_DRAWN)

    private fun normalOf(axis: Direction.Axis): Vec3d = if (axis == Direction.Axis.X) Vec3d(0.0, 0.0, 1.0) else Vec3d(1.0, 0.0, 0.0)

    private fun alongOf(axis: Direction.Axis): Vec3d = if (axis == Direction.Axis.X) Vec3d(1.0, 0.0, 0.0) else Vec3d(0.0, 0.0, 1.0)

    private fun VertexConsumer.put(p: Vec3d, cam: Vec3d, u: Float, v: Float, color: Int) {
        vertex((p.x - cam.x).toFloat(), (p.y - cam.y).toFloat(), (p.z - cam.z).toFloat()).texture(u, v).color(color)
    }

    private fun fadeOf(gate: Gate, tickDelta: Float): Float = ((gate.age + tickDelta) / FADE_TICKS).coerceIn(0f, 1f)

    /** Fenster und Splitter (deckend, mit Tiefe), vor der durchscheinenden Welt. */
    private fun renderSolid(consumers: VertexConsumerProvider) {
        if (!active()) return
        val client = MinecraftClient.getInstance()
        val world = client.world ?: return
        val cam = client.gameRenderer.camera.cameraPos
        val tickDelta = client.renderTickCounter.getTickProgress(false)
        val seconds = ((world.time % 24000L) + tickDelta) / 20.0
        val drawn = nearest(cam)
        for (gate in drawn) {
            val layer = if (gate.kind == Kind.OLYMPUS) OLYMPUS_LAYER else TARTAROS_LAYER
            drawWindow(consumers.getBuffer(layer), gate, cam, fadeOf(gate, tickDelta))
        }
        for (gate in drawn) {
            if (gate.kind == Kind.TARTAROS) drawShards(consumers.getBuffer(SHARD_LAYER), gate, cam, fadeOf(gate, tickDelta), seconds)
        }
        // Gleich zeichnen: So liegen Glas und Wasser vor dem Tor richtig darüber.
        if (consumers is VertexConsumerProvider.Immediate) {
            consumers.draw(OLYMPUS_LAYER)
            consumers.draw(TARTAROS_LAYER)
            consumers.draw(SHARD_LAYER)
        }
    }

    /** Lichtkeile und Lichtflecken vor den Wolkentoren (durchscheinend, nach der Welt). */
    private fun renderBeams(consumers: VertexConsumerProvider) {
        if (!active()) return
        val client = MinecraftClient.getInstance()
        val cam = client.gameRenderer.camera.cameraPos
        val tickDelta = client.renderTickCounter.getTickProgress(false)
        for (gate in nearest(cam)) {
            if (gate.kind == Kind.OLYMPUS) drawBeams(consumers.getBuffer(BEAM_LAYER), gate, cam, fadeOf(gate, tickDelta))
        }
    }

    /** Ein Quad je Zelle, 0,01 vor der Blockscheibe (6–10/16) auf der Kameraseite; in der Ferne etwas mehr Abstand. */
    private fun drawWindow(vc: VertexConsumer, gate: Gate, cam: Vec3d, fade: Float) {
        val toward = if (gate.axis == Direction.Axis.X) cam.z - (gate.plane + 0.5) else cam.x - (gate.plane + 0.5)
        val distance = gate.center.distanceTo(cam)
        val gap = min(0.01 + distance * 0.0004, 0.2)
        val face = if (toward >= 0.0) gate.plane + 10.0 / 16.0 + gap else gate.plane + 6.0 / 16.0 - gap
        val code = (if (gate.axis == Direction.Axis.Z) 128 else 0) or gate.seed
        val g = (gate.halfW * 16.0).toInt().coerceIn(0, 255)
        val b = (gate.halfH * 16.0).toInt().coerceIn(0, 255)
        val a = (fade * 255f).toInt().coerceIn(0, 255)
        val color = (a shl 24) or (code shl 16) or (g shl 8) or b
        for (cell in gate.cells) {
            val t0 = gate.along(cell).toDouble()
            val y0 = cell.y.toDouble()
            val u0 = (t0 - gate.centerT).toFloat()
            val u1 = (t0 + 1.0 - gate.centerT).toFloat()
            val v0 = (y0 - gate.centerY).toFloat()
            val v1 = (y0 + 1.0 - gate.centerY).toFloat()
            if (gate.axis == Direction.Axis.X) {
                vc.put(Vec3d(t0, y0, face), cam, u0, v0, color)
                vc.put(Vec3d(t0 + 1.0, y0, face), cam, u1, v0, color)
                vc.put(Vec3d(t0 + 1.0, y0 + 1.0, face), cam, u1, v1, color)
                vc.put(Vec3d(t0, y0 + 1.0, face), cam, u0, v1, color)
            } else {
                vc.put(Vec3d(face, y0, t0), cam, u0, v0, color)
                vc.put(Vec3d(face, y0, t0 + 1.0), cam, u1, v0, color)
                vc.put(Vec3d(face, y0 + 1.0, t0 + 1.0), cam, u1, v1, color)
                vc.put(Vec3d(face, y0 + 1.0, t0), cam, u0, v1, color)
            }
        }
    }

    /**
     * Wolkentor: je Seite zwei Lichtkeile aus dem oberen Teil der Öffnung schräg auf den Boden (zur Kamera gedrehte
     * Bänder, unten breiter) und ein weicher Lichtfleck am Boden. Zusammen sechs Quads.
     */
    private fun drawBeams(vc: VertexConsumer, gate: Gate, cam: Vec3d, fade: Float) {
        val normal = normalOf(gate.axis)
        val along = alongOf(gate.axis)
        val up = Vec3d(0.0, 1.0, 0.0)
        val alpha = (BEAM_ALPHA * fade * 255f).toInt().coerceIn(0, 255)
        val seedByte = (gate.seed * 2).coerceIn(0, 255)
        val beamColor = (alpha shl 24) or seedByte
        val poolColor = (alpha shl 24) or (255 shl 16) or seedByte
        for (side in intArrayOf(1, -1)) {
            val ground = (if (side > 0) gate.groundPlus else gate.groundMinus) ?: gate.minY.toDouble()
            for (lateral in doubleArrayOf(-0.45, 0.45)) {
                val start = gate.center.add(along.multiply(lateral * gate.halfW)).add(up.multiply(0.30 * gate.halfH))
                    .add(normal.multiply(side * 0.2))
                val endBase = gate.center.add(along.multiply(lateral * gate.halfW * 1.35)).add(normal.multiply(side * (1.0 + 1.1 * gate.halfH)))
                val end = Vec3d(endBase.x, ground + 0.05, endBase.z)
                ribbon(vc, cam, start, end, 0.30 * gate.halfW, 0.55 * gate.halfW, beamColor)
            }
            val poolCenter = gate.center.add(normal.multiply(side * (0.6 + 0.9 * gate.halfH)))
            val y = ground + 0.03
            val a = along.multiply(gate.halfW * 1.1)
            val n = normal.multiply(gate.halfH * 0.55)
            val c = Vec3d(poolCenter.x, y, poolCenter.z)
            vc.put(c.subtract(a).subtract(n), cam, 0f, 0f, poolColor)
            vc.put(c.add(a).subtract(n), cam, 1f, 0f, poolColor)
            vc.put(c.add(a).add(n), cam, 1f, 1f, poolColor)
            vc.put(c.subtract(a).add(n), cam, 0f, 1f, poolColor)
        }
    }

    /** Zur Kamera gedrehtes Band von [start] (Breite [w0]) nach [end] (Breite [w1]); UV.x entlang, UV.y quer. */
    private fun ribbon(vc: VertexConsumer, cam: Vec3d, start: Vec3d, end: Vec3d, w0: Double, w1: Double, color: Int) {
        val axis = end.subtract(start)
        val mid = start.add(end).multiply(0.5)
        var across = axis.crossProduct(cam.subtract(mid))
        if (across.lengthSquared() < 1.0e-6) across = Vec3d(1.0, 0.0, 0.0)
        across = across.normalize()
        vc.put(start.subtract(across.multiply(w0)), cam, 0f, 0f, color)
        vc.put(start.add(across.multiply(w0)), cam, 0f, 1f, color)
        vc.put(end.add(across.multiply(w1)), cam, 1f, 1f, color)
        vc.put(end.subtract(across.multiply(w1)), cam, 1f, 0f, color)
    }

    /**
     * Höllentor: sechs dunkle Splitter kreisen langsam am Rand der Öffnung, knapp vor dem Rahmen auf der Kameraseite.
     * Je Splitter zwei gekreuzte Quads (zusammen zwölf).
     */
    private fun drawShards(vc: VertexConsumer, gate: Gate, cam: Vec3d, fade: Float, seconds: Double) {
        val normal = normalOf(gate.axis)
        val along = alongOf(gate.axis)
        val up = Vec3d(0.0, 1.0, 0.0)
        val toward = if (gate.axis == Direction.Axis.X) cam.z - gate.center.z else cam.x - gate.center.x
        val side = if (toward >= 0.0) 1.0 else -1.0
        val wall = if (side > 0) gate.wallPlus else gate.wallMinus
        val radius = max(gate.halfW, gate.halfH) * 0.98
        val color = ((fade * 255f).toInt().coerceIn(0, 255) shl 24)
        for (i in 0 until 6) {
            val angle = i * PI / 3.0 + seconds * 2.0 * PI / 120.0 + gate.seed * 0.05
            val bob = sin(seconds * 2.0 * PI / 7.0 + i) * 0.15
            val pos = gate.center.add(along.multiply(cos(angle) * radius))
                .add(up.multiply(sin(angle) * radius + bob))
                .add(normal.multiply(side * (0.65 + wall)))
            val spin = seconds * 2.0 * PI / (9.0 + i) + i
            val size = 0.35 + 0.12 * (i % 3)
            val long = along.multiply(cos(spin) * size).add(up.multiply(sin(spin) * size))
            val short1 = along.multiply(-sin(spin) * size * 0.55).add(up.multiply(cos(spin) * size * 0.55))
            val short2 = normal.multiply(size * 0.55)
            for (short in arrayOf(short1, short2)) {
                vc.put(pos.subtract(short).subtract(long), cam, 0f, 0f, color)
                vc.put(pos.add(short).subtract(long), cam, 1f, 0f, color)
                vc.put(pos.add(short).add(long), cam, 1f, 1f, color)
                vc.put(pos.subtract(short).add(long), cam, 0f, 1f, color)
            }
        }
    }
}
