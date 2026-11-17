#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// HHA-Tunnelshader (PLAN 4.1, Paket P18). Die Röhre ist ein fest vorgebautes Mesh (`TunnelRenderer`); die Lage kommt
// schon in Kameraachsen an (die Kamera rechnet `TunnelCamera` auf der CPU ein, ModelViewMat bleibt die GUI-Einheit).
//
// Belegung je Ecke (VertexFormats.POSITION_TEXTURE_COLOR_LIGHT):
//   UV0.x = Umlauf um die Achse (0..1, plus Drehung)   UV0.y = Lage entlang der Röhre plus Fahrt (mod 256)
//   Color = Wegfarbe (rgb), Lichtgipfel (a)             UV2   = Tempo · 1000, exitOpen · 1000
// Bei TUNNEL_GLOW (Lichtringe, Schleier): UV0 = Fläche −1..1, Color = Farbe/Stärke, UV2.x = Form, UV2.y = Lauf · 1000.
in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;

out vec2 texCoord0;
out vec4 vertexColor;
out float viewDepth;
out float vSpeed;
out float vExit;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    texCoord0 = UV0;
    vertexColor = Color;
    viewDepth = -view.z;
    vSpeed = float(UV2.x) / 1000.0;
    vExit = float(UV2.y) / 1000.0;
}
