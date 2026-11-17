#version 330

// HHA-Bildschirm-Pass (Überarbeitung 3, P02, PLAN 6.3): verbiegt bzw. tönt das fertige Weltbild. Fünf Arten in einem
// Shader (Art = BossFx.Screen-Ordinal). Alles stetig gerechnet: keine Zeit-, Alpha- oder Rasterstufen, volle Auflösung
// (anders als Voids void_lens.fsh, das die Strahlen bewusst in ein grobes Raster setzt).
//
// Tiefe: Dinge vor dem Effekt bleiben scharf (Tiefenvergleich wie bei Void). Der Tiefen-Sampler kennt keine
// halbtransparenten Dinge (Wasser, Partikel); die werden wie der Hintergrund dahinter behandelt.

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform HhaScreen {
    vec4 CenterRadius; // xyz = Mitte relativ zur Kamera (Weltachsen), w = Radius in Blöcken
    vec4 Params;       // x = Art, y = Stärke (schon mal Verzerrungseffekte), z = Zeit seit Beginn (s), w = sicherer Radius
    vec4 Tint;         // rgb = Farbe (0..1), a = Hüllkurve 0..1
    vec4 Extra;        // xyz = Mitte der sicheren Zone relativ zur Kamera, w = Fortschritt 0..1 über die Dauer
};

in vec2 texCoord;
flat in mat4 viewProj;
flat in mat4 invViewProj;

out vec4 fragColor;

const int KIND_SHOCK = 0;
const int KIND_HEAT = 1;
const int KIND_ECLIPSE = 2;
const int KIND_TIME = 3;
const int KIND_LENS = 4;

const float NO_HIT = 1.0e6;
const float PI = 3.14159265;

// Linse: höchstens so viele Schritte je Pixel (Void: 110 auf einem groben Raster; hier volle Auflösung)
const int LENS_STEPS = 48;

// Stoßring: größte Verschiebung (Bildanteil) bei Stärke 1
const float SHOCK_SHIFT = 0.04;
// Hitze: größte Verschiebung bei Stärke 1, Wellen je Bildhöhe
const float HEAT_SHIFT = 0.006;
const float HEAT_FREQ = 140.0;
// Zeitstopp: Geschwindigkeit der beiden Wellenringe (Umläufe je Sekunde) und ihre Verschiebung
const float TIME_RING_RATE = 0.18;
const float TIME_SHIFT = 0.012;

// ---------------------------------------------------------------------------------------------- Hilfen

vec3 rayDir(vec2 uv) {
    vec4 far = invViewProj * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    return normalize(far.xyz / far.w);
}

float sceneDistanceAt(vec2 uv) {
    float depth = texture(DepthSampler, uv).r;
    if (depth >= 0.99999) return NO_HIT;
    vec4 point = invViewProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return length(point.xyz / point.w);
}

vec2 toScreen(vec3 p) {
    vec4 clip = viewProj * vec4(p, 1.0);
    return clip.xy / max(clip.w, 1.0e-3) * 0.5 + 0.5;
}

vec3 sceneAt(vec2 uv) {
    return texture(SceneSampler, clamp(uv, vec2(0.0005), vec2(0.9995))).rgb;
}

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Farbe mit hellstem Kanal 1 (Tönung ohne Helligkeitsänderung)
vec3 hue(vec3 c) {
    return c / max(max(c.r, c.g), max(c.b, 1.0e-3));
}

// Verschiebung nur übernehmen, wenn das verschobene Pixel nicht deutlich vor dem eigenen liegt: Dinge vor dem Effekt
// verschmieren nicht in ihn hinein.
vec2 keepFront(vec2 offset, float sceneDist) {
    float there = sceneDistanceAt(clamp(texCoord + offset, vec2(0.0), vec2(1.0)));
    // Spielraum wächst mit der Entfernung (flacher Boden in der Ferne ändert die Tiefe schnell)
    float tolerance = max(1.5, min(sceneDist, 400.0) * 0.15);
    float k = smoothstep(tolerance, tolerance * 0.33, sceneDist - there);
    return offset * k;
}

