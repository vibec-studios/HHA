// HHA-Rauschen für die neuen Effekt-Shader (Überarbeitung 3, P01): `fx_energy`, `fx_solid`.
// Bewusst ohne #version: Die Zeile setzt das Spiel bzw. der Prüflauf (tmp/overhaul3/previews/fx/glcheck.py).
//
// Alles hier ist stetig: Werte-Rauschen mit Quintik-Glättung (glatt bis zur zweiten Ableitung, keine Kanten),
// fbm aus gedrehten Oktaven. Das Gitter (`floor`) gehört nur zum Rauschen selbst, nie zu Zeit, Alpha oder Fläche.
// Die periodischen Fassungen (`hha_pnoise`, `hha_pfbm`) nähen sich an der UV-Naht von Kugel, Kuppel und Torus
// nahtlos zusammen: Periode in Gitterzellen (ganzzahlig), Oktaven verdoppeln Frequenz und Periode.

float hha_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float hha_hash13(vec3 p) {
    vec3 p3 = fract(p * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 hha_quintic(vec2 f) {
    return f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
}

vec3 hha_quintic(vec3 f) {
    return f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
}

// 0..1, stetig.
float hha_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    vec2 u = hha_quintic(f);
    float a = hha_hash12(i);
    float b = hha_hash12(i + vec2(1.0, 0.0));
    float c = hha_hash12(i + vec2(0.0, 1.0));
    float d = hha_hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float hha_noise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = p - i;
    vec3 u = hha_quintic(f);
    float n000 = hha_hash13(i);
    float n100 = hha_hash13(i + vec3(1.0, 0.0, 0.0));
    float n010 = hha_hash13(i + vec3(0.0, 1.0, 0.0));
    float n110 = hha_hash13(i + vec3(1.0, 1.0, 0.0));
    float n001 = hha_hash13(i + vec3(0.0, 0.0, 1.0));
    float n101 = hha_hash13(i + vec3(1.0, 0.0, 1.0));
    float n011 = hha_hash13(i + vec3(0.0, 1.0, 1.0));
    float n111 = hha_hash13(i + vec3(1.0, 1.0, 1.0));
    float x00 = mix(n000, n100, u.x);
    float x10 = mix(n010, n110, u.x);
    float x01 = mix(n001, n101, u.x);
    float x11 = mix(n011, n111, u.x);
    return mix(mix(x00, x10, u.y), mix(x01, x11, u.y), u.z);
}

// Vier gedrehte Oktaven, 0..1 (Mittel 0,5).
float hha_fbm(vec2 p) {
    float sum = 0.0;
    float amp = 0.5;
    mat2 rot = mat2(0.8, 0.6, -0.6, 0.8);
    for (int i = 0; i < 4; i++) {
        sum += amp * hha_noise(p);
        p = rot * p * 2.03 + vec2(17.1, 9.7);
        amp *= 0.5;
    }
    return sum / 0.9375;
}

float hha_fbm(vec3 p) {
    float sum = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += amp * hha_noise(p);
        p = p.yzx * 2.03 + vec3(17.1, 9.7, 4.3);
        amp *= 0.5;
    }
    return sum / 0.9375;
}

// Periodisch in beiden Achsen mit der Periode `period` (Gitterzellen, ganzzahlig).
float hha_pnoise(vec2 p, vec2 period) {
    vec2 i = floor(p);
    vec2 f = p - i;
    vec2 u = hha_quintic(f);
    vec2 i0 = mod(i, period);
    vec2 i1 = mod(i + 1.0, period);
    float a = hha_hash12(i0);
    float b = hha_hash12(vec2(i1.x, i0.y));
    float c = hha_hash12(vec2(i0.x, i1.y));
    float d = hha_hash12(i1);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Vier Oktaven ohne Drehung (sonst ginge die Periode verloren), 0..1.
float hha_pfbm(vec2 p, vec2 period) {
    float sum = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += amp * hha_pnoise(p, period);
        p *= 2.0;
        period *= 2.0;
        amp *= 0.5;
    }
    return sum / 0.9375;
}

// Grat-Rauschen: 1 auf der Mittellinie des Rauschens, fällt weich ab (Adern, Haarrisse).
float hha_ridge(float n) {
    return 1.0 - abs(n * 2.0 - 1.0);
}

// Nummer der Zelle, in der x liegt (Runenzeichen je Feld). Kein Raster der Fläche: Die Kanten der Zeichen
// entstehen weich über `hha_line`/`smoothstep`.
float hha_cell(float x) {
    return floor(x);
}

// Weicher Strich: 1 innerhalb der halben Breite `halfWidth` um die Strecke a-b, Kante über `aa` (Pixelbreite).
float hha_line(vec2 p, vec2 a, vec2 b, float halfWidth, float aa) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-6), 0.0, 1.0);
    float d = length(pa - ba * h);
    return 1.0 - smoothstep(halfWidth - aa, halfWidth + aa, d);
}

// Weiches Fenster: 1 zwischen lo und hi, Kanten über `aa`.
float hha_window(float x, float lo, float hi, float aa) {
    return smoothstep(lo - aa, lo + aa, x) * (1.0 - smoothstep(hi - aa, hi + aa, x));
}
