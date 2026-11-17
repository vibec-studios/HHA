#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// HHA-Fest (Überarbeitung 3, P01): schattierte feste Flächen mit Tiefe. Belegung siehe Kopf von FxDraw.kt:
//   Color = Grundfarbe (a = Hitze für EMBER), UV0 = Mesh-UV, UV1 = (FxSolidMode, Seed), UV2 = Lichtwert wie Vanilla.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vColor;
out vec4 vLight;
out vec2 vUV;
out vec3 vNormal;
flat out ivec2 vMode;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vColor = Color;
    vLight = texelFetch(Sampler2, UV2 / 16, 0);
    vUV = UV0;
    vNormal = Normal;
    vMode = UV1;
}