// Abstand des Pixels zur Kugelschale um c: auf Flächen über den Weltpunkt des Pixels (die Welle läuft über Boden und
// Wände), am Himmel über den kleinsten Abstand des Sichtstrahls (Umriss der Schale). Liefert d (Abstand zur Mitte),
// den Punkt auf dem Strahl und die Richtung von der Mitte weg.
float shellDistance(vec3 dir, float sceneDist, vec3 c, out vec3 point, out vec3 radial) {
    float t = sceneDist < NO_HIT ? sceneDist : max(dot(c, dir), 0.5);
    point = dir * t;
    vec3 rel = point - c;
    float d = length(rel);
    radial = rel / max(d, 1.0e-3);
    return d;
}

// Bildrichtung, in die „von der Mitte weg“ an diesem Punkt zeigt
vec2 screenRadial(vec3 point, vec3 radial) {
    float t = length(point);
    vec2 a = toScreen(point);
    vec2 b = toScreen(point + radial * max(0.05 * t, 0.1));
    vec2 d = b - a;
    float l = length(d);
    return l > 1.0e-6 ? d / l : vec2(0.0);
}

// Ring um R mit Breite w: x·e^(−x²) verschiebt wie eine Linse (außen weg, innen hin), e^(−2x²) ist der Schein.
vec3 ring(vec3 dir, float sceneDist, vec3 c, float R, float w) {
    vec3 point;
    vec3 radial;
    float d = shellDistance(dir, sceneDist, c, point, radial);
    float x = (d - R) / max(w, 1.0e-3);
    if (abs(x) > 3.5) return vec3(0.0);
    vec2 sd = screenRadial(point, radial);
    // Ferne Ringe verschieben im Bild weniger
    float perspective = clamp(8.0 / max(length(point), 0.5), 0.25, 1.0);
    float shift = x * exp(-x * x) * 2.33 * perspective;
    return vec3(sd * shift, exp(-2.0 * x * x));
}

// ---------------------------------------------------------------------------------------------- Linse

vec3 lensDeep(vec3 tint) {
    return tint * 0.02 + vec3(0.004, 0.0, 0.012);
}

vec3 sampleBent(vec3 worldPos, vec3 tint) {
    vec4 clip = viewProj * vec4(worldPos, 1.0);
    if (clip.w <= 0.001) return lensDeep(tint);
    vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
    // Farbsaum wächst mit der Ablenkung (Abstand zum geraden Pixel)
    vec2 shift = (uv - texCoord) * 0.03;
    vec3 color = vec3(sceneAt(uv + shift).r, sceneAt(uv).g, sceneAt(uv - shift).b);
    vec2 edge = smoothstep(vec2(-0.25), vec2(0.04), uv) * smoothstep(vec2(1.25), vec2(0.96), uv);
    return mix(lensDeep(tint), color, edge.x * edge.y);
}

vec4 shadeDisk(vec3 hit, vec3 rayDirection, vec3 normal, float rs, float outer, float time, vec3 tint) {
    vec3 axisU = normalize(cross(normal, abs(normal.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0)));
    vec3 axisV = cross(normal, axisU);
    vec2 f = vec2(dot(hit, axisU), dot(hit, axisV));
    float r = length(f);
    float inner = rs * 2.3;
    float x = (r - inner) / max(outer - inner, 1.0e-3);
    if (x <= 0.0 || x >= 1.0) return vec4(0.0);

    float angle = atan(f.y, f.x);
    // Kepler-Scherung: innen schneller als außen, zieht die Struktur zu Spiralen (ganzzahlige Winkel-Vielfache: keine Naht)
    float spin = angle + time * 1.9 * pow(inner / r, 1.5);
    float logR = log(r / rs);
    float arms = 0.5 + 0.5 * sin(spin * 3.0 + logR * 7.0);
    float fine = 0.5 + 0.5 * sin(spin * 7.0 - logR * 13.0 + 1.7) * sin(spin * 2.0 + logR * 3.0 - time * 0.4);
    float density = arms * 0.6 + fine * 0.5;

    // Die auf uns zulaufende Seite strahlt heller (stetig)
    vec3 orbit = cross(normal, normalize(axisU * f.x + axisV * f.y));
    float beta = 0.55 * sqrt(inner / r);
    float doppler = 1.0 / max(0.25, 1.0 + beta * dot(orbit, rayDirection));
    float beam = clamp(doppler * doppler, 0.35, 2.2);

    float heat = pow(1.0 - x, 1.6);
    vec3 hot = mix(tint, vec3(1.0), 0.78);
    vec3 cold = tint * 0.32;
    vec3 color = mix(cold, tint, smoothstep(0.0, 0.55, heat));
    color = mix(color, hot, smoothstep(0.5, 1.0, heat));
    float alpha = smoothstep(0.28, 0.85, density) * smoothstep(0.0, 0.12, x) * (1.0 - smoothstep(0.65, 1.0, x));
    return vec4(color * (0.55 + heat) * beam, clamp(alpha * 0.95, 0.0, 0.95));
}

