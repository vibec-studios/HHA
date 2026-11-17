#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <hha:hha_noise.glsl>

// HHA-Energie (Überarbeitung 3, P01): eine Pipeline, 14 Formen. Welche Form ein Fragment bekommt, steht im Vertex
// (UV1.x = FxMode, UV1.y = Seed); UV2 trägt Innenradius-Anteil (bei Bändern: relative Halbbreite) und Fortschritt
// (0..1). Belegung: Kopf von FxDraw.kt.
//
// Weich statt gestuft (PLAN 0, Regel 14): stetige Zeit aus GameTime, Kanten mit smoothstep (Breite aus fwidth, also
// scharf, aber ohne Treppen), Rauschen in UV bzw. auf der Fläche, nie im Weltraster. Gemischt wird vormultipliziert:
// rgb = Licht, a = Abdeckung. Leuchtende Teile decken wenig ab (FX_GLOW), dunkle Kerne (Riss, Sprung, Fleck) decken
// ab, ohne zu leuchten. FX_GAIN dämpft das ganze Licht wie VFX_GAIN im Bestand. Nah an der Kamera blendet alles aus.

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vColor;
in vec2 vUV;
in vec2 vParam;
in vec3 vNormal;
in vec3 vPos;
flat in ivec2 vMode;

out vec4 fragColor;

#ifndef FX_GLOW
#define FX_GLOW 0.6
#endif
#ifndef FX_GAIN
#define FX_GAIN 0.7
#endif

const int MODE_RIBBON = 0;
const int MODE_SHELL = 1;
const int MODE_GLOW = 2;
const int MODE_SWIRL = 3;
const int MODE_SHOCK = 4;
const int MODE_FLAME = 5;
const int MODE_RUNE = 6;
const int MODE_STAIN = 7;
const int MODE_TEAR = 8;
const int MODE_DOME = 9;
const int MODE_CRACK = 10;
const int MODE_WATER = 11;
const int MODE_DUST = 12;
const int MODE_BOLT = 13;

const float TAU = 6.2831853;

// Ergebnis einer Form: Licht (Farbe und Menge), Abdeckung des Lichts (0..1 je Licht), dunkle Abdeckung.
struct Fx {
    vec3 emit;
    float light;
    float cover;
    float dark;
};

Fx fx(vec3 emit, float light, float cover, float dark) {
    Fx r;
    r.emit = emit;
    r.light = light;
    r.cover = cover;
    r.dark = dark;
    return r;
}

// Kern leicht weißlich wie vfx.fsh (dort 0,3): helle Stellen werden heller, die Farbe bleibt lesbar.
vec3 hot(vec3 c, float e) {
    return mix(c, vec3(1.0), clamp(e * e * e * 0.3, 0.0, 1.0));
}

// Pixelbreite einer Größe (mindestens ein winziger Rest gegen Division durch null).
float px(float x) {
    return max(fwidth(x), 1e-4);
}

// --- Bänder: uv.x entlang (Blöcke, mit Verschiebung), uv.y quer 0..1 (aus `band` in main) ------------------------------------------

Fx m_ribbon(vec2 uv, vec2 so, float t) {
    float x = abs(uv.y * 2.0 - 1.0);
    float across = 1.0 - x;
    float n = hha_fbm(vec2(uv.x * 0.9 - t * 2.2, uv.y * 1.6) + so);
    float n2 = hha_noise(vec2(uv.x * 2.4 - t * 3.6, uv.y * 3.1) + so.yx);
    float noise = n * 0.65 + n2 * 0.35;
    float body = smoothstep(0.0, 0.6, across);
    float core = smoothstep(0.55, 1.0, across);
    float e = body * (0.2 + noise * 0.95) + core * 0.45;
    return fx(hot(vColor.rgb, e), clamp(e, 0.0, 1.0), FX_GLOW, 0.0);
}

Fx m_bolt(vec2 uv, vec2 so, float t) {
    float x = abs(uv.y * 2.0 - 1.0);
    float core = exp(-x * x * 55.0);
    float halo = exp(-x * 3.6) * (1.0 - smoothstep(0.75, 1.0, x));
    // Laufendes Schimmern entlang des Blitzes (Bewegung, kein Helligkeitsflackern der ganzen Form).
    float flow = 0.88 + 0.24 * hha_noise(vec2(uv.x * 1.7 - t * 3.0, 0.5) + so);
    float e = (core * 1.25 + halo * 0.38) * flow;
    vec3 c = mix(vColor.rgb, vec3(1.0), core * 0.6);
    return fx(c, clamp(e, 0.0, 1.0), FX_GLOW, 0.0);
}

