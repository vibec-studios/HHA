#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <hha:hha_noise.glsl>

// HHA-Fest (Überarbeitung 3, P01): feste, Tiefe schreibende Formen (Fels, Glut, glatte Splitter). Licht kommt weich
// aus der Normale (keine Flächenstufen wie bei Blöcken) mal Lichtwert der Welt. Rauschen periodisch in UV, damit die
// Naht von Kugel und Torus nicht zu sehen ist. Modi (FxSolidMode): 0 = glatt, 1 = Glutadern, 2 = Stein.

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vColor;
in vec4 vLight;
in vec2 vUV;
in vec3 vNormal;
flat in ivec2 vMode;

out vec4 fragColor;

const int SOLID_PLAIN = 0;
const int SOLID_EMBER = 1;
const int SOLID_STONE = 2;

void main() {
    float t = GameTime * 1200.0;
    float seed = float(vMode.y);
    vec2 so = vec2(hha_hash12(vec2(seed, 2.9)), hha_hash12(vec2(7.1, seed))) * 61.0;
    vec3 N = normalize(vNormal);

    // Weiches Licht: Hauptlicht schräg von oben, Himmelslicht von oben, etwas Gegenlicht – Bereich etwa 0,55..1.
    vec3 L = normalize(vec3(0.35, 1.0, 0.45));
    float key = max(dot(N, L), 0.0);
    float sky = N.y * 0.5 + 0.5;
    float back = max(dot(N, -L), 0.0);
    float shade = min(0.55 + 0.38 * key + 0.15 * sky + 0.05 * back, 1.0);

    vec3 albedo = vColor.rgb;
    vec2 period = vec2(16.0, 8.0);
    if (vMode.x == SOLID_STONE || vMode.x == SOLID_EMBER) {
        float m = hha_pfbm(vUV * period + so, period);
        albedo *= 0.8 + 0.34 * m;
    }
    vec3 color = albedo * shade * vLight.rgb;

    if (vMode.x == SOLID_EMBER) {
        // Glutadern: Grat-Rauschen in UV, Breite und Leuchten mit der Hitze (Vertex-Alpha). Die Adern fließen sehr
        // langsam (stetig), leuchten unabhängig vom Licht der Welt.
        float heat = clamp(vColor.a, 0.0, 1.0);
        float n = hha_pfbm(vUV * period * 0.5 + so.yx + vec2(0.0, t * 0.02), period * 0.5);
        float ridge = abs(n * 2.0 - 1.0);
        float aa = max(fwidth(ridge), 1e-4);
        float wdt = 0.03 + 0.07 * heat;
        float vein = 1.0 - smoothstep(wdt * 0.35 - aa, wdt + aa, ridge);
        float halo = 1.0 - smoothstep(wdt, wdt * 3.2, ridge);
        vec3 ember = mix(vec3(0.95, 0.32, 0.06), vec3(1.0, 0.85, 0.55), vein * vein);
        color = mix(color * (1.0 - 0.3 * halo * heat), ember, vein * heat) + ember * halo * heat * 0.12;
    }

    fragColor = apply_fog(vec4(color, 1.0), sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
