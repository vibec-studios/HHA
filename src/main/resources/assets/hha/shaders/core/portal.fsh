#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// HHA-Portalshader (PLAN 7.2): ein Parallaxe-Fenster statt echter Durchsicht. Varianten über Pipeline-Defines:
//   PORTAL_OLYMPUS  – Fenster des Wolkentors: Himmel mit Sonne · ferne Wolkenbank (40) · Insel mit Tempel (18) ·
//                     nahe Wolkenfetzen (6)
//   PORTAL_TARTAROS – Fenster des Höllentors: roter Himmel · Palastkuppel mit Ketten (30) · Felssplitter (12) ·
//                     grüne Glut (4)
//   PORTAL_BEAM     – Lichtkeile und Lichtfleck vor dem Wolkentor (durchscheinend, nicht additiv)
//   PORTAL_SHARD    – dunkle Splitter um das Höllentor (deckend, ausgeschnitten)
//
// Fenster: UV0 = Lage relativ zur Tormitte in Blöcken (x entlang der Ebene, y nach oben), Color.r = Achse (ab 128:
// die Ebene spannt z) plus Saat (0..127), Color.g/.b = halbe Breite/Höhe in 1/16 Block, Color.a = Aufblenden.
// Jede Ebene liegt in der Tiefe d hinter dem Tor und wird bei uv + slope · d ausgewertet; slope ist der Sichtstrahl
// geteilt durch seinen Anteil entlang der Tornormalen. Die Formeln stehen gleich in
// tmp/overhaul2/previews/portale/render.py (Vorschau ohne Spiel).

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec3 viewRay;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

const float TAU = 6.28318531;