// UV0.y = 0 am Fuß, 1 an der Spitze; die Zungen steigen auf und laufen vor der Oberkante weich aus.
Fx m_flame(vec2 uv, vec2 so, float t) {
    float v = clamp(uv.y, 0.0, 1.0);
    vec2 q = vec2(uv.x * 1.9, v * 0.95 - t * 1.5);
    float warp = hha_noise(q * 0.7 + so.yx) - 0.5;
    float n = hha_fbm(q + vec2(warp * 0.8, 0.0) + so);
    float n2 = hha_noise(vec2(uv.x * 4.2, v * 2.2 - t * 2.6) + so.yx);
    float tongue = n * 0.85 + n2 * 0.3;
    float shape = tongue * 1.45 - v * 1.3 - 0.12;
    float m = smoothstep(0.0, 0.22, shape);
    float heat = smoothstep(0.08, 0.55, shape);
    float foot = smoothstep(0.0, 0.1, v);
    float tip = 1.0 - smoothstep(0.72, 1.0, v);
    float e = m * (0.28 + 0.8 * heat) * foot * tip;
    vec3 warm = mix(vColor.rgb, vec3(1.0, 0.93, 0.78), 0.5);
    vec3 c = mix(vColor.rgb, warm, heat * (1.0 - v));
    return fx(c, clamp(e, 0.0, 1.0), FX_GLOW, 0.0);
}

// Liegender Riss im Boden: gezackte Ränder, dunkler Saum, Glutkern. Fortschritt = Hitze des Kerns.
Fx m_crack(vec2 uv, vec2 so, float t, float heat) {
    float x = abs(uv.y * 2.0 - 1.0);
    float j1 = hha_ridge(hha_noise(vec2(uv.x * 4.2, 0.37) + so));
    float j2 = hha_noise(vec2(uv.x * 11.0, 2.1) + so.yx) - 0.5;
    float d = x + (j1 - 0.5) * 0.42 + j2 * 0.16;
    float aa = px(d);
    float rim = 1.0 - smoothstep(0.62 - aa, 0.78 + aa, d);
    float core = 1.0 - smoothstep(0.06 - aa, 0.3 + aa, d);
    float flow = 0.8 + 0.3 * hha_noise(vec2(uv.x * 1.2 - t * 0.55, 3.3) + so);
    float halo = exp(-max(d, 0.0) * 5.5) * 0.22;
    float edgeFade = 1.0 - smoothstep(0.85, 1.0, x);
    float e = (core * 1.15 + halo) * heat * flow * edgeFade;
    vec3 c = mix(vColor.rgb, vec3(1.0, 0.9, 0.7), core * heat * 0.35);
    float dark = rim * (1.0 - core * heat * 0.85) * 0.72 * edgeFade;
    return fx(c, clamp(e, 0.0, 1.0), FX_GLOW, dark);
}

// Riss im Raum: dunkler Kern aus Nichts, Glutrand, Lichthof und feine Haarrisse nach außen.
// Fortschritt = Öffnung (0 zu, 1 offen); der Kern ist offen gut ein Drittel der Bandbreite.
Fx m_tear(vec2 uv, vec2 so, float t, float open) {
    float x = abs(uv.y * 2.0 - 1.0);
    float jag = (hha_noise(vec2(uv.x * 1.3, 0.0) + so) - 0.5) + (hha_noise(vec2(uv.x * 3.1, 4.0) + so.yx) - 0.5) * 0.35;
    float d = x + jag * 0.2;
    float coreW = mix(0.015, 0.36, smoothstep(0.0, 1.0, open));
    float dc = d - coreW;
    float aa = px(dc);
    float darkCore = 1.0 - smoothstep(-aa - 0.015, aa + 0.015, dc);
    float flow = 0.75 + 0.4 * hha_noise(vec2(uv.x * 2.2 - t * 0.6, 1.9) + so);
    float rim = exp(-abs(dc) * 14.0) * flow;
    float beyond = max(dc, 0.0);
    float halo = exp(-beyond * 4.5) * 0.3 * step(0.0, dc);
    float hn = hha_noise(vec2(uv.x * 2.4 + d * 1.2, d * 2.6) + so.yx);
    float hw = px(hn) * 1.2 + 0.012;
    float hair = 1.0 - smoothstep(hw * 0.5, hw * 1.6, abs(hn - 0.5));
    hair *= (1.0 - smoothstep(0.0, 0.5, beyond)) * step(0.0, dc) * open;
    float edgeFade = 1.0 - smoothstep(0.8, 1.0, x);
    float e = (rim * 1.1 + halo + hair * 0.7 + darkCore * 0.04) * edgeFade;
    vec3 c = hot(vColor.rgb, rim);
    return fx(c, clamp(e, 0.0, 1.0), FX_GLOW, darkCore * 0.93 * edgeFade);
}