// Sichere Zone: senkrechter Zylinder um Extra.xyz mit Radius Params.w. Dort wird nicht verzerrt.
float safeMask(vec3 dir, float sceneDist) {
    float safeRadius = Params.w;
    if (safeRadius <= 0.01 || sceneDist >= NO_HIT) return 0.0;
    vec3 p = dir * sceneDist - Extra.xyz;
    float r = length(p.xz);
    float vertical = 1.0 - smoothstep(4.0, 5.0, abs(p.y));
    return (1.0 - smoothstep(safeRadius * 0.9, safeRadius * 1.1, r)) * vertical;
}

vec3 lens(vec3 scene, vec3 dir, float sceneDist, float intensity, float env, float time, vec3 tint) {
    vec2 centeredReal = texCoord * 2.0 - 1.0;
    float vignette = smoothstep(0.35, 1.5, length(centeredReal));
    vec3 dimColor = mix(vec3(0.55, 0.5, 0.72), hue(tint) * 0.6, 0.25);
    vec3 dimmed = mix(scene, scene * dimColor, intensity * (0.06 + 0.2 * vignette));

    vec3 center = CenterRadius.xyz;
    float influence = max(CenterRadius.w, 0.5);
    // Horizont und Scheibe öffnen sich mit der Hüllkurve und der Stärke
    float rs = influence / 10.0 * (0.3 + 0.7 * env) * (0.5 + 0.5 * intensity);
    float outer = influence * 0.62 * (0.55 + 0.45 * env);
    // Scheibe um gut 30° zum Betrachter gekippt (sonst sieht man sie aus Augenhöhe nur als Strich), wankt langsam
    vec3 toCam = -center;
    toCam.y = 0.0;
    toCam = dot(toCam, toCam) > 1.0e-4 ? normalize(toCam) : vec3(0.0, 0.0, 1.0);
    vec3 normal = normalize(vec3(0.0, 1.0, 0.0) + toCam * 0.62
        + vec3(0.12 * sin(time * 0.21), 0.0, 0.10 * cos(time * 0.17)));

    float along = dot(center, dir);
    float camDist2 = dot(center, center);
    float closest2 = camDist2 - along * along;
    bool cameraInside = camDist2 < influence * influence;
    if (!cameraInside && (along < 0.0 || closest2 > influence * influence)) return dimmed;

    float entry = cameraInside ? 0.0 : along - sqrt(max(0.0, influence * influence - closest2));
    // Dinge vor dem Einflussbereich bleiben scharf
    if (sceneDist < entry) return dimmed;

    vec3 rel = dir * entry - center;
    vec3 vel = dir;
    float travelled = entry;
    vec3 angular = cross(rel, vel);
    float h2 = dot(angular, angular);

    vec3 accum = vec3(0.0);
    float transmit = 1.0;
    float minR = 1.0e6;
    bool captured = false;
    bool blocked = false;

    for (int i = 0; i < LENS_STEPS; i++) {
        float r2 = dot(rel, rel);
        float r = sqrt(r2);
        minR = min(minR, r);
        if (r < rs) {
            captured = true;
            break;
        }
        if (travelled > sceneDist) {
            blocked = true;
            break;
        }
        if (r > influence && dot(rel, vel) > 0.0) break;

        float dt = clamp(0.25 * (r - rs * 0.7), rs * 0.05, rs * 2.0);
        // Schwarzschild-Näherung; zum Rand hin ausgeblendet, damit keine Naht entsteht
        float window = 1.0 - smoothstep(influence * 0.5, influence, r);
        vec3 acc = -1.5 * rs * h2 * rel / (r2 * r2 * r) * window;
        vec3 prev = rel;
        vel += acc * dt;
        rel += normalize(vel) * dt;
        travelled += dt;

        float d0 = dot(prev, normal);
        float d1 = dot(rel, normal);
        if (d0 * d1 < 0.0 && transmit > 0.01) {
            vec3 hit = mix(prev, rel, d0 / (d0 - d1));
            vec4 disk = shadeDisk(hit, normalize(vel), normal, rs, outer, time, tint);
            accum += disk.rgb * disk.a * transmit;
            transmit *= 1.0 - disk.a;
        }
    }

    vec3 background = vec3(0.0);
    vec3 emission = accum;
    if (!captured) {
        float remaining = blocked ? 0.0 : clamp(sceneDist - travelled, 2.0, 1000.0);
        background = sampleBent(rel + center + normalize(vel) * remaining, tint);
        // Gleiche Abdunklung wie außerhalb, damit am Rand keine Naht entsteht
        background = mix(background, background * dimColor, intensity * (0.06 + 0.2 * vignette));
        float near = smoothstep(influence, rs * 1.5, minR);
        background = mix(background, background * dimColor * 0.9, intensity * near);

        // Photonenring und Halo als weiche Glocken
        float g = (minR - rs * 1.32) / rs;
        vec3 hot = mix(tint, vec3(1.0), 0.8);
        emission += hot * 1.2 * exp(-(g * g) / 0.0081);
        emission += tint * 0.7 * exp(-(g * g) / 0.05);
        emission += tint * 0.3 * exp(-max(minR - rs * 1.6, 0.0) / (rs * 0.8));
    }

    vec3 color = background * transmit + min(emission, vec3(1.0)) * intensity;

    // Sichere Zone: gerades, kaum abgedunkeltes Bild, der Schein nur schwach darüber
    float safe = safeMask(dir, sceneDist);
    vec3 clear = mix(scene, dimmed, 0.5) + min(accum, vec3(1.0)) * intensity * 0.35;
    return mix(color, clear, safe);
}