// ---------------------------------------------------------------------------------------------------------------
// Rauschen. GameTime läuft in 1200 s einmal durch; alles Bewegte ist so gewählt, dass es dort nahtlos umbricht
// (Perioden teilen 1200 s, Drift um ganze Rauschperioden in x).
// ---------------------------------------------------------------------------------------------------------------

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Wertrauschen, in x mit der Periode `period` (Gitterzellen) wiederholend.
float vnoise(vec2 p, float period) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float x0 = mod(i.x, period);
    float x1 = mod(i.x + 1.0, period);
    float a = hash12(vec2(x0, i.y));
    float b = hash12(vec2(x1, i.y));
    float c = hash12(vec2(x0, i.y + 1.0));
    float d = hash12(vec2(x1, i.y + 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Mehrere Oktaven, periodisch in x mit `period` (in Einheiten von p).
float fbm(vec2 p, float period, int octaves) {
    float sum = 0.0;
    float amp = 0.5;
    float norm = 0.0;
    for (int o = 0; o < 5; o++) {
        if (o >= octaves) break;
        sum += amp * vnoise(p, period);
        norm += amp;
        p = vec2(p.x * 2.0, p.y * 2.0 + 17.31);
        period *= 2.0;
        amp *= 0.5;
    }
    return sum / norm;
}

// 1 unterhalb von e0, 0 oberhalb von e1 (smoothstep mit fallender Kante).
float fall(float e0, float e1, float x) {
    return 1.0 - smoothstep(e0, e1, x);
}

float boxMask(vec2 q, vec2 lo, vec2 hi, float aa) {
    vec2 a = smoothstep(lo - aa, lo + aa, q);
    vec2 b = 1.0 - smoothstep(hi - aa, hi + aa, q);
    return a.x * a.y * b.x * b.y;
}

// Geordnetes 4×4-Raster: Aufblenden ohne Mischen (deckend und mit Tiefe).
const float BAYER[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);

void dissolve(float alpha) {
    if (alpha < 0.995) {
        ivec2 bp = ivec2(mod(gl_FragCoord.xy, 4.0));
        if ((BAYER[bp.x + bp.y * 4] + 0.5) / 16.0 > alpha) discard;
    }
}

float fogAmount() {
    return total_fog_value(sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
}

// Kantenglättung je Ebene: Ableitungen werden einmal oben in main bestimmt (außerhalb von Verzweigungen).
vec2 gDUv = vec2(0.0);
vec2 gDSlope = vec2(0.0);

float aaAt(float depth) {
    return max(gDUv.x + gDSlope.x * depth, gDUv.y + gDSlope.y * depth) * 0.75 + 0.002;
}

// ---------------------------------------------------------------------------------------------------------------
// Wolkentor
// ---------------------------------------------------------------------------------------------------------------
#ifdef PORTAL_OLYMPUS
const vec3 SKY_TOP = vec3(0.184, 0.435, 0.816);   // #2f6fd0
const vec3 SKY_MID = vec3(0.435, 0.714, 1.000);   // #6fb6ff
const vec3 SKY_LOW = vec3(0.863, 0.922, 0.980);   // #dcebfa
const vec3 CLOUD   = vec3(1.000, 1.000, 1.000);   // #ffffff
const vec3 SUN     = vec3(1.000, 0.914, 0.659);   // #ffe9a8
const vec3 TEMPLE  = vec3(0.788, 0.839, 0.902);   // #c9d6e6
const vec3 SHADOW  = vec3(0.600, 0.665, 0.780);   // bläulicher Wolkenschatten
const vec3 ROCK    = vec3(0.890, 0.875, 0.845);   // Kalkstein der Insel
const vec3 GRASS   = vec3(0.525, 0.722, 0.373);
const vec3 CYPRESS = vec3(0.290, 0.431, 0.271);

vec3 skyOlympus(vec2 slope, float t, float seed) {
    vec3 dir = normalize(vec3(slope, 1.0));
    float h = dir.y;
    vec3 c = mix(SKY_LOW, SKY_MID, smoothstep(-0.04, 0.24, h));
    c = mix(c, SKY_TOP, smoothstep(0.24, 0.80, h));
    // Hohe Schleierwolken, kaum sichtbar
    float cir = fbm(vec2(slope.x * 1.5 + t * (16.0 / 1200.0), slope.y * 7.0 + seed * 5.0), 8.0, 3);
    c = mix(c, CLOUD, smoothstep(0.55, 0.85, cir) * 0.22 * smoothstep(0.05, 0.30, h));
    // Unter dem Horizont: helles Wolkenmeer im Dunst
    c = mix(c, mix(SKY_LOW, CLOUD, 0.5), smoothstep(0.0, 0.12, -h) * 0.8);
    // Sonne: Scheibe mit weichem Hof, warm und nicht überstrahlt
    vec3 sunDir = normalize(vec3(0.42, 0.34, 1.0));
    float cosA = max(dot(dir, sunDir), 0.0);
    float halo = pow(cosA, 48.0) * 0.28 + pow(cosA, 6.0) * 0.10;
    c = mix(c, SUN, clamp(halo, 0.0, 1.0));
    c = mix(c, SUN, smoothstep(0.99880, 0.99915, cosA));
    return c;
}

// Ferne Wolkenbank (Tiefe 40): Dichtefeld aus Rauschen minus Höhe, oben sonnig, unten bläulich.
vec4 farBank(vec2 p, float t, float seed, float aa) {
    vec2 q = vec2(p.x / 9.0 + t * (8.0 / 1200.0), p.y / 4.5 + seed * 11.0);
    float n = fbm(q, 8.0, 4);
    float inside = (n - 0.5) * 6.0 - 1.4 - p.y;
    float a = smoothstep(-0.30 - aa, 0.30 + aa, inside);
    float puff = fbm(vec2(q.x * 3.0, q.y * 2.5 + 5.7), 24.0, 3);
    float lit = clamp(0.82 - min(inside, 8.0) * 0.05 + (puff - 0.5) * 0.9, 0.0, 1.0);
    vec3 c = mix(SHADOW, CLOUD, lit);
    return vec4(mix(c, SKY_LOW, 0.32), a);
}

// Schwebende Insel mit Tempel und zwei Zypressen (Tiefe 18), treibt langsam.
vec4 island(vec2 p, float t, float seed, float aa) {
    vec2 center = vec2(3.6 + (seed - 0.5) * 5.0 + 1.1 * sin(TAU * t / 150.0), 0.4 + 0.30 * sin(TAU * t / 40.0));
    vec2 q = p - center;
    float w = 4.0;
    float ax = abs(q.x) / w;
    float top = 0.30 * (1.0 - ax * ax) + 0.10 * (vnoise(vec2(q.x * 1.6 + seed * 50.0, 3.0), 64.0) - 0.5);
    float k = clamp(1.0 - ax, 0.0, 1.0);
    float rough = fbm(vec2(q.x * 1.2 + seed * 20.0, 7.0), 64.0, 3);
    float bottom = -(0.35 + 3.4 * pow(k, 1.3)) * (0.75 + 0.5 * rough);
    float body = smoothstep(bottom - aa, bottom + aa, q.y) * fall(top - aa, top + aa, q.y) * fall(w - aa, w + aa, abs(q.x));

    // Fels: von rechts besonnt, nach unten in den Schatten, senkrechte Rillen
    float stria = fbm(vec2(q.x * 3.0 + seed * 9.0, q.y * 0.6), 64.0, 3);
    vec3 col = ROCK * (0.80 + 0.20 * clamp(q.x / w, -1.0, 1.0)) * (0.86 + 0.28 * stria);
    col = mix(col, SHADOW * 0.85, (1.0 - smoothstep(-3.6, 0.0, q.y)) * 0.75);
    // Grasnarbe
    float grass = smoothstep(top - 0.22 - aa, top - 0.22 + aa, q.y);
    col = mix(col, GRASS * (0.85 + 0.25 * vnoise(q * 5.0, 1000.0)), grass);

    // Tempel: Stufenbau, Cella im Schatten, sechs Säulen, Gebälk, Giebel
    float tx = q.x + 0.7;
    vec2 tq = vec2(tx, q.y);
    float stylo = boxMask(tq, vec2(-1.62, 0.22), vec2(1.62, 0.46), aa);
    col = mix(col, TEMPLE * (0.92 + 0.10 * smoothstep(0.38, 0.46, q.y)), stylo);
    float cella = boxMask(tq, vec2(-1.25, 0.46), vec2(1.25, 1.80), aa);
    col = mix(col, mix(SHADOW, TEMPLE, 0.35), cella);
    float cx = (tx + 1.35) / 0.54;
    float ci = floor(cx + 0.5);
    float off = (cx - ci) * 0.54;
    float column = fall(0.10 - aa, 0.10 + aa, abs(off)) * step(-0.5, ci) * step(ci, 5.5)
        * boxMask(tq, vec2(-1.6, 0.46), vec2(1.6, 1.80), aa);
    col = mix(col, TEMPLE * (0.80 + 0.18 * cos(off * 14.0) + 0.10 * off / 0.10), column);
    float beam = boxMask(tq, vec2(-1.50, 1.80), vec2(1.50, 2.05), aa);
    col = mix(col, TEMPLE * (1.0 - 0.12 * boxMask(tq, vec2(-1.5, 1.91), vec2(1.5, 1.95), aa)), beam);
    float apex = 2.05 + 0.50 * (1.0 - abs(tx) / 1.55);
    float pediment = smoothstep(2.05 - aa, 2.05 + aa, q.y) * fall(apex - aa, apex + aa, q.y) * fall(1.55 - aa, 1.55 + aa, abs(tx));
    float tympanum = smoothstep(2.11, 2.13, q.y) * fall(apex - 0.10, apex - 0.08, q.y) * fall(1.30, 1.32, abs(tx));
    col = mix(col, TEMPLE * (1.06 - 0.14 * tympanum), pediment);

    // Zwei Zypressen rechts vom Tempel
    vec2 c1 = (q - vec2(1.55, top + 0.62)) / vec2(0.21, 0.72);
    vec2 c2 = (q - vec2(2.05, top + 0.45)) / vec2(0.17, 0.52);
    float tree = max(fall(1.0 - aa * 4.0, 1.0 + aa * 4.0, length(c1)), fall(1.0 - aa * 5.0, 1.0 + aa * 5.0, length(c2)));
    col = mix(col, CYPRESS * (0.85 + 0.30 * smoothstep(-0.2, 0.25, q.x - 1.6)), tree);

    float mask = max(body, max(max(stylo, cella), max(max(column, beam), max(pediment, tree))));
    return vec4(mix(col, SKY_LOW, 0.20), mask);
}

// Nahe Wolkenfetzen (Tiefe 6): zwei Bänder, ziehen nach rechts.
vec4 nearWisps(vec2 p, float t, float seed, float aa) {
    vec2 q = vec2(p.x / 3.2 - t * 0.04, p.y / 1.6 + seed * 23.0);
    float n = fbm(q, 8.0, 5);
    float low = (p.y + 2.3) / 1.4;
    float high = (p.y - 2.4) / 0.9;
    float band = exp(-low * low) + 0.55 * exp(-high * high);
    float dens = n * band;
    float a = smoothstep(0.26 - aa, 0.50 + aa, dens) * 0.88;
    float lit = smoothstep(0.25, 0.65, dens + (fbm(vec2(q.x * 2.0, q.y * 2.0 + 3.3), 16.0, 2) - 0.5) * 0.3);
    vec3 c = mix(mix(SKY_LOW, SHADOW, 0.25), CLOUD, lit);
    return vec4(c, a);
}
#endif

// ---------------------------------------------------------------------------------------------------------------
// Höllentor (Farben auch für die Splitter)
// ---------------------------------------------------------------------------------------------------------------
#if defined(PORTAL_TARTAROS) || defined(PORTAL_SHARD)
const vec3 T_TOP   = vec3(0.071, 0.024, 0.039);   // #12060a
const vec3 T_MID   = vec3(0.227, 0.039, 0.063);   // #3a0a10
const vec3 T_LOW   = vec3(0.416, 0.016, 0.031);   // #6a0408
const vec3 T_GREEN = vec3(0.055, 0.478, 0.173);   // #0e7a2c
const vec3 T_EMBER = vec3(0.298, 1.000, 0.353);   // #4cff5a
const vec3 T_ROCK  = vec3(0.102, 0.071, 0.086);   // #1a1216

// Ein Felssplitter: unregelmäßige Raute mit Facetten; r in Einheiten der Splittergröße.
vec3 shardColor(vec2 r, float k) {
    float wx = r.x >= 0.0 ? 0.55 : 0.40;
    float hy = r.y >= 0.0 ? 1.00 : 0.75;
    vec2 nrm = normalize(vec2((r.x >= 0.0 ? 1.0 : -1.0) / wx, (r.y >= 0.0 ? 1.0 : -1.0) / hy));
    float red = clamp(dot(nrm, normalize(vec2(0.35, -1.0))), 0.0, 1.0);
    float upper = clamp(dot(nrm, normalize(vec2(-0.3, 1.0))), 0.0, 1.0);
    vec3 c = T_ROCK * (0.75 + 0.35 * upper) + T_LOW * 0.55 * red;
    c *= 0.85 + 0.25 * (1.0 - k);
    return mix(c, T_GREEN * 0.6, smoothstep(0.80, 0.98, k) * 0.35);
}
#endif

#ifdef PORTAL_TARTAROS
vec3 skyTartaros(vec2 slope, float t, float seed) {
    vec3 dir = normalize(vec3(slope, 1.0));
    float h = dir.y;
    vec3 c = mix(T_LOW, T_MID, smoothstep(-0.10, 0.12, h));
    c = mix(c, T_TOP, smoothstep(0.12, 0.60, h));
    // Rauchschwaden ziehen langsam
    float m = fbm(vec2(slope.x * 1.4 + t * (8.0 / 1200.0), slope.y * 2.6 + seed * 9.0), 8.0, 4);
    c *= 0.78 + 0.44 * m;
    // Unter dem Horizont: dunkler Abgrund mit feinen Glutadern
    float deep = smoothstep(0.0, 0.35, -h);
    c = mix(c, T_MID * 0.5, deep * 0.85);
    float vein = fbm(vec2(slope.x * 3.0 + seed * 4.0, slope.y * 6.0), 8.0, 3);
    c = mix(c, T_LOW * 1.1, fall(0.0, 0.035, abs(vein - 0.5)) * deep * 0.55);
    return c;
}

// Eine Kette aus abwechselnd flachen und hochkant hängenden Gliedern, oben aufgehängt, pendelt leicht.
float chain(vec2 q, float x0, float yEnd, float t, float i, float aa) {
    if (q.y < yEnd - 0.4) return 0.0;
    float yTop = 18.0;
    float swing = 0.35 * sin(TAU * t / 60.0 + i * 1.7) * clamp((yTop - q.y) / (yTop - yEnd), 0.0, 1.0);
    float x = q.x - x0 - swing;
    float pitch = 0.36;
    float fyRing = (fract(q.y / (2.0 * pitch)) - 0.5) * 2.0 * pitch;
    float fyBar = (fract(q.y / (2.0 * pitch) + 0.5) - 0.5) * 2.0 * pitch;
    float outer = length(vec2(x / 0.12, fyRing / 0.27));
    float inner = length(vec2(x / 0.055, fyRing / 0.16));
    float ring = fall(1.0 - aa * 6.0, 1.0 + aa * 6.0, outer) * smoothstep(1.0 - aa * 12.0, 1.0 + aa * 12.0, inner);
    float bar = boxMask(vec2(x, fyBar), vec2(-0.04, -0.25), vec2(0.04, 0.25), aa);
    return max(ring, bar) * smoothstep(yEnd - 0.4, yEnd, q.y);
}

// Palastkuppel des Hades als Umriss (Tiefe 30), dazu hängende Ketten.
vec4 palace(vec2 p, float t, float seed, float aa) {
    vec2 q = p - vec2(-2.0 + (seed - 0.5) * 6.0, -3.0);
    float m = boxMask(q, vec2(-9.0, -14.0), vec2(9.0, 0.6), aa);
    m = max(m, boxMask(q, vec2(-5.6, 0.0), vec2(5.6, 3.4), aa));
    float dome = length(vec2(q.x / 5.9, (q.y - 3.4) / 4.8));
    m = max(m, fall(1.0 - aa / 5.0, 1.0 + aa / 5.0, dome) * smoothstep(3.3 - aa, 3.3 + aa, q.y));
    m = max(m, boxMask(q, vec2(-0.55, 8.0), vec2(0.55, 9.3), aa));
    float spireW = 0.28 * (1.0 - (q.y - 9.3) / 2.4);
    m = max(m, fall(spireW - aa, spireW + aa, abs(q.x)) * smoothstep(9.3 - aa, 9.3 + aa, q.y) * fall(11.7 - aa, 11.7 + aa, q.y));
    for (int s = 0; s < 2; s++) {
        float tx = q.x - (s == 0 ? -8.2 : 8.2);
        m = max(m, boxMask(vec2(tx, q.y), vec2(-0.9, 0.6), vec2(0.9, 7.5), aa));
        float roofW = 1.1 * (1.0 - (q.y - 7.5) / 2.6);
        m = max(m, fall(roofW - aa, roofW + aa, abs(tx)) * smoothstep(7.5 - aa, 7.5 + aa, q.y) * fall(10.1 - aa, 10.1 + aa, q.y));
    }
    // Fast schwarz, von unten rot angestrahlt; Kuppelrippen etwas heller
    vec3 col = mix(vec3(0.035, 0.012, 0.020), T_LOW * 0.45, fall(-6.0, 3.0, q.y) * 0.8);
    float ribs = fall(0.06, 0.14, abs(fract(q.x / 1.6 + 0.5) - 0.5) * 1.6) * smoothstep(3.4, 3.6, q.y) * fall(0.92, 1.0, dome);
    col = mix(col, T_MID * 0.55, ribs * 0.6);
    // Matte grüne Fensterschlitze in der Trommel
    float wx = abs(fract((q.x + 4.0) / 2.0 + 0.5) - 0.5) * 2.0;
    float win = fall(0.15, 0.19, wx) * boxMask(q, vec2(-4.6, 0.9), vec2(4.6, 2.3), aa);
    col = mix(col, T_GREEN * 0.65, win * (0.75 + 0.25 * sin(TAU * t / 4.0 + q.x)));
    // Ketten hängen aus der Höhe
    float ch = 0.0;
    ch = max(ch, chain(q, -9.5, 6.5, t, 0.0, aa));
    ch = max(ch, chain(q, -4.0, 9.0, t, 1.0, aa));
    ch = max(ch, chain(q, 3.2, 8.0, t, 2.0, aa));
    ch = max(ch, chain(q, 7.8, 5.0, t, 3.0, aa));
    ch = max(ch, chain(q, 12.0, 10.0, t, 4.0, aa));
    col = mix(col, mix(T_ROCK * 1.6, T_LOW * 0.6, 0.3), ch);
    return vec4(mix(col, T_MID, 0.18), max(m, ch));
}

// Schwebende Felssplitter (Tiefe 12), drehen sich langsam.
vec4 shards(vec2 p, float t, float seed, float aa) {
    vec4 acc = vec4(0.0);
    for (int i = 0; i < 6; i++) {
        float fi = float(i);
        float h1 = hash12(vec2(fi, seed * 17.0 + 1.0));
        float h2 = hash12(vec2(fi, seed * 17.0 + 2.0));
        vec2 c = vec2(-8.0 + fi * 3.4 + (h1 - 0.5) * 2.0, -2.5 + h2 * 6.5);
        c.y += 0.35 * sin(TAU * (t / 60.0 + h1));
        float spin = (h2 - 0.5) * 6.0 + TAU * t / ((i % 2 == 0) ? 300.0 : -400.0);
        float size = 0.8 + h1 * 1.0;
        vec2 d = p - c;
        float cs = cos(spin);
        float sn = sin(spin);
        vec2 r = vec2(cs * d.x + sn * d.y, -sn * d.x + cs * d.y) / size;
        float wx = r.x >= 0.0 ? 0.55 : 0.40;
        float hy = r.y >= 0.0 ? 1.00 : 0.75;
        float k = abs(r.x) / wx + abs(r.y) / hy;
        float m = fall(1.0 - aa / size, 1.0 + aa / size, k);
        if (m <= 0.0) continue;
        vec3 col = mix(shardColor(r, k), T_MID, 0.12);
        acc = vec4(mix(acc.rgb, col, m), max(acc.a, m));
    }
    return acc;
}

// Grüne Glutpunkte (Tiefe 4), steigen auf und verglühen nach oben.
vec4 embers(vec2 p, float t, float seed) {
    vec2 q = vec2(p.x / 1.1 + seed * 13.0, p.y / 1.1 - t * 0.25);
    vec2 cell = floor(q);
    vec2 f = fract(q);
    vec2 key = vec2(cell.x, mod(cell.y, 300.0));
    float h0 = hash12(key + 3.7);
    if (h0 > 0.30) return vec4(0.0);
    float h1 = hash12(key + 11.1);
    float h2 = hash12(key + 23.7);
    vec2 c = vec2(0.25 + 0.5 * h1, 0.25 + 0.5 * h2);
    c.x += 0.12 * sin(TAU * (t / 6.0 + h1));
    float d = length(f - c) * 1.1;
    float r = 0.030 + 0.030 * h2;
    float core = fall(r * 0.4, r, d);
    float glow = fall(r, r * 4.5, d) * 0.30;
    float flick = 0.70 + 0.30 * sin(TAU * (t / 2.0 + h0 * 5.0));
    float rise = 1.0 - smoothstep(-1.0, 7.0, p.y) * 0.7;
    return vec4(mix(T_GREEN, T_EMBER, core), max(core, glow) * flick * rise);
}
#endif

void main() {
    float t = GameTime * 1200.0;

#if defined(PORTAL_OLYMPUS) || defined(PORTAL_TARTAROS)
    float code = floor(vertexColor.r * 255.0 + 0.5);
    bool spansZ = code >= 127.5;
    float seed = mod(code, 128.0) / 128.0;
    vec2 halfSize = max(vertexColor.gb * (255.0 / 16.0), vec2(0.5));
    vec3 r = viewRay;
    float rt = spansZ ? r.z : r.x;
    float rn = spansZ ? r.x : r.z;
    // Auf beiden Seiten dasselbe, nicht gespiegelte Bild: x zeigt immer nach rechts im Blick.
    float side = rn < 0.0 ? 1.0 : -1.0;
    float flip = spansZ ? -side : side;
    float len = length(r);
    vec2 slope = vec2(flip * rt, r.y) / max(abs(rn), 0.08 * len);
    vec2 uv = vec2(flip * texCoord0.x, texCoord0.y);
    gDUv = fwidth(uv);
    gDSlope = fwidth(slope);
    dissolve(vertexColor.a);
    bool detailed = len < 96.0;
    // Laibung: Der Rand wird mit einer kleinen Tiefe ausgewertet und wandert so mit dem Blickwinkel.
    vec2 pe = uv + clamp(slope, vec2(-1.5), vec2(1.5)) * 0.45;
#endif

#ifdef PORTAL_OLYMPUS
    vec3 col = skyOlympus(slope, t, seed);
    if (detailed) {
        vec4 layer = farBank(uv + slope * 40.0, t, seed, aaAt(40.0));
        col = mix(col, layer.rgb, layer.a);
        layer = island(uv + slope * 18.0, t, seed, aaAt(18.0));
        col = mix(col, layer.rgb, layer.a);
        layer = nearWisps(uv + slope * 6.0, t, seed, aaAt(6.0));
        col = mix(col, layer.rgb, layer.a);
    }
    float e = max(abs(pe.x) / halfSize.x, abs(pe.y) / halfSize.y);
    col = mix(col, vec3(0.93, 0.96, 1.0), smoothstep(0.78, 1.0, e) * 0.30);
    // Nachts gedämpft, damit das Fenster im Dunkeln nicht blendet (die Nebelfarbe zeigt das Umgebungslicht).
    float ambient = dot(FogColor.rgb, vec3(0.299, 0.587, 0.114));
    col *= mix(0.62, 1.0, smoothstep(0.06, 0.45, ambient));
    col = mix(col, FogColor.rgb, fogAmount() * FogColor.a);
    fragColor = vec4(col, 1.0);
#elif defined(PORTAL_TARTAROS)
    vec3 col = skyTartaros(slope, t, seed);
    if (detailed) {
        vec4 layer = palace(uv + slope * 30.0, t, seed, aaAt(30.0));
        col = mix(col, layer.rgb, layer.a);
        layer = shards(uv + slope * 12.0, t, seed, aaAt(12.0));
        col = mix(col, layer.rgb, layer.a);
        layer = embers(uv + slope * 4.0, t, seed);
        col = mix(col, layer.rgb, layer.a);
    }
    float e = length(pe / halfSize);
    col = mix(col, vec3(0.020, 0.008, 0.012), smoothstep(0.80, 1.02, e) * 0.65);
    col = mix(col, T_GREEN, smoothstep(0.86, 0.95, e) * fall(0.95, 1.04, e) * 0.22);
    col = mix(col, FogColor.rgb, fogAmount() * FogColor.a);
    fragColor = vec4(col, 1.0);
#elif defined(PORTAL_BEAM)
    // Color.r: 0 = Lichtkeil (UV.x entlang, UV.y quer), 1 = Lichtfleck am Boden; Color.b = Saat; Color.a = Stärke.
    vec2 uv = texCoord0;
    float seed = vertexColor.b;
    float a;
    if (vertexColor.r < 0.5) {
        float across = abs(uv.y * 2.0 - 1.0);
        a = smoothstep(0.0, 0.12, uv.x) * pow(1.0 - uv.x, 1.3) * pow(1.0 - across, 1.6);
        a *= 0.75 + 0.35 * vnoise(vec2(uv.x * 6.0 - t * 0.25, uv.y * 3.0 + seed * 40.0), 60.0);
    } else {
        float d = length(uv * 2.0 - 1.0);
        a = fall(0.15, 1.0, d);
        a *= 0.85 + 0.15 * vnoise(vec2(uv.x * 5.0 + t * 0.1, uv.y * 5.0 + seed * 30.0), 40.0);
    }
    float nearFade = smoothstep(0.4, 2.5, sphericalVertexDistance);
    fragColor = vec4(1.0, 0.957, 0.860, a * vertexColor.a * nearFade * (1.0 - fogAmount()));
#elif defined(PORTAL_SHARD)
    // UV0 0..1 über das Quad; Color.a = Aufblenden.
    vec2 r = texCoord0 * 2.0 - 1.0;
    float wx = r.x >= 0.0 ? 0.55 : 0.40;
    float hy = r.y >= 0.0 ? 1.00 : 0.75;
    float k = abs(r.x) / wx + abs(r.y) / hy;
    if (k > 1.0) discard;
    dissolve(vertexColor.a);
    vec3 col = shardColor(r, k);
    col = mix(col, FogColor.rgb, fogAmount() * FogColor.a);
    fragColor = vec4(col, 1.0);
#else
    fragColor = vertexColor;
#endif
}