// Wasserwand: UV0.x entlang (Blöcke), UV0.y = 0 unten, 1 oben. Die Krone läuft in +x, Glanzlinien steigen zur
// Krone auf, unten wird das Wasser dunkler und setzt weich auf dem Boden auf. Fortschritt = Schaummenge.
Fx m_water(vec2 uv, vec2 so, float t, float foamAmount) {
    float v = uv.y;
    float top = 1.0 - 0.07 * hha_noise(vec2(uv.x * 0.7 - t * 1.1, 0.0) + so)
                    - 0.04 * hha_noise(vec2(uv.x * 2.2 - t * 1.9, 5.0) + so.yx);
    float aa = px(v);
    float inside = 1.0 - smoothstep(top - aa * 1.5, top + aa * 1.5, v);
    float streak = hha_fbm(vec2(uv.x * 1.4, v * 2.6 - t * 1.3) + so);
    float sheenN = hha_noise(vec2(uv.x * 1.8 + v * 0.7, v * 1.4 - t * 0.9) + so.yx);
    float sheen = pow(hha_ridge(sheenN), 3.0) * smoothstep(0.15, 0.7, v) * 0.6;
    float crest = smoothstep(top - 0.24, top - 0.02, v);
    float foamN = hha_fbm(vec2(uv.x * 2.3 - t * 1.6, v * 5.5) + so.yx);
    float foam = crest * smoothstep(0.38, 0.72, foamN + crest * 0.25) * foamAmount;
    float foot = smoothstep(0.0, 0.14, v);
    float depth = mix(0.55, 1.05, smoothstep(0.0, 0.9, v));
    vec3 body = vColor.rgb * depth * (0.8 + 0.3 * streak);
    vec3 c = mix(body + vec3(0.55, 0.7, 0.8) * sheen * 0.6, vec3(0.94, 0.97, 1.0), foam);
    float light = inside * foot * (0.32 + 0.22 * streak + sheen * 0.35 + foam * 0.6);
    return fx(c, clamp(light, 0.0, 1.0), 0.95, inside * foot * 0.2);
}

// --- Flächen: UV0 = -1..1 über das Viereck, Radius = Länge ---------------------------------------------------

Fx m_glow(vec2 uv) {
    float r = length(uv);
    float fall = exp(-r * r * 5.0) * 0.8 + exp(-r * 7.5) * 0.35;
    float e = fall * (1.0 - smoothstep(0.62, 1.0, r));
    return fx(hot(vColor.rgb, e * 1.3), clamp(e, 0.0, 1.0), FX_GLOW, 0.0);
}