// ---------------------------------------------------------------------------------------------- Finsternis

vec3 eclipse(vec3 col, vec3 dir, float s, float time, float aspect, vec3 tint) {
    vec3 cool = mix(vec3(1.0), hue(tint), 0.6);
    // Entsättigen in die kühle Farbe, dann abdunkeln
    col = mix(col, vec3(luma(col)) * cool, 0.45 * s);
    col *= mix(1.0, 0.45, s);

    // Weicher Rand
    vec2 q = (texCoord - 0.5) * vec2(aspect, 1.0);
    float vr = length(q) / length(vec2(aspect, 1.0) * 0.5);
    col *= 1.0 - 0.6 * s * smoothstep(0.35, 1.0, vr);

    // Kühler Lichtkranz um das Bild, atmet sehr langsam (weit unter 2 Hz)
    float angle = atan(q.y, q.x);
    float breathe = 0.85 + 0.15 * sin(time * 0.6 + angle * 2.0);
    float kranz = (vr - 0.72) / 0.12;
    col += cool * 0.10 * s * exp(-kranz * kranz) * breathe;

    // Liegt die Mitte sichtbar vor uns (nicht um die Kamera), bekommt sie eine dunkle Scheibe mit Korona
    vec3 c = CenterRadius.xyz;
    float dist = length(c);
    float visible = smoothstep(CenterRadius.w, CenterRadius.w * 1.5, dist);
    if (visible > 0.0 && dot(c, dir) > 0.0) {
        float ang = acos(clamp(dot(dir, c / dist), -1.0, 1.0));
        float disk = atan(CenterRadius.w * 0.18, dist);
        float inside = 1.0 - smoothstep(disk * 0.92, disk, ang);
        float corona = exp(-max(ang - disk, 0.0) / (disk * 0.4)) * smoothstep(disk * 0.9, disk * 1.02, ang);
        col *= 1.0 - 0.8 * s * visible * inside;
        col += cool * 0.35 * s * visible * corona * breathe;
    }
    return col;
}

// ---------------------------------------------------------------------------------------------- Ablauf

