#version 330

// HHA-Tunnelshader (PLAN 4.1, Paket P18): der Lichttunnel der RPG-Lobby und der Reise. Varianten über Pipeline-Defines:
//   TUNNEL_HEAVEN – Himmelsweg: warmes Goldweiß, federartige Schlieren
//   TUNNEL_HELL   – Unterweltweg: Seelenblau, dunkle Rippen, Glutfunken
//   TUNNEL_TINT   – Addon-Wege: Wegfarbe aus Color.rgb
//   TUNNEL_GLOW   – Lichtringe unter den Füßen und der Schleier beim Wegwechsel (additiv, über den Figuren)
//
// Alles ist stetig: keine Zeit- oder Alpha-Stufen, kein Raster. Die Fahrt steckt in UV0.y (mod 256); jedes Muster ist
// entlang der Röhre mit einer Teilerperiode von 256 gebaut, damit der Umbruch nahtlos bleibt. Lichtkörner liegen in zwei
// gedachten Röhren mit kleinerem Radius (Parallaxe über die Sichttiefe). Das Ergebnis ist vormultipliziert: Im
// Austrittskreis (exitOpen) wird es durchsichtig, dahinter liegt die Welt. Vorschau ohne Spiel:
// tmp/overhaul3/previews/tunnel/glcheck.py (dort steht dieselbe Geometrie).

in vec2 texCoord0;
in vec4 vertexColor;
in float viewDepth;
in float vSpeed;
in float vExit;

out vec4 fragColor;

const float TAU = 6.28318531;
const float RADIUS = 9.0;
const float CIRCUMFERENCE = 56.5486678;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Wertrauschen, gekachelt mit der Periode [per] (in Zellen, ganzzahlig).
float vnoise(vec2 x, vec2 per) {
    vec2 i = floor(x);
    vec2 f = fract(x);
    vec2 w = f * f * (3.0 - 2.0 * f);
    float a = hash12(mod(i, per));
    float b = hash12(mod(i + vec2(1.0, 0.0), per));
    float c = hash12(mod(i + vec2(0.0, 1.0), per));
    float d = hash12(mod(i + vec2(1.0, 1.0), per));
    return mix(mix(a, b, w.x), mix(c, d, w.x), w.y);
}

float fbm(vec2 x, vec2 per) {
    float s = 0.0;
    float amp = 0.5;
    for (int o = 0; o < 3; o++) {
        s += amp * vnoise(x, per);
        x *= 2.0;
        per *= 2.0;
        amp *= 0.5;
    }
    return s / 0.875;
}

// Weicher Lichtpunkt in Weltmaß; [fw] = Weltmaß je Bildpunkt (gegen Flimmern in der Ferne: der Punkt wird nie kleiner
// als ein Bildpunkt, seine Energie bleibt gleich).
float softDot(vec2 d, float size, float fw) {
    float s = max(size, fw * 1.2);
    return exp(-dot(d, d) / (s * s)) * (size * size) / (s * s);
}

// Lichtkörner einer gedachten inneren Röhre mit Radius [r]; [nu] Zellen im Umlauf, Zellen 4 Blöcke lang.
float motes(float u, float p, float dz, float r, float nu, float seed, float stretch) {
    float pl = p - dz * (1.0 - r / RADIUS);
    vec2 cell = vec2(u * nu, pl / 4.0);
    vec2 i = floor(cell);
    vec2 f = fract(cell);
    vec2 key = mod(i, vec2(nu, 64.0)) + seed;
    float h = hash12(key);
    if (h > 0.42) return 0.0;
    vec2 c = vec2(0.25, 0.25) + 0.5 * vec2(hash12(key + 7.1), hash12(key + 3.7));
    vec2 scale = vec2(TAU * r / nu, 4.0);
    vec2 d = (f - c) * scale;
    d.y /= stretch;
    vec2 fwv = fwidth(vec2(u * TAU * r, pl));
    float fw = max(fwv.x, fwv.y / stretch);
    float twinkle = 0.55 + 0.45 * sin(TAU * (h * 9.0 + pl / 64.0));
    return softDot(d, 0.075 + 0.05 * hash12(key + 1.3), fw) * twinkle;
}