// Wirbel: Innenradius = dunkles Auge, Fortschritt = Öffnung (sichtbarer Radius).
Fx m_swirl(vec2 uv, vec2 so, float t, float inner, float open) {
    float R = mix(0.04, 1.0, smoothstep(0.0, 1.0, open));
    float r = length(uv) / R;
    vec2 dir = uv / max(length(uv), 1e-4);
    float ang = atan(uv.y, uv.x);
    float spiral = ang * 3.0 + log(max(r, 0.015)) * 4.2 - t * 1.3;
    float arms = 0.5 + 0.5 * sin(spiral);
    float n = hha_fbm(dir * (1.5 + r * 2.0) + vec2(r * 2.5 - t * 0.45, 0.0) + so);
    float w = smoothstep(0.28, 0.9, arms * 0.75 + n * 0.45);
    float aa = px(r);
    float rim = 1.0 - smoothstep(0.72, 1.0 + aa, r);
    float eye = inner > 0.0 ? smoothstep(inner - aa, inner + 0.18, r) : smoothstep(0.0, 0.2, r);
    float e = w * rim * eye * (0.3 + 0.75 * r);
    float lip = inner > 0.0 ? exp(-abs(r - inner) * 18.0) * 0.6 * rim : 0.0;
    vec3 c = hot(vColor.rgb, e + lip);
    float dark = inner > 0.0 ? (1.0 - smoothstep(inner - aa, inner + 0.06, r)) * 0.9 : 0.0;
    return fx(c, clamp(e + lip, 0.0, 1.0), FX_GLOW, dark);
}

// Stoßring zwischen Innenradius und Rand: weiche Front außen, Schweif läuft nach innen aus.
Fx m_shock(vec2 uv, vec2 so, float t, float inner) {
    float r = length(uv);
    float aa = px(r);
    float v = (r - inner) / max(1.0 - inner, 1e-3);
    vec2 dir = uv / max(r, 1e-4);
    float n = hha_fbm(dir * 2.6 + vec2(v * 1.8 - t * 1.1, 0.0) + so);
    float outer = 1.0 - smoothstep(1.0 - aa * 2.0 - 0.025, 1.0, r);
    float tail = smoothstep(0.0, 1.0, clamp(v, 0.0, 1.0));
    tail *= tail;
    float crest = smoothstep(0.72, 0.97, v) * 0.55;
    float e = (tail * (0.45 + 0.65 * n) + crest) * outer * step(0.0, v);
    return fx(hot(vColor.rgb, e), clamp(e, 0.0, 1.0), FX_GLOW, 0.0);
}

// Ein Runenzeichen aus zwei bis vier Strichen im Feld p (x quer 0..k, y 0..1), Auswahl über h.
float glyph(vec2 p, float k, float h, float h2, float aa) {
    float wdt = 0.075;
    float g = 0.0;
    float cx = k * 0.5;
    // Stamm
    g = max(g, hha_line(p, vec2(cx, 0.16), vec2(cx, 0.84), wdt, aa));
    if (h < 0.5) {
        g = max(g, hha_line(p, vec2(cx, 0.84), vec2(cx + k * 0.3, 0.62), wdt, aa));
    } else {
        g = max(g, hha_line(p, vec2(cx, 0.84), vec2(cx - k * 0.3, 0.62), wdt, aa));
    }
    if (fract(h * 7.0) < 0.5) {
        g = max(g, hha_line(p, vec2(cx - k * 0.28, 0.34), vec2(cx + k * 0.28, 0.34), wdt, aa));
    } else {
        g = max(g, hha_line(p, vec2(cx - k * 0.26, 0.18), vec2(cx + k * 0.26, 0.5), wdt, aa));
    }
    if (h2 < 0.35) {
        float d = abs(length(p - vec2(cx, 0.5)) - 0.17);
        g = max(g, 1.0 - smoothstep(wdt * 0.8 - aa, wdt * 0.8 + aa, d));
    } else if (h2 < 0.7) {
        g = max(g, hha_line(p, vec2(cx + k * 0.3, 0.16), vec2(cx + k * 0.3, 0.42), wdt, aa));
    }
    return g;
}

