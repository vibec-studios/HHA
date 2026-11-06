#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

// HHA-Effektshader. Drei Varianten über Pipeline-Defines:
//   VFX_ENERGY – fließendes Plasma; UV.x = entlang der Form, UV.y = quer (0..1)
//   VFX_FLOW   – flächiges Wabern (Styx); UV sind freie Flächenkoordinaten
//   sonst      – normales Sprite (Glühen, Federn, Runen, Wolken)
// VFX_NO_FOG nimmt große Fernobjekte wie Wolken aus dem Nebel heraus.
uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    // GameTime läuft 0..1 über 24000 Ticks = 1200 s. Alle Scroll-Faktoren sind
    // so gewählt, dass t * Faktor beim Umbruch ganzzahlig bleibt (nahtlos).
    float t = GameTime * 1200.0;

#ifdef VFX_ENERGY
    vec2 uv = texCoord0;
    float a = texture(Sampler0, vec2(uv.x * 0.31 - t * 0.9, uv.y * 0.47 + t * 0.15)).r;
    float b = texture(Sampler0, vec2(uv.x * 0.73 - t * 1.75, uv.y * 0.83 - t * 0.35)).g;
    float c = texture(Sampler0, vec2(uv.x * 1.57 - t * 3.1, uv.y * 1.31)).b;
    float noise = a * 0.55 + b * 0.35 + c * 0.25;
    float across = 1.0 - abs(uv.y * 2.0 - 1.0);
    float body = smoothstep(0.0, 0.55, across);
    float core = smoothstep(0.45, 1.0, across);
    float energy = body * (0.15 + noise * 1.1) + core * 0.35;
    vec3 hot = mix(vertexColor.rgb, vec3(1.0), clamp(energy * energy * energy * 0.55, 0.0, 1.0));
    fragColor = vec4(hot, clamp(energy, 0.0, 1.0) * vertexColor.a);
#elif defined(VFX_FLOW)
    vec2 uv = texCoord0;
    float a = texture(Sampler0, uv * 0.53 + vec2(t * 0.05, t * 0.0333333)).r;
    float b = texture(Sampler0, uv * 1.17 + vec2(a * 0.35 - t * 0.0833333, t * 0.0666666)).g;
    float c = texture(Sampler0, uv * 2.31 + vec2(b * 0.25, a * 0.25 - t * 0.1)).b;
    float cloud = clamp(a * 0.55 + b * 0.5 + c * 0.2, 0.0, 1.0);
    vec3 tint = mix(vertexColor.rgb * 0.55, vertexColor.rgb * 1.2, smoothstep(0.3, 0.8, cloud));
    fragColor = vec4(tint, vertexColor.a * (0.55 + cloud * 0.6));
#else
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a <= 0.004) {
        discard;
    }
    fragColor = color;
#endif

    // Direkt vor der Kamera ausblenden, damit eigene Effekte nie das Bild zukleistern.
    float nearFade = smoothstep(0.35, 2.2, sphericalVertexDistance);
#ifdef VFX_NO_FOG
    fragColor.a *= ColorModulator.a * nearFade;
#else
    float fog = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
    fragColor.a *= ColorModulator.a * (1.0 - fog) * nearFade;
#endif
    fragColor.rgb *= ColorModulator.rgb;

#ifdef VFX_GLOW
    // Halb additiv: leuchtet nachts wie Licht und behält am hellen Tag trotzdem seine Farbe.
    fragColor.rgb *= fragColor.a;
    fragColor.a *= VFX_GLOW;
#endif
}
