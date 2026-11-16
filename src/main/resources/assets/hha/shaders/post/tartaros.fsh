#version 330

#moj_import <minecraft:globals.glsl>

// Die Farbe der Unterwelt: kalte, grünstichige Schatten, warme Glut in den Lichtern, Aschedunst in der Ferne.
// Überarbeitung 2 (ruhiger): Flimmern nur noch ein Hauch in großer Ferne, Schein nur um wirklich Helles und klein,
// Vignette halb so stark (Werte in post_effect/tartaros.json). Bewusst ohne Pixelraster: alle Abtastungen sind
// bilinear und das Korn liegt unter der Wahrnehmungsschwelle einzelner Pixel.

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform TartarosConfig {
    vec4 ShadowTint;
    vec4 HighlightTint;
    vec4 HazeColor;
    float HazeStart;
    float HazeEnd;
    float Shimmer;
    float Glow;
    float Vignette;
};

out vec4 fragColor;

const float NEAR = 0.05;
const float FAR = 768.0;

float linearDepth(float d) {
    float z = d * 2.0 - 1.0;
    return (2.0 * NEAR * FAR) / (FAR + NEAR - z * (FAR - NEAR));
}

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// Weiches Wertrauschen für das Flimmern: keine harten Kanten, kein Raster.
float smoothNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    // GameTime läuft einmal pro Spieltag von 0 bis 1; in Sekunden umgerechnet bleibt die Bewegung gleichmäßig.
    float seconds = GameTime * 1200.0;
    vec2 texel = 1.0 / InSize;

    float rawDepth = texture(DepthSampler, texCoord).r;
    float dist = rawDepth >= 1.0 ? FAR : linearDepth(rawDepth);
    // Erst ab dem Dunst-Anfang, damit Nahes nie wabert.
    float far = smoothstep(HazeStart, HazeEnd, dist);

    // Flimmern wie über heißem Stein, nur in der Ferne und am Himmel, nie direkt vor dem Gesicht.
    vec2 wobble = vec2(
        smoothNoise(texCoord * vec2(9.0, 14.0) + vec2(0.0, seconds * 0.3)) - 0.5,
        smoothNoise(texCoord * vec2(13.0, 8.0) - vec2(seconds * 0.2, 0.0)) - 0.5
    );
    vec2 uv = texCoord + wobble * texel * Shimmer * far;

    vec3 color = texture(InSampler, uv).rgb;

    // Leiser Schein: Nur was sehr hell ist, strahlt in einem kleinen Kreis aus. Zwölf bilineare Abtastungen auf
    // zwei Ringen.
    vec3 bloom = vec3(0.0);
    float weight = 0.0;
    for (int ring = 1; ring <= 2; ring++) {
        float radius = float(ring) * 3.5;
        for (int i = 0; i < 6; i++) {
            float a = (float(i) + float(ring) * 0.5) * 1.0471976;
            vec3 s = texture(InSampler, uv + vec2(cos(a), sin(a)) * texel * radius).rgb;
            float w = 1.0 / float(ring);
            bloom += max(s - 0.74, 0.0) * w;
            weight += w;
        }
    }
    bloom /= weight;
    color += bloom * Glow * vec3(1.0, 0.92, 0.78);

    // Farbgebung: Schatten kalt und grünlich, Lichter glutwarm, dazwischen etwas weniger Sättigung.
    float l = luma(color);
    vec3 grey = vec3(l);
    color = mix(grey, color, 0.82);
    float shadow = 1.0 - smoothstep(0.0, 0.45, l);
    float highlight = smoothstep(0.45, 1.0, l);
    color = mix(color, color * ShadowTint.rgb * 1.35 + ShadowTint.rgb * 0.025, shadow * 0.55);
    color = mix(color, color * HighlightTint.rgb, highlight * 0.45);

    // Sanfte S-Kurve: tiefere Schwärzen, ohne dass etwas absäuft.
    color = clamp(color, 0.0, 1.0);
    color = color * color * (3.0 - 2.0 * color) * 0.35 + color * 0.65;

    // Aschedunst: in der Ferne verschwimmt alles in grünlichem Grau. Der Himmel bekommt nur einen Hauch davon.
    float haze = smoothstep(HazeStart, HazeEnd, dist) * (rawDepth >= 1.0 ? 0.35 : 0.75);
    float drift = smoothNoise(texCoord * vec2(3.0, 2.0) + vec2(seconds * 0.03, seconds * 0.012));
    color = mix(color, HazeColor.rgb * (0.85 + drift * 0.3), haze);

    // Vignette: Die Ränder sinken ins Dunkel, als schaue man durch einen Höhlengang.
    vec2 centered = texCoord - 0.5;
    centered.x *= OutSize.x / OutSize.y;
    float edge = smoothstep(0.35, 1.05, length(centered));
    color *= 1.0 - edge * Vignette;

    // Feines Aschekorn, zeitlich verschmiert, damit es eher wie Staub als wie Rauschen wirkt.
    float grain = hash(texCoord * OutSize + fract(seconds * 7.0) * 61.0) - 0.5;
    color += grain * 0.012;

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