// Runenring zwischen Innenradius und Rand: Randlinien, Schlitzband (dreht stetig gegen die Zeichen), Zeichen.
// Fortschritt = wie weit der Ring gezogen ist (Winkelanteil). Die Drehung des ganzen Rings macht FxDraw am Viereck.
Fx m_rune(vec2 uv, float seed, float t, float inner, float drawn) {
    float r = length(uv);
    float w = px(r);
    float in0 = clamp(inner, 0.0, 0.9);
    float span = 1.0 - in0;
    float s = (r - in0) / span;
    float ws = w / span;
    float turn = atan(uv.y, uv.x) / TAU + 0.5;
    float wa = w / max(r, 1e-3) / TAU;
    float inside = hha_window(s, 0.0, 1.0, ws);
    float lines = max(hha_window(s, 0.9, 0.97, ws), hha_window(s, 0.03, 0.1, ws));
    // Schlitzband
    float nSlots = 48.0;
    float sa = fract(turn * nSlots + t * 0.12);
    float slots = hha_window(sa, 0.2, 0.62, wa * nSlots) * hha_window(s, 0.79, 0.85, ws);
    // Zeichenband: Feldzahl aus dem Umfang, damit die Felder etwa quadratisch sind
    float rMid = in0 + span * 0.47;
    float bandH = span * 0.5;
    float cells = clamp(hha_cell(TAU * rMid / (bandH * 1.15) + 0.5), 8.0, 64.0);
    float ga = turn * cells;
    float idx = hha_cell(ga);
    float k = (TAU * rMid / cells) / bandH;
    vec2 p = vec2(fract(ga) * k, (s - 0.22) / 0.5);
    float h = hha_hash12(vec2(idx, seed));
    float h2 = hha_hash12(vec2(seed, idx + 13.0));
    float aaCell = ws / 0.5;
    float g = glyph(p, k, h, h2, aaCell) * hha_window(s, 0.2, 0.74, ws);
    float faint = inside * 0.07;
    float reveal = 1.0 - smoothstep(drawn - 0.015, drawn + 0.015, turn);
    if (drawn >= 0.999) reveal = 1.0;
    float e = max(max(lines, slots * 0.75), g * 0.95) + faint;
    e *= reveal;
    vec3 c = mix(vColor.rgb, vec3(1.0), max(lines, g) * 0.18);
    return fx(c, clamp(e, 0.0, 1.0), FX_GLOW, 0.0);
}

// Fleck am Boden (Blut, Gift, Ruß): ausgefranster weicher Rand, dunkelt den Boden ab, leuchtet kaum.
// Fortschritt = Ausbreitung (sichtbarer Radius).
Fx m_stain(vec2 uv, vec2 so, float spread) {
    float R = max(spread, 0.02);
    float r = length(uv) / R;
    float n = hha_fbm(uv * 2.1 + so);
    float n2 = hha_noise(uv * 6.5 + so.yx);
    float edge = 1.0 - r + (n - 0.5) * 0.5 + (n2 - 0.5) * 0.12;
    float aa = px(edge);
    float m = smoothstep(-aa, 0.12 + aa, edge) * (1.0 - smoothstep(0.85, 1.0, length(uv)));
    float lip = smoothstep(-aa, 0.06, edge) * (1.0 - smoothstep(0.06, 0.22, edge));
    float mottle = 0.7 + 0.3 * n2;
    vec3 c = vColor.rgb * (mottle + lip * 0.5);
    return fx(c, m * 0.35, 0.9, m * 0.55);
}

// Staubwolke: weich, matt, treibt langsam. Fortschritt = Dichte (1 frisch, 0 verweht).
Fx m_dust(vec2 uv, vec2 so, float t, float density) {
    float r = length(uv);
    float n = hha_fbm(uv * 1.7 + so + vec2(t * 0.05, t * 0.08));
    float n2 = hha_fbm(uv * 3.9 + so.yx - vec2(0.0, t * 0.12));
    float shape = 1.0 - r * r + (n - 0.5) * 0.9 + (n2 - 0.5) * 0.35;
    float m = smoothstep(0.0, 0.55, shape) * (1.0 - smoothstep(0.7, 1.0, r));
    m *= smoothstep(0.0, 1.0, density);
    vec3 c = vColor.rgb * (0.78 + 0.32 * n2);
    return fx(c, m * 0.5, 0.92, 0.0);
}

// --- Meshes: Fresnel aus Normale und Blickrichtung, Rauschen periodisch in UV ------------------------------------

// Fortschritt < 1 löst die Hülle mit glühender Kante auf (Zerspringen, Verblassen).
float dissolve(float n, float keep, out float front) {
    float th = 1.0 - keep;
    float m = smoothstep(th - 0.06, th + 0.06, n);
    front = keep < 0.999 ? exp(-abs(n - th) * 22.0) : 0.0;
    return keep >= 0.999 ? 1.0 : m;
}

