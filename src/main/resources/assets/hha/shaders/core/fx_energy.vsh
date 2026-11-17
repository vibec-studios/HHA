#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// HHA-Energie (Überarbeitung 3, P01). Vertexbelegung siehe Kopf von FxDraw.kt:
//   UV0 = Flächenkoordinate, UV1 = (Modus, Seed), UV2 = (Innenradius, Fortschritt) je × 32767, Normal = Flächennormale.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vColor;
out vec2 vUV;
out vec2 vParam;
out vec3 vNormal;
out vec3 vPos;
flat out ivec2 vMode;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vColor = Color;
    vUV = UV0;
    vParam = vec2(UV2) / 32767.0;
    vNormal = Normal;
    vPos = Position;
    vMode = UV1;
}
