#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// HHA-Bildschirm-Pass (Überarbeitung 3, P02): ein Vollbild-Dreieck aus gl_VertexID (Vorbild Void void_lens.vsh).
// Die Matrizen gehen flach an den Fragment-Shader, damit er Sichtstrahlen und Weltpunkte rekonstruieren kann.

out vec2 texCoord;
flat out mat4 viewProj;
flat out mat4 invViewProj;

void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
    texCoord = uv;
    viewProj = ProjMat * ModelViewMat;
    invViewProj = inverse(viewProj);
}