void main() {
    int kind = int(Params.x + 0.5);
    float env = clamp(Tint.a, 0.0, 1.0);
    float s = Params.y * env;
    vec3 tint = Tint.rgb;
    float time = Params.z;
    vec3 c = CenterRadius.xyz;
    float radius = max(CenterRadius.w, 0.05);
    vec2 size = vec2(textureSize(SceneSampler, 0));
    float aspect = size.x / max(size.y, 1.0);

    if (s <= 0.0005) {
        fragColor = vec4(sceneAt(texCoord), 1.0);
        return;
    }

    vec3 dir = rayDir(texCoord);
    float sceneDist = sceneDistanceAt(texCoord);

    if (kind == KIND_LENS) {
        fragColor = vec4(lens(sceneAt(texCoord), dir, sceneDist, s, env, time, tint), 1.0);
        return;
    }

    vec2 offset = vec2(0.0);
    float glow = 0.0;

    if (kind == KIND_SHOCK) {
        // Ein Ring läuft von der Mitte aus und verebbt dabei
        float k = clamp(Extra.w, 0.0, 1.0);
        float grow = 1.0 - (1.0 - k) * (1.0 - k);
        float R = radius * (0.06 + 0.94 * grow);
        float w = radius * 0.07 + R * 0.05;
        float fade = 1.0 - k;
        vec3 r = ring(dir, sceneDist, c, R, w);
        offset = r.xy * SHOCK_SHIFT * s * fade;
        glow = r.z * 0.14 * s * fade;
    } else if (kind == KIND_HEAT) {
        // Heiße Luft als Kugel: was man durch sie sieht, flimmert; was davor steht, nicht
        float along = dot(c, dir);
        float b2 = max(dot(c, c) - along * along, 0.0);
        float R2 = radius * radius;
        if (b2 < R2) {
            float h = sqrt(R2 - b2);
            float entry = max(along - h, 0.0);
            float exit = min(along + h, sceneDist);
            float thick = clamp((exit - entry) / (2.0 * radius), 0.0, 1.0);
            float m = smoothstep(0.0, 0.7, thick) * (1.0 - smoothstep(0.55, 1.0, sqrt(b2) / radius));
            vec2 q = vec2(texCoord.x * aspect, texCoord.y) * HEAT_FREQ;
            // Gedrehte Sinuswellen, die langsam nach oben steigen (stetig, kein Rauschgitter)
            vec2 n = vec2(
                sin(q.y * 1.00 - time * 3.1 + sin(q.x * 0.37 + time * 0.9) * 1.7) + 0.5 * sin(q.y * 2.13 - q.x * 0.41 - time * 4.3),
                sin(q.x * 0.83 + q.y * 0.55 - time * 2.6) + 0.5 * sin(q.x * 1.71 - q.y * 1.37 - time * 3.7)
            ) / 1.5;
            offset = n * HEAT_SHIFT * s * m;
        }
    } else if (kind == KIND_TIME) {
        // Zwei langsame Wellenringe, gegeneinander um eine halbe Runde versetzt; jeder blendet an beiden Enden aus
        for (int i = 0; i < 2; i++) {
            float ph = fract(time * TIME_RING_RATE + float(i) * 0.5);
            float a = sin(PI * ph);
            float R = radius * (0.08 + 0.92 * ph);
            vec3 r = ring(dir, sceneDist, c, R, radius * 0.06 + R * 0.04);
            offset += r.xy * TIME_SHIFT * s * a;
            glow += r.z * 0.11 * s * a;
        }
    }

    vec3 col;
    if (dot(offset, offset) > 1.0e-12) {
        offset = keepFront(offset, sceneDist);
        // Leichter Farbsaum entlang der Verschiebung
        col = vec3(sceneAt(texCoord + offset * 1.12).r, sceneAt(texCoord + offset).g, sceneAt(texCoord + offset * 0.88).b);
    } else {
        col = sceneAt(texCoord);
    }

    if (kind == KIND_TIME) {
        // Entsättigung mit kühlem Stich, Kontrast etwas zurück
        col = mix(col, vec3(luma(col)) * vec3(0.93, 0.97, 1.06), 0.85 * s);
        col = mix(col, col * 0.9 + 0.04, 0.3 * s);
        col += mix(vec3(1.0), hue(tint), 0.5) * glow;
    } else if (kind == KIND_ECLIPSE) {
        col = eclipse(col, dir, s, time, aspect, tint);
    } else {
        col += hue(tint) * glow;
    }
    fragColor = vec4(col, 1.0);
}