Fx m_shell(vec2 uv, vec2 so, float t, float keep) {
    vec3 N = normalize(vNormal);
    vec3 V = normalize(-vPos);
    float f = 1.0 - abs(dot(N, V));
    float fres = pow(f, 2.4);
    float n = hha_pfbm(vec2(uv.x * 12.0, uv.y * 6.0 - t * 0.9) + vec2(0.0, so.x), vec2(12.0, 4096.0));
    float front;
    float m = dissolve(n, keep, front);
    float e = (fres * (0.55 + 0.6 * n) + 0.035) * m + front * 0.8;
    return fx(hot(vColor.rgb, e), clamp(e, 0.0, 1.0), FX_GLOW, 0.0);
}

Fx m_dome(vec2 uv, vec2 so, float t, float keep) {
    vec3 N = normalize(vNormal);
    vec3 V = normalize(-vPos);
    float f = 1.0 - abs(dot(N, V));
    float fres = pow(f, 3.0);
    float n = hha_pfbm(vec2(uv.x * 16.0, uv.y * 5.0 - t * 0.35) + vec2(0.0, so.y), vec2(16.0, 4096.0));
    float bands = 0.5 + 0.5 * sin((uv.y * 9.0 - t * 0.4 + n * 1.4) * TAU * 0.5);
    float base = 1.0 - smoothstep(0.0, 0.1, uv.y);
    float front;
    float m = dissolve(n, keep, front);
    float e = (fres * 1.05 + 0.05 + bands * 0.07 * (0.4 + f) + base * 0.45) * m + front * 0.85;
    return fx(hot(vColor.rgb, e), clamp(e, 0.0, 1.0), FX_GLOW, 0.0);
}

void main() {
    float t = GameTime * 1200.0;
    int mode = vMode.x;
    float seed = float(vMode.y);
    vec2 so = vec2(hha_hash12(vec2(seed, 1.7)), hha_hash12(vec2(4.3, seed))) * 89.0;
    float inner = vParam.x;
    float prog = vParam.y;
    // Bänder: UV0.y = Querlage × relative Halbbreite, UV2.x = relative Halbbreite. Der Quotient ist auch in
    // verjüngten (trapezförmigen) Vierecken exakt – ohne ihn knickt die Querlage an jeder Viereck-Diagonale.
    vec2 band = vec2(vUV.x, 0.5 + 0.5 * clamp(vUV.y / max(vParam.x, 1e-5), -1.0, 1.0));

    Fx r;
    if (mode == MODE_RIBBON) r = m_ribbon(band, so, t);
    else if (mode == MODE_SHELL) r = m_shell(vUV, so, t, prog);
    else if (mode == MODE_GLOW) r = m_glow(vUV);
    else if (mode == MODE_SWIRL) r = m_swirl(vUV, so, t, inner, prog);
    else if (mode == MODE_SHOCK) r = m_shock(vUV, so, t, inner);
    else if (mode == MODE_FLAME) r = m_flame(band, so, t);
    else if (mode == MODE_RUNE) r = m_rune(vUV, seed, t, inner, prog);
    else if (mode == MODE_STAIN) r = m_stain(vUV, so, prog);
    else if (mode == MODE_TEAR) r = m_tear(band, so, t, prog);
    else if (mode == MODE_DOME) r = m_dome(vUV, so, t, prog);
    else if (mode == MODE_CRACK) r = m_crack(band, so, t, prog);
    else if (mode == MODE_WATER) r = m_water(band, so, t, prog);
    else if (mode == MODE_DUST) r = m_dust(vUV, so, t, prog);
    else r = m_bolt(band, so, t);

    // Nah an der Kamera ausblenden (wie vfx.fsh), Nebel wie im Bestand.
    float nearFade = smoothstep(0.35, 2.2, sphericalVertexDistance);
    float fog = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
    float fade = clamp(vColor.a, 0.0, 1.0) * ColorModulator.a * (1.0 - fog) * nearFade;

    float light = clamp(r.light, 0.0, 1.0) * fade;
    vec3 rgb = r.emit * ColorModulator.rgb * light * FX_GAIN;
    float alpha = clamp(light * r.cover * FX_GAIN + clamp(r.dark, 0.0, 1.0) * fade, 0.0, 1.0);
    if (alpha <= 0.002 && rgb.r + rgb.g + rgb.b <= 0.004) {
        discard;
    }
    fragColor = vec4(rgb, alpha);
}