#ifdef TUNNEL_GLOW

void main() {
    vec2 uv = texCoord0;
    float shape = floor(vSpeed + 0.5);
    float a = 0.0;
    if (shape < 0.5) {
        // Lichtring unter den Füßen: Ring mit weichem Hof, innen ein zarter Schein
        float r = length(uv);
        a = exp(-pow((r - 0.72) / 0.10, 2.0)) + 0.28 * exp(-r * r * 2.6);
        a *= 1.0 - smoothstep(0.92, 1.0, r);
    } else {
        // Schleier beim Wegwechsel: ein Lichtband zieht von oben nach unten über die Figur
        float c = 1.3 - vExit * 2.6;
        float across = 1.0 - uv.x * uv.x;
        a = exp(-pow((uv.y - c) / 0.22, 2.0)) * across * across;
        a += 0.25 * exp(-pow((uv.y - c) / 0.7, 2.0)) * across;
    }
    fragColor = vec4(vertexColor.rgb * vertexColor.a * a, 0.0);
}

#else

void main() {
    float u = texCoord0.x;
    float p = texCoord0.y;
    float dz = max(viewDepth, 0.0);
    float speed = max(vSpeed, 0.0);
    float k = clamp(speed / 6.0, 0.0, 1.0);
    float stretch = 1.0 + speed * 1.1;

    // Tiefe: nah dunkel, fern hell (Fluchtpunkt); beim Rasen rückt das Licht näher
    float depthT = smoothstep(4.0, mix(215.0, 150.0, k), dz);
    float core = pow(depthT, mix(2.6, 1.8, k));

    // Lichtbänder: drei winden sich vorwärts, zwei gegenläufig, weich verbogen
    float warp = fbm(vec2(u * 6.0, p / 16.0), vec2(6.0, 16.0)) - 0.5;
    float b1 = 0.5 + 0.5 * cos(TAU * (u * 3.0 + p / 64.0 + warp * 0.35));
    float b2 = 0.5 + 0.5 * cos(TAU * (u * 5.0 - p / 128.0 + warp * 0.25 + 0.3));
    float bands = pow(b1, mix(9.0, 4.5, k)) + 0.45 * pow(b2, 14.0);

    // Schlieren entlang der Fahrt: kurz im Ruhetempo, lang beim Rasen (zwei Lagen mit festen Perioden)
    float shortStreak = vnoise(vec2(u * 48.0, p / 2.0), vec2(48.0, 128.0));
    float longStreak = vnoise(vec2(u * 48.0, p / 16.0), vec2(48.0, 16.0));
    float streak = mix(shortStreak, longStreak, smoothstep(0.1, 0.7, k));
    streak = smoothstep(0.55, 0.95, streak);

    vec3 baseNear;
    vec3 baseFar;
    vec3 bandCol;
    vec3 coreCol;
    vec3 moteCol;
#if defined(TUNNEL_HELL)
    baseNear = vec3(0.006, 0.016, 0.040);
    baseFar = vec3(0.045, 0.22, 0.38);
    bandCol = vec3(0.20, 0.78, 1.00);
    coreCol = vec3(0.80, 0.96, 1.00);
    moteCol = vec3(0.55, 0.88, 1.00);
#elif defined(TUNNEL_TINT)
    vec3 tint = vertexColor.rgb;
    baseNear = tint * 0.07;
    baseFar = tint * 0.50;
    bandCol = mix(tint, vec3(1.0), 0.25);
    coreCol = mix(tint, vec3(1.0), 0.85);
    moteCol = mix(tint, vec3(1.0), 0.5);
#else
    baseNear = vec3(0.10, 0.065, 0.028);
    baseFar = vec3(0.66, 0.49, 0.22);
    bandCol = vec3(1.00, 0.80, 0.46);
    coreCol = vec3(1.00, 0.97, 0.90);
    moteCol = vec3(1.00, 0.90, 0.66);
#endif

    // Rauschen über der Grundfläche, damit die Wand nie glatt-flach wirkt
    float cloud = fbm(vec2(u * 12.0 + warp, p / 8.0), vec2(12.0, 32.0));
    vec3 col = mix(baseNear, baseFar, depthT) * (0.75 + 0.5 * cloud);
    col += bandCol * bands * mix(0.30, 0.85, depthT) * (0.8 + 0.7 * k);
    col += bandCol * streak * (0.10 + 0.35 * k) * (0.4 + 0.6 * depthT);

#if defined(TUNNEL_HELL)
    // Dunkle Rippen quer zur Röhre und aufglimmende Glut
    float rib = pow(0.5 + 0.5 * cos(TAU * p / 16.0), 22.0);
    col *= 1.0 - 0.72 * rib * (1.0 - 0.7 * depthT);
    vec2 ec = vec2(u * 72.0, p / 4.0);
    vec2 ei = floor(ec);
    vec2 ekey = mod(ei, vec2(72.0, 64.0));
    float eh = hash12(ekey + 11.0);
    if (eh < 0.16) {
        vec2 ecen = vec2(0.3, 0.3) + 0.4 * vec2(hash12(ekey + 2.0), hash12(ekey + 5.0));
        vec2 ed = (fract(ec) - ecen) * vec2(CIRCUMFERENCE / 72.0, 4.0);
        ed.y /= stretch;
        vec2 efw = fwidth(vec2(u * CIRCUMFERENCE, p));
        float flicker = 0.6 + 0.4 * sin(TAU * (eh * 13.0 + p / 32.0));
        col += vec3(1.00, 0.52, 0.18) * softDot(ed, 0.10, max(efw.x, efw.y / stretch)) * flicker * 1.6;
    }
#elif !defined(TUNNEL_TINT)
    // Federartige Schlieren: feine helle Fäden entlang der Fahrt
    float fe = fbm(vec2(u * 28.0 + warp * 1.5, p / 32.0), vec2(28.0, 8.0));
    float feather = pow(1.0 - abs(2.0 * fe - 1.0), 10.0);
    col += vec3(1.00, 0.94, 0.80) * feather * (0.14 + 0.26 * depthT) * (1.0 + 0.6 * k);
#endif

    // Lichtkörner in zwei Tiefen
    float m = motes(u, p, dz, 6.2, 40.0, 17.0, stretch) + 0.8 * motes(u, p, dz, 3.6, 24.0, 41.0, stretch);
    col += moteCol * m * 1.4;

    // Heller Fluchtpunkt, beim Rasen heller
    col = mix(col, coreCol * 1.3, core);
    col *= 1.0 + 0.45 * k;

    // Weiche Lichter (kein hartes Abschneiden)
    col = vec3(1.0) - exp(-col * 1.15);

    // Lichtgipfel: weißgoldenes Bild
    float flash = clamp(vertexColor.a, 0.0, 1.0);
    col = mix(col, vec3(1.0, 0.95, 0.84), flash);

    // Austrittskreis: alles hinter der Tiefe D wird durchsichtig; D wandert zum Betrachter, der Kreis wächst so
    // gleichmäßig über das Bild (Bildanteil ≈ 2,4 · e^1,5). Am Rand ein heller Saum.
    float e = clamp(vExit, 0.0, 1.0);
    float frac = 2.4 * pow(e, 1.5);
    float depthD = 16.2 / max(frac, 0.05) - 8.0 * e * e * e * e;
    float w = max(depthD * 0.18, 0.8);
    float vis = 1.0 - smoothstep(depthD - w, depthD + w * 0.2, dz);
    float rim = exp(-pow((dz - (depthD - w * 0.6)) / (w * 0.55), 2.0)) * smoothstep(0.0, 0.08, e);
    col = min(col + coreCol * rim * 0.6 * (1.0 - flash), vec3(1.0));

    // Feines Zittern gegen Farbstufen in den Verläufen
    col += (hash12(gl_FragCoord.xy) - 0.5) / 255.0;

    fragColor = vec4(col * vis, vis);
}

#endif
