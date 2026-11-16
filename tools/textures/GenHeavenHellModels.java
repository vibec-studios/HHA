import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 3D-Handmodelle + Texturen der vier Set-Waffen (Überarbeitung 2, PLAN 9.3), im Stil der Schwester-Mods:
 * Gerüst (Elemente, Atlas, JSON, Display-Rechnung, Icon-Render, Hand-Vorschau) unverändert aus GenVoidModels.java
 * (HHA Void) übernommen, die Waffen sind neu.
 *
 *   Heaven's Sword: Flügelschwert. Gerade, helle Klinge aus Wolkenstahl mit schmaler, warmweiß leuchtender Rinne,
 *                   die Parierstange ist ein Schwingenpaar (Goldknochen, vier gestaffelte weiße Federn je Seite),
 *                   in der Mitte ein leuchtender Stein, Goldgriff mit Wicklung, Knauf mit kleinen Goldschwingen.
 *   Hells Sword:    gezackte Schwarzstahlklinge (Sägezähne versetzt auf beiden Schneiden), durch die Mitte läuft
 *                   eine türkise Glutader (Seelenfeuer, pulsiert zur Spitze), gehörnte Parierstange mit Seelenauge.
 *   Heavens Mace:   Schmiedehammer des Brontes. Heller Stahlkopf mit zwei Goldbändern und Gold-Blitz auf den
 *                   Flanken; in der Schlagfläche (-x, in der Hand vorn/unten) eine leuchtende Blitz-Kerbe.
 *   Hells Mace:     Kettenmorgenstern. Kurzer Griff, drei Kettenglieder, schwarzer Dornkopf (13 Dornen); aus den
 *                   Fugen des Kopfs scheint Seelenfeuer (innerer Leuchtkern, nur in den Kanten sichtbar).
 *
 * Die Modelle stehen AUFRECHT (Längsachse = y, Mitte x=8/z=8); -x zeigt in der Hand nach vorn/unten (Schlagfläche).
 * Display-Werte wie bei Void aus Vanilla `handheld` bzw. `handheld_mace` abgeleitet (Griffpunkt an der
 * Vanilla-Handposition, siehe held/pose); eingestellt mit der Hand-Vorschau (--hand), Stellschrauben unten.
 *
 * Texturen werden aus der 3D-Position jedes Texels gemalt (2 Texel pro Modell-Einheit), 8 Frames. Icons 32x32:
 * orthografischer Render des Modells, dominante Farbe, 1-px-Umriss. Für die Verzauberungs-Variante der Maces
 * (`*_ench`) werden Atlas und Icon mit „aufgeladenen" Leuchtteilen (heller) erzeugt; das Modell ist ein Kind des
 * normalen 3D-Modells mit anderer Textur.
 *
 * Schreibt (unter src/main/resources/assets/hha):
 *   models/item/<waffe>_3d.json, textures/item/<waffe>_3d.png(.mcmeta), textures/item/<waffe>.png(.mcmeta) (Icon 32x32)
 *   für die Maces zusätzlich <mace>_ench_3d.json, textures/item/<mace>_ench_3d.png, <mace>_ench.png (+mcmeta),
 *   für den Heavens Mace models/item/heavens_mace_thrown.json (geworfen: 3D-Modell, eigene ground-Werte)
 * Die items/*.json und die 2D-Modelle (models/item/<waffe>.json) sind von Hand geschrieben (siehe README).
 *
 * Aufruf (im Projektordner):
 *   java tools/textures/GenHeavenHellModels.java .                   alles schreiben
 *   java tools/textures/GenHeavenHellModels.java . --preview <dir>   nur Vorschau-PNGs (<waffe>_sheet.png, _atlas.png)
 *   java tools/textures/GenHeavenHellModels.java . --hand <dir>      Hand-Vorschau: Ego-Sicht + Spielerpuppe
 *     (Varianten: java -Dhells_sword.fp="p lean,pitch,roll,hx,hy,hz,size" ... ; siehe Kommentar „Hand-Vorschau")
 */
public class GenHeavenHellModels {

    static final int PX = 2;          // Texel pro Modell-Einheit
    static final int FRAMES = 8;      // Animations-Frames (Atlas + Icon)
    static int ATLAS = 128;           // Atlas-Kantenlänge
    static final int ICON = 32, SS = 8;

    // ------------------------------------------------------------------ Stellschrauben: Haltung in der Hand
    /**
     * Größe in der Hand, 3rd Person / Ego-Sicht (Vanilla: Schwert 0.85 / 0.68, Mace 1.0 / 0.9). Ausgangspunkt sind
     * die im Spiel geprüften Void-Werte (MACE_NOTES.md, Runde 3/4); die Hämmer sind in 3rd Person bewusst groß.
     */
    static final double SWORD_3RD = 0.95, MACE_3RD = 1.35, MACE_1ST = 0.64;
    /** Der Flanschkolben ist kompakter gebaut als der Morgenstern und wirkt in 3rd Person sonst viel zu groß */
    static final double HEAVEN_MACE_3RD = 0.95;
    /** Griffpunkte (aufrechtes Modell): wo Vanilla die Hand hat / wo unsere Hand sitzen soll (Mitte der Wicklung) */
    static final double[] SWORD_GRIP_VANILLA = {8, 1, 8}, SWORD_GRIP = {8, -2.25, 8};
    static final double[] MACE_GRIP_VANILLA = {8, 1.5, 8}, MACE_GRIP = {8, -2.75, 8};
    /** 3rd Person fasst die Hand den großen Hammer weiter unten, damit der Knauf nicht im Boden steckt */
    static final double[] MACE_GRIP_3RD = {8, -4, 8};
    /** Display-Werte {rx, ry, rz, tx, ty, tz, Größe}; Herleitung und Bedeutung siehe [held] und [pose] */
    static final double[] SWORD_TP = held(55, new double[]{0, 4, 0.5}, 0.85, SWORD_GRIP_VANILLA, new double[]{0, -90, 10}, SWORD_3RD, SWORD_GRIP);
    static final double[] MACE_TP = held(55, new double[]{0, 4, 1}, 1, MACE_GRIP_VANILLA, new double[]{0, -90, -35}, MACE_3RD, MACE_GRIP_3RD);
    static final double[] HEAVEN_MACE_TP = held(55, new double[]{0, 4, 1}, 1, MACE_GRIP_VANILLA, new double[]{0, -90, -35}, HEAVEN_MACE_3RD, MACE_GRIP_3RD);
    /**
     * Ego-Sicht der Schwerter: Haltung wie Void-Katana (Klinge läuft von rechts unten schräg zur Bildmitte). Das
     * Flügelschwert etwas kleiner und tiefer, stärker zur Flachseite gedreht, damit die Schwingen nur unten rechts
     * ins Bild ragen statt es zu füllen.
     */
    static final double[] HEAVEN_SWORD_FP = pose(6, 52, 50, new double[]{4.6, -4.5, 0.3}, 0.88, SWORD_GRIP);
    static final double[] HELL_SWORD_FP = pose(6, 58, 38, new double[]{4.2, -3.6, 0.5}, 1.1, SWORD_GRIP);
    static final double[] MACE_FP = pose(-3, 28, -8, new double[]{3.5, -1.8, -2}, MACE_1ST, MACE_GRIP);
    /** Geworfener Heavens Mace (FlyingItemEntityRenderer zeichnet im Kontext "ground", der Renderer skaliert 1.6×) */
    static final double THROWN_SCALE = 0.42;

    // ------------------------------------------------------------------ Paletten
    /** Wolkenstahl (wie die Rüstung: #5e6b7a -> #aab6c4 -> #e8eef5 -> #ffffff) */
    static final int[] HST = {0x2E3540, 0x46505D, 0x5E6B7A, 0x84909F, 0xAAB6C4, 0xCAD4DF, 0xE8EEF5, 0xFFFFFF};
    /** Gold der Trims (#b06a16 -> #ffc23c -> #fff4c2) */
    static final int[] GOLD = {0x3F2006, 0x5C300A, 0x864A10, 0xB06A16, 0xD9921F, 0xFFC23C, 0xFFDE85, 0xFFF4C2};
    /** warmweißes Leuchten (Rinne, Blitz-Kerbe, Steine) */
    static final int[] HGLOW = {0xB8741C, 0xE0A23A, 0xFFC860, 0xFFE09A, 0xFFF0CC, 0xFFFAEC, 0xFFFFFF};
    /** Federn: Gold (Vorlage des Nutzers: goldene Schwingen) */
    static final int[] FEATHER = {0x8A5A16, 0xB57A22, 0xDDA03A, 0xF4C35E, 0xFFDF94, 0xFFF4D2};
    /** Lederwicklung (Heaven) */
    static final int[] LEATHER_H = {0x3A2312, 0x56351C, 0x744A28, 0x936238};
    /** Schwarzstahl (Netherit mit kühlem Stich) */
    static final int[] BST = {0x0C0B0F, 0x151419, 0x1F1D24, 0x2A2730, 0x37333E, 0x48434F, 0x5D5766, 0x77707F};
    /** Seelenfeuer (#0b7690 -> #3cd6ef -> #d8fdff) */
    static final int[] SOUL = {0x032A36, 0x064C5E, 0x0B7690, 0x1EA8C8, 0x3CD6EF, 0x8EEEFB, 0xD8FDFF};
    /** Lederwicklung (Hell) */
    static final int[] LEATHER_D = {0x120E10, 0x1D1619, 0x2A2025, 0x392C33};
    /** Umriss der Icons (je Set) */
    static int OUTLINE = 0x0A0C12;
    static final int OUTLINE_HEAVEN = 0x232934, OUTLINE_HELL = 0x07080B;
    /** Material-Rückgabe für "durchsichtig" (Cutout) */
    static final int CLEAR = -1;
    /** Aufhellung der Leuchtteile in der Verzauberungs-Variante (0 = gleich, 1 = weiß) */
    static final double CHARGE = 0.32;

    // ------------------------------------------------------------------ Modell

    interface Mat { int col(El e, double[] p, double[] w, int face, int fr); }

    static final String[] FACES = {"north", "south", "west", "east", "up", "down"};
    static final int N = 0, S = 1, W = 2, E = 3, U = 4, D = 5;

    static class El {
        String name; double[] from, to; double[] rot; /* {x,y,z} Grad um org, immer nur eine Achse */
        double[] org = {8, 8, 8};
        Mat mat; boolean emissive, fx; int[][] uv = new int[6][]; boolean[] skip = new boolean[6];
        El(String name, double x1, double y1, double z1, double x2, double y2, double z2, Mat mat) {
            this.name = name; from = new double[]{x1, y1, z1}; to = new double[]{x2, y2, z2}; this.mat = mat;
        }
        El glow() { emissive = true; return this; }
        /** Effekt-Element (Flammen): zählt nicht zur Silhouette, bekommt im Icon keinen Umriss */
        El fx() { fx = true; return this; }
        El rotY(double deg) { rot = new double[]{0, deg, 0}; return this; }
        El rotZ(double deg) { rot = new double[]{0, 0, deg}; return this; }
        El rotX(double deg) { rot = new double[]{deg, 0, 0}; return this; }
        El at(double x, double y, double z) { org = new double[]{x, y, z}; return this; }
        El noFaces(int... f) { for (int i : f) skip[i] = true; return this; }
        /** Ecken einer Fläche: {TL, TR, BL} in lokalen Koordinaten (Minecrafts Standard-UV-Ausrichtung) */
        double[][] corners(int f) {
            double x1 = from[0], y1 = from[1], z1 = from[2], x2 = to[0], y2 = to[1], z2 = to[2];
            switch (f) {
                case S: return new double[][]{{x1, y2, z2}, {x2, y2, z2}, {x1, y1, z2}};
                case N: return new double[][]{{x2, y2, z1}, {x1, y2, z1}, {x2, y1, z1}};
                case W: return new double[][]{{x1, y2, z1}, {x1, y2, z2}, {x1, y1, z1}};
                case E: return new double[][]{{x2, y2, z2}, {x2, y2, z1}, {x2, y1, z2}};
                case U: return new double[][]{{x1, y2, z1}, {x2, y2, z1}, {x1, y2, z2}};
                default: return new double[][]{{x1, y1, z2}, {x2, y1, z2}, {x1, y1, z1}};
            }
        }
        double[] world(double[] p) {
            if (rot == null) return p;
            double[] v = {p[0] - org[0], p[1] - org[1], p[2] - org[2]};
            v = mul(mul(mul(Rz(rot[2]), Ry(rot[1])), Rx(rot[0])), v);
            return new double[]{v[0] + org[0], v[1] + org[1], v[2] + org[2]};
        }
    }

    // ------------------------------------------------------------------ Mathe
    static double[][] Rx(double d) { double a = Math.toRadians(d), c = Math.cos(a), s = Math.sin(a); return new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}}; }
    static double[][] Ry(double d) { double a = Math.toRadians(d), c = Math.cos(a), s = Math.sin(a); return new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}}; }
    static double[][] Rz(double d) { double a = Math.toRadians(d), c = Math.cos(a), s = Math.sin(a); return new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}}; }
    static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j];
        return r;
    }
    static double[] mul(double[][] a, double[] v) {
        return new double[]{a[0][0] * v[0] + a[0][1] * v[1] + a[0][2] * v[2], a[1][0] * v[0] + a[1][1] * v[1] + a[1][2] * v[2], a[2][0] * v[0] + a[2][1] * v[1] + a[2][2] * v[2]};
    }
    static double hash(int a, int b, int c) {
        int h = a * 374761393 + b * 668265263 + c * 2147483647 + 0x9E3779B9;
        h = (h ^ (h >>> 13)) * 1274126177; h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }
    static int q(double v) { return (int) Math.floor(v * PX + 1e-6); }
    static int pick(int[] pal, double idx) { return pal[Math.max(0, Math.min(pal.length - 1, (int) Math.floor(idx)))]; }

    /** weiches, in y kachelbares Rauschen (Periode py Zellen) */
    static double vnoise(double x, double y, int py, int seed) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
        double a = hash(x0, Math.floorMod(y0, py), seed), b = hash(x0 + 1, Math.floorMod(y0, py), seed);
        double c = hash(x0, Math.floorMod(y0 + 1, py), seed), d = hash(x0 + 1, Math.floorMod(y0 + 1, py), seed);
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
    }
    /** weiches 1D-Rauschen mit Periode [period] */
    static double noise1(double x, int period, int seed) {
        int x0 = (int) Math.floor(x);
        double f = x - x0; f = f * f * (3 - 2 * f);
        double a = hash(Math.floorMod(x0, period), seed, 3), b = hash(Math.floorMod(x0 + 1, period), seed, 3);
        return a + (b - a) * f;
    }
    /**

    // ------------------------------------------------------------------ Hilfen für Materialien
    /** Texel auf der Fläche: {i, j, Breite, Höhe}; j zählt bei Seitenflächen von oben */
    static int[] texel(El e, double[] p, int f) {
        int a = (f == W || f == E) ? 2 : 0, b = (f == U || f == D) ? 2 : 1;
        int wd = Math.max(1, (int) Math.round((e.to[a] - e.from[a]) * PX)), ht = Math.max(1, (int) Math.round((e.to[b] - e.from[b]) * PX));
        int i = Math.min(wd - 1, (int) ((p[a] - e.from[a]) * PX)), j = Math.min(ht - 1, (int) ((b == 1 ? e.to[1] - p[1] : p[2] - e.from[2]) * PX));
        return new int[]{i, j, wd, ht};
    }
    /** Abstand des Texels zum Flächenrand (0 = Randtexel) */
    static int rim(int[] t) { return Math.min(Math.min(t[0], t[2] - 1 - t[0]), Math.min(t[1], t[3] - 1 - t[1])); }
    static double sq(double v) { return v * v; }
    /** Puls über die 8 Frames, Phase in Bildern */
    static double pulse(int fr, double phase) { return 0.5 + 0.5 * Math.sin(2 * Math.PI * (fr - phase) / FRAMES); }
    /** Welle, die entlang y (Wellenlänge len Einheiten) nach oben läuft */
    static double wave(int fr, double y, double len) { return 0.5 + 0.5 * Math.sin(2 * Math.PI * ((double) fr / FRAMES - y / len)); }
    static int mix(int a, int b, double k) {
        int r = (int) Math.round(((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k);
        int bl = (int) Math.round((a & 255) * (1 - k) + (b & 255) * k);
        return (r << 16) | (g << 8) | bl;
    }

    /**
     * Metall mit Pixel-Fase (wie Void): Oberkante hell, Unterkante dunkel, Seitenränder etwas heller, leichtes Korn.
     * base = mittlere Stufe der Palette, grain = Stärke des Korns.
     */
    static Mat metal(int[] pal, double base, double grain) {
        return (e, p, w, f, fr) -> {
            int[] t = texel(e, p, f);
            double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 31);
            double idx = base + (n - 0.5) * grain + (f == U ? 1.0 : f == D ? -1.2 : f == W ? -0.3 : 0);
            if (f != U && f != D && t[3] > 2) { if (t[1] == 0) idx += 1.3; if (t[1] == t[3] - 1) idx -= 1.1; }
            if (f != U && f != D && t[2] > 3 && (t[0] == 0 || t[0] == t[2] - 1)) idx += 0.5;
            if ((f == U || f == D) && t[2] > 2 && t[3] > 2 && rim(t) == 0) idx += 0.8;
            return pick(pal, idx);
        };
    }

    /** Abstand eines Punkts (u, v) zur Strecke a-b */
    static double segDist(double u, double v, double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1];
        double k = Math.max(0, Math.min(1, ((u - a[0]) * dx + (v - a[1]) * dy) / (dx * dx + dy * dy)));
        return Math.hypot(u - a[0] - k * dx, v - a[1] - k * dy);
    }
    /** Blitz als Zickzack-Linie in Flächenkoordinaten (u nach rechts, v nach unten, je 0..1) */
    static final double[][] BOLT = {{0.70, 0.02}, {0.36, 0.50}, {0.64, 0.50}, {0.30, 0.98}};
    /** Abstand (in Texeln) eines Flächen-Texels zum Blitz; t wie texel() */
    static double boltDist(int[] t, boolean mirror) {
        double u = (t[0] + 0.5) / t[2], v = (t[1] + 0.5) / t[3];
        if (mirror) u = 1 - u;
        double d = 9;
        for (int i = 0; i + 1 < BOLT.length; i++) d = Math.min(d, segDist(u * t[2], v * t[3], new double[]{BOLT[i][0] * t[2], BOLT[i][1] * t[3]}, new double[]{BOLT[i + 1][0] * t[2], BOLT[i + 1][1] * t[3]}));
        return d;
    }

    // ------------------------------------------------------------------ Materialien Heaven
    static final Mat STEEL_H = metal(HST, 4.2, 1.2);
    static final Mat STEEL_H_DARK = metal(HST, 3.0, 1.2);
    static final Mat GOLD_M = metal(GOLD, 4.3, 1.3);

    /** Klinge des Flügelschwerts: gleißende Schneidenfase, Hohlschliff zur Mitte dunkler, ein Glanzlicht wandert hoch */
    static final Mat BLADE_H = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 7);
        if (f == W || f == E) return pick(HST, 6.0 + n * 1.6);
        if (f == U || f == D) return pick(HST, 4.6 + n);
        int edge = Math.min(t[0], t[2] - 1 - t[0]);
        double idx = edge == 0 ? 6.4 : edge == 1 ? 4.7 : 3.4;
        idx += (n - 0.5) * 0.9;
        int s = Math.floorMod(q(p[1]) - fr * 4, 32);
        if (s < 2 && edge <= 1) idx += 0.9;                          // wanderndes Glanzlicht
        return pick(HST, idx);
    };
    /** schmale leuchtende Rinne: warmweiß, eine helle Welle läuft zur Spitze */
    static final Mat FULLER = (e, p, w, f, fr) -> pick(HGLOW, 2.4 + wave(fr, p[1], 10) * 3.0 + hash(q(p[0]), q(p[1]), f) * 0.7);
    /** leuchtende Steine (Heaven) */
    static final Mat GEM_H = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        double pu = pulse(fr, 0);
        if ((f == N || f == S) && rim(t) > 0) return HGLOW[6];
        return pick(HGLOW, 2.8 + pu * 2.0 + hash(t[0], t[1], f) * 0.8);
    };
    /** Goldgriff: Wicklung aus Golddraht, schräg umlaufend */
    static final Mat GRIP_GOLD = (e, p, w, f, fr) -> {
        if (f == U || f == D) return GOLD[3];
        int off = f == S ? 0 : f == E ? 1 : f == N ? 2 : 3;
        int s = Math.floorMod(q(p[1]) + off, 3);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f);
        return s == 0 ? GOLD[2] : s == 1 ? (n < 0.5 ? GOLD[5] : GOLD[6]) : GOLD[4];
    };
    /** Lederwicklung mit Goldfaden (Hammer) */
    static final Mat GRIP_LEATHER_H = (e, p, w, f, fr) -> {
        if (f == U || f == D) return LEATHER_H[0];
        int off = f == S ? 0 : f == E ? 1 : f == N ? 2 : 3;
        int s = Math.floorMod(q(p[1]) + off, 4);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f);
        if (s == 0) return n < 0.4 ? GOLD[4] : GOLD[5];
        return s == 1 ? LEATHER_H[1] : n < 0.5 ? LEATHER_H[2] : LEATHER_H[3];
    };
    /**
     * Feder: Kiel (Mittellinie) leicht dunkler, Fahne hell, zur Spitze hin heller; die äußere Unterkante ist
     * schräg gekerbt (durchsichtig), damit die Schwingen nicht wie Bretter aussehen.
     */
    static final Mat FEATHER_M = (e, p, w, f, fr) -> {
        boolean right = (e.from[0] + e.to[0]) / 2 > 8;
        double u = (p[0] - e.from[0]) / Math.max(1e-6, e.to[0] - e.from[0]);
        double out = right ? u : 1 - u;
        int[] t = texel(e, p, f);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 5);
        if (f == U) return pick(FEATHER, 4.2 + n);
        if (f == D) return pick(FEATHER, 1.6 + n);
        if (f == W || f == E) return pick(FEATHER, 2.8 + n);
        int len = t[2];
        int fromOut = right ? len - 1 - t[0] : t[0];
        if (fromOut == 0 && t[1] == t[3] - 1) return CLEAR;                    // Kerbe an der Spitze
        if (t[1] == 0 && out < 0.35) return pick(GOLD, 4.6 + n);               // Goldsaum am Ansatz
        double idx = 2.9 + out * 1.8 + (t[1] == 0 ? 0.6 : -0.5) + (n - 0.5) * 0.8;
        if (fromOut % 3 == 1 && t[1] == t[3] - 1) idx -= 0.9;                   // einzelne Fahnenstrahlen
        return pick(FEATHER, idx);
    };
    /** Hammerkopf: heller Stahl mit Fase; auf den Flanken (N/S) ein eingelegter Gold-Blitz */
    static final Mat HEAD_H = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        if (f == N || f == S) {
            double d = boltDist(t, f == N);
            if (d < 0.9) return pick(GOLD, 5.6 - d * 1.6 + (t[1] < t[3] / 2 ? 0.6 : 0));
            if (d < 1.6) return HST[2];                                     // Fuge um die Einlage
        }
        return STEEL_H.col(e, p, w, f, fr);
    };
    /** Schlagflächen: glatter, heller Stahl; auf der Blitz-Seite (-x) eine dunkle Kerbe um die Leuchtlinie */
    static final Mat STRIKE_H = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 11);
        boolean face = (f == W && e.to[0] < 8) || (f == E && e.from[0] > 8);
        if (!face) return pick(HST, 4.6 + (n - 0.5) * 1.0 + (f == U ? 1 : f == D ? -1 : 0));
        if (f == W && boltDist(t, false) < 2.2) return HST[1];
        int r = rim(t);
        if (r == 0) return t[1] == 0 ? HST[7] : t[1] == t[3] - 1 ? HST[3] : HST[6];
        return n < 0.07 ? HST[3] : n < 0.55 ? HST[5] : HST[6];
    };
    /** die Blitz-Kerbe selbst: leuchtende Linie (Cutout), ein Lichtschlag läuft von oben nach unten */
    static final Mat NOTCH = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        double d = boltDist(t, false);
        if (d >= 1.25) return CLEAR;
        double v = (t[1] + 0.5) / t[3];
        double strike = 0.5 + 0.5 * Math.cos(2 * Math.PI * ((double) fr / FRAMES - v));
        return pick(HGLOW, 3.4 - d * 1.6 + strike * 2.6);
    };

    /**
     * Goldklinge (Vorlage des Nutzers, 16.11.): gleissend helles Gold, Schneidenfase fast weiss, zur Mitte hin warm,
     * in der Mittellinie eine weisse Rinne; ein Glanzlicht wandert zur Spitze. Das Element leuchtet (emissive).
     */
    /** Goldklinge: warmes Gold, zur Schneide und Mittellinie hin fast weiss */
    static final int[] BLADE_GOLD = {0xB87A22, 0xD9982E, 0xF0B845, 0xFFD268, 0xFFE297, 0xFFF0C8, 0xFFFBEF};
    static final Mat BLADE_HG = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 7);
        if (f == W || f == E) return pick(BLADE_GOLD, 4.6 + n * 1.2);
        if (f == U || f == D) return pick(BLADE_GOLD, 3.6 + n);
        int edge = Math.min(t[0], t[2] - 1 - t[0]);
        int mid = Math.abs(t[0] - (t[2] - 1) / 2);
        double idx = edge == 0 ? 5.6 : edge == 1 ? 3.0 : mid == 0 ? 5.2 : 2.2;
        idx += (n - 0.5) * 0.6 + wave(fr, p[1], 14) * 0.9;
        return pick(BLADE_GOLD, idx);
    };
    /** Weissstahl der Flanschen: helle Flaeche, Goldsaum an allen Raendern */
    static final Mat FLANGE_H = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 9);
        double r = Math.hypot(w[0] - 8, w[2] - 8);
        if (r > 4.15 || (f == U && r > 2.4)) return pick(GOLD, 5.0 + n * 1.6);
        if (f == D) return pick(HST, 3.0 + n);
        return pick(HST, 5.2 + (n - 0.5) * 1.0 + (f == U ? 1.0 : 0) + (t[1] == 0 ? 0.6 : 0));
    };

    // ------------------------------------------------------------------ Materialien Hell
    static final Mat STEEL_D = metal(BST, 3.3, 1.4);
    /** Schwarzstahl der Klinge: helle Fase an den Schneiden, dunkle Fläche, hier und da ein türkiser Funke */
    static final Mat BLADE_D = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 7);
        if (f == W || f == E) return pick(BST, 5.6 + n * 2);
        if (f == U || f == D) return pick(BST, 4.4 + n);
        int edge = Math.min(t[0], t[2] - 1 - t[0]);
        if (edge >= 1 && hash(q(p[0]) + 3, q(p[1]), f) < 0.03) return SOUL[1];
        double idx = edge == 0 ? 5.9 : edge == 1 ? 3.0 : 1.4;
        return pick(BST, idx + (n - 0.5) * 1.1 + (t[1] == 0 && t[3] > 1 ? 0.8 : 0));
    };
    /** Mittellinie der Glutader bei Höhe y */
    static double veinX(double y) { return 8 + 0.42 * Math.sin(y * 0.85) + 0.22 * Math.sin(y * 2.1 + 1.3); }
    /** Glutader: schmale türkise Linie (Cutout) mit kurzen Seitenästen, Puls läuft zur Spitze */
    static final Mat VEIN = (e, p, w, f, fr) -> {
        double y = p[1], dx = Math.abs(p[0] - veinX(y));
        double fade = Math.min(1, Math.min((y - e.from[1]) / 1.5, (e.to[1] - y) / 1.5));
        boolean branch = hash(q(y), 7, 3) < 0.18 && dx < 0.8 && Math.signum(p[0] - veinX(y)) == (hash(q(y), 9, 4) < 0.5 ? 1 : -1);
        if (dx >= 0.32 && !branch) return CLEAR;
        if (fade <= 0) return CLEAR;
        double wv = wave(fr, y, 9);
        return pick(SOUL, (branch && dx >= 0.32 ? 2.6 : 3.4) + wv * 2.6 * fade + hash(q(p[0]), q(y), 2) * 0.5);
    };
    static final Mat GEM_D = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        double pu = pulse(fr, 0);
        if ((f == N || f == S) && rim(t) > 0) return SOUL[6];
        return pick(SOUL, 2.8 + pu * 2.0 + hash(t[0], t[1], f) * 0.8);
    };
    /** Seelenauge der Parierstange: dunkler Rand, leuchtende Iris mit Schlitzpupille */
    static final Mat SOUL_EYE = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        double pu = pulse(fr, 0);
        if (f != N && f != S) return pick(SOUL, 2.4 + pu);
        if (rim(t) == 0) return SOUL[2];
        if (t[0] == t[2] / 2 && t[3] > 2) return 0x02141A;
        return pick(SOUL, 3.6 + pu * 2.2);
    };
    /** dunkle Lederwicklung mit türkisem Faden */
    static final Mat GRIP_D = (e, p, w, f, fr) -> {
        if (f == U || f == D) return LEATHER_D[0];
        int off = f == S ? 0 : f == E ? 1 : f == N ? 2 : 3;
        int s = Math.floorMod(q(p[1]) + off, 4);
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f);
        if (s == 0) return n < 0.5 ? SOUL[2] : SOUL[3];
        return s == 1 ? LEATHER_D[1] : n < 0.5 ? LEATHER_D[2] : LEATHER_D[3];
    };
    /** Knochenstahl: dunkles, leicht warmes Grau (Totenschaedel, Hoerner) */
    static final int[] BONE_D = {0x16141A, 0x221F26, 0x302B33, 0x403A43, 0x544C56, 0x6A616B, 0x857A84};
    static final Mat BONE_M = metal(BONE_D, 3.6, 1.3);
    /**
     * Totenschaedel der Parierstange (Vorlage des Nutzers): Schaedeldach zum Griff, offener Kiefer zur Klinge. Auf den
     * Flachseiten zwei tiefe Augenhoehlen, Nasenloch und eine Zahnreihe; der Rest ist Knochenstahl.
     */
    static final Mat SKULL_M = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        if (f != N && f != S) return BONE_M.col(e, p, w, f, fr);
        double u = (t[0] + 0.5) / t[2], v = (t[1] + 0.5) / t[3];
        boolean eye = v > 0.42 && v < 0.72 && (Math.abs(u - 0.27) < 0.16 || Math.abs(u - 0.73) < 0.16);
        if (eye) return 0x050407;
        if (v > 0.30 && v <= 0.42 && Math.abs(u - 0.5) < 0.08) return 0x0B090D;
        if (v < 0.22) return (t[0] % 2 == 0) ? BONE_D[6] : BONE_D[1];
        if (v < 0.30) return BONE_D[1];
        return BONE_M.col(e, p, w, f, fr);
    };
    /** Seelenglut in den Augenhoehlen (leuchtende Auflage, sonst durchsichtig) */
    static final Mat SKULL_EYES = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        if (f != N && f != S) return CLEAR;
        double u = (t[0] + 0.5) / t[2], v = (t[1] + 0.5) / t[3];
        boolean eye = v > 0.48 && v < 0.66 && (Math.abs(u - 0.27) < 0.09 || Math.abs(u - 0.73) < 0.09);
        return eye ? pick(SOUL, 4.0 + pulse(fr, 0) * 2.4) : CLEAR;
    };
    /** Rinne voller kleiner Schaedel: dunkler Grund, Schaedelreihen in Knochenstahl, versetzt gestapelt */
    static final Mat SKULLS_M = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        if (f != N && f != S) return pick(BST, 1.2);
        int cx = Math.floorMod(t[0] + ((t[1] / 4) % 2) * 2, 4), cy = Math.floorMod(t[1], 4);
        if (rim(t) == 0) return BST[4];
        if (cx == 3 || cy == 3) return BST[0];
        boolean socket = cy == 1 && (cx == 0 || cx == 2);
        if (socket) return hash(t[0], t[1], 5) < 0.25 ? SOUL[2] : 0x040306;
        double n = hash(t[0], t[1], f + 11);
        return pick(BONE_D, 2.4 + (cy == 0 ? 1.0 : 0) + (n - 0.5) * 0.8);
    };

    /** Mittelpunkt des Morgenstern-Kopfs */
    static final double[] BALL = {8, 14.0, 8};
    /** Kopf des Morgensterns: Schwarzstahl; Randtexel nehmen einen türkisen Schimmer aus den Fugen an */
    static final Mat BALL_M = (e, p, w, f, fr) -> {
        int[] t = texel(e, p, f);
        int c = STEEL_D.col(e, p, w, f, fr);
        if (rim(t) == 0) return mix(c, SOUL[2], 0.25 + 0.15 * pulse(fr, 0));
        return c;
    };
    /** Seelenfeuer im Kern (nur in den Fugen sichtbar), pulsiert */
    static final Mat SOUL_CORE = (e, p, w, f, fr) -> pick(SOUL, 3.4 + pulse(fr, 0) * 2.2 + hash(q(p[0]), q(p[1]), q(p[2]) + f) * 0.9);
    /** Dorn: an der Wurzel dunkel, zur Spitze heller Stahl */
    static final Mat SPIKE_D = (e, p, w, f, fr) -> {
        double r = Math.sqrt(sq(w[0] - BALL[0]) + sq(w[1] - BALL[1]) + sq(w[2] - BALL[2]));
        double t = Math.max(0, Math.min(1, (r - 2.6) / 3.0));
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 13);
        return pick(BST, 1.6 + t * 5.4 + (n - 0.5) * 1.0 + (f == U ? 0.7 : f == D ? -0.7 : 0));
    };
    static final Mat CHAIN = (e, p, w, f, fr) -> {
        double n = hash(q(p[0]), q(p[1]), q(p[2]) + f * 3);
        return pick(BST, 3.6 + (n - 0.5) * 1.6 + (f == U ? 1.4 : f == D ? -1.0 : f == W || f == N ? -0.4 : 0.6));
    };

    // ------------------------------------------------------------------ Modelle
    static El box(String name, double x1, double y1, double z1, double x2, double y2, double z2, Mat mat) {
        return new El(name, x1, y1, z1, x2, y2, z2, mat);
    }
    /** Manschette um den Schaft; band = leuchtender Ring in der Mitte */
    static void cuff(List<El> m, double y0, double y1, double half, Mat mat, Mat band) {
        m.add(box("cuff", 8 - half, y0, 8 - half, 8 + half, y1, 8 + half, mat));
        double ym = (y0 + y1) / 2;
        if (band != null) m.add(box("cuff_band", 8 - half - 0.06, ym - 0.25, 8 - half - 0.06, 8 + half + 0.06, ym + 0.25, 8 + half + 0.06, band).glow().noFaces(U, D));
    }

    /**
     * Heaven's Sword (Vorlage des Nutzers, 16.11.): lange, leuchtende Goldklinge, an der Parierstange ein Paar goldener
     * Schwingen, die sich nach außen und zum Griff hin fächern; lange Drahtwicklung, verzierter Knauf mit Stein.
     */
    static List<El> heavensSword() {
        List<El> m = new ArrayList<>();
        // Knauf: Goldknospe mit Stein und zwei kleinen Seitenknospen
        m.add(box("pommel_gem", 7.6, -9.9, 7.6, 8.4, -9.3, 8.4, GEM_H).glow());
        m.add(box("pommel", 7.0, -9.3, 7.0, 9.0, -7.9, 9.0, GOLD_M));
        m.add(box("pommel", 6.6, -8.9, 7.4, 9.4, -8.3, 8.6, GOLD_M));
        m.add(box("pommel_neck", 7.3, -7.9, 7.3, 8.7, -7.5, 8.7, GOLD_M));
        // lange Wicklung aus Golddraht, Zwinge
        m.add(box("grip", 7.35, -7.5, 7.35, 8.65, 0.6, 8.65, GRIP_GOLD));
        m.add(box("ferrule", 7.0, 0.6, 7.0, 9.0, 1.3, 9.0, GOLD_M));
        // Parierstange: verzierter Goldkern mit Stein, eine Zunge läuft spitz auf die Klinge
        m.add(box("guard", 6.3, 1.3, 6.85, 9.7, 3.0, 9.15, GOLD_M));
        m.add(box("guard_gem", 7.45, 1.65, 6.75, 8.55, 2.65, 9.25, GEM_H).glow().noFaces(W, E, U, D));
        m.add(box("langet", 7.15, 3.0, 7.45, 8.85, 4.7, 8.55, GOLD_M));
        m.add(box("langet", 7.55, 4.7, 7.48, 8.45, 5.8, 8.52, GOLD_M));
        // Schwingen (Vorlage: große, dichte Flügel, die sich von der Parierstange zum Griff hin fächern).
        // Goldener Flügelbug, daran sieben breite, sich überlappende Federn in zwei Winkeln (22,5° und 45°).
        for (int s = -1; s <= 1; s += 2) {
            double r = 8 + s * 1.7, by = 2.6;
            double ca = Math.cos(Math.toRadians(22.5)), sa = Math.sin(Math.toRadians(22.5));
            m.add(box("wing_bow", Math.min(r, r + s * 6.4), by - 0.55, 7.3, Math.max(r, r + s * 6.4), by + 0.55, 8.7, GOLD_M).rotZ(s * 22.5).at(r, by, 8));
            double bx = r + s * 6.4 * ca, bty = by + 6.4 * sa;
            m.add(box("wing_bow_tip", Math.min(bx - s * 0.4, bx + s * 1.8), bty - 0.45, 7.4, Math.max(bx - s * 0.4, bx + s * 1.8), bty + 0.45, 8.6, GOLD_M).rotZ(s * 45).at(bx, bty, 8));
            double[] len = {7.2, 7.6, 7.4, 6.8, 6.0, 5.0, 3.8};
            double[] ang = {-22.5, -22.5, -45, -45, -45, -45, -45};
            for (int k = 0; k < 7; k++) {
                double along = 6.2 - k * 0.9;
                double fx = r + s * along * ca, fy = by + along * sa - 0.3;
                double z = 0.46 - k * 0.03;
                m.add(box("feather", Math.min(fx, fx + s * len[k]), fy - 0.75, 8 - z, Math.max(fx, fx + s * len[k]), fy + 0.75, 8 + z, FEATHER_M).rotZ(s * ang[k]).at(fx, fy, 8));
            }
        }
        // Klinge: lang und gerade, leuchtendes Gold; Spitze in drei Stufen
        m.add(box("blade", 6.75, 3.0, 7.62, 9.25, 21.5, 8.38, BLADE_HG).glow());
        m.add(box("blade", 7.05, 21.5, 7.64, 8.95, 23.0, 8.36, BLADE_HG).glow());
        m.add(box("blade", 7.45, 23.0, 7.66, 8.55, 24.2, 8.34, BLADE_HG).glow());
        m.add(box("tip", 7.8, 24.2, 7.7, 8.2, 25.0, 8.3, BLADE_HG).glow());
        return m;
    }

    /**
     * Hells Sword (Vorlage des Nutzers, 16.11.): schwere, breite Schwarzstahlklinge; an der Parierstange ein gehörnter
     * Totenschädel mit Seelenglut in den Augen, darunter eine Rinne voller kleiner Schädel, seitliche Dornen und eine
     * gegabelte Spitze; Knauf als Halbmond mit zwei Hörnern.
     */
    static List<El> hellsSword() {
        List<El> m = new ArrayList<>();
        // Knauf: Halbmond-Querstück mit zwei nach außen gebogenen Spitzen, darüber ein Stein
        m.add(box("pommel", 6.3, -9.0, 7.35, 9.7, -8.2, 8.65, STEEL_D));
        for (int s = -1; s <= 1; s += 2) {
            double x0 = 8 + s * 1.7;
            m.add(box("pommel_horn", Math.min(x0, x0 + s * 1.8), -9.0, 7.5, Math.max(x0, x0 + s * 1.8), -8.4, 8.5, STEEL_D).rotZ(-s * 45).at(x0, -8.7, 8));
        }
        m.add(box("pommel_gem", 7.6, -9.6, 7.6, 8.4, -9.0, 8.4, GEM_D).glow());
        // Griff mit zwei beschlagenen Ringen
        m.add(box("grip", 7.3, -8.2, 7.3, 8.7, -0.2, 8.7, GRIP_D));
        cuff(m, -5.6, -5.0, 0.85, STEEL_D, GEM_D);
        cuff(m, -2.4, -1.8, 0.85, STEEL_D, null);
        // Großer Totenschädel als Parierstange: Schädeldach zum Griff, offener Kiefer zur Klinge
        m.add(box("skull", 5.9, -0.2, 6.5, 10.1, 4.4, 9.5, SKULL_M));
        m.add(box("skull_brow", 5.7, 2.4, 6.4, 10.3, 3.0, 9.6, BONE_M));
        m.add(box("skull_eyes", 5.9, -0.2, 6.45, 10.1, 4.4, 9.55, SKULL_EYES).glow().noFaces(W, E, U, D));
        // Hörner: dick, erst seitlich, dann zur Klinge hin gebogen, die Spitze zeigt nach oben
        for (int s = -1; s <= 1; s += 2) {
            double hx = 8 + s * 1.9, hy = 0.8;
            m.add(box("horn", Math.min(hx, hx + s * 2.6), hy - 0.75, 7.25, Math.max(hx, hx + s * 2.6), hy + 0.75, 8.75, BONE_M));
            double ex = hx + s * 2.6;
            m.add(box("horn", Math.min(ex - s * 0.5, ex + s * 1.8), hy - 0.6, 7.4, Math.max(ex - s * 0.5, ex + s * 1.8), hy + 0.6, 8.6, BONE_M).rotZ(s * 45).at(ex, hy, 8));
            double tx = ex + s * 1.8 * Math.cos(Math.toRadians(45)), ty = hy + 1.8 * Math.sin(Math.toRadians(45));
            m.add(box("horn_tip", Math.min(tx - s * 0.45, tx + s * 0.35), ty - 0.2, 7.55, Math.max(tx - s * 0.45, tx + s * 0.35), ty + 2.0, 8.45, BONE_M));
            m.add(box("horn_tip", Math.min(tx - s * 0.2, tx + s * 0.15), ty + 2.0, 7.7, Math.max(tx - s * 0.2, tx + s * 0.15), ty + 2.9, 8.3, BONE_M));
        }
        // Breite Klinge mit hellem Rahmen, Rinne voller Schädel, zwei große Dornen am Ansatz, Verbreiterung, Gabel
        m.add(box("blade", 5.6, 4.4, 7.58, 10.4, 17.4, 8.42, BLADE_D));
        m.add(box("blade", 5.0, 17.4, 7.6, 11.0, 19.6, 8.4, BLADE_D));
        m.add(box("skulls", 6.8, 5.4, 7.5, 9.2, 16.6, 8.5, SKULLS_M));
        for (int s = -1; s <= 1; s += 2) {
            double x0 = 8 + s * 2.3;
            m.add(box("blade_spike", Math.min(x0, x0 + s * 2.4), 5.2, 7.68, Math.max(x0, x0 + s * 2.4), 5.9, 8.32, STEEL_D).rotZ(s * 45).at(x0, 5.5, 8));
            m.add(box("blade_spike", Math.min(x0, x0 + s * 1.8), 7.4, 7.7, Math.max(x0, x0 + s * 1.8), 8.0, 8.3, STEEL_D).rotZ(s * 45).at(x0, 7.7, 8));
            // Gabelspitze: zwei Zinken, die nach außen kippen
            double gx = s < 0 ? 5.0 : 8.9, gx1 = s < 0 ? 7.1 : 11.0;
            m.add(box("prong", gx, 19.6, 7.62, gx1, 21.8, 8.38, BLADE_D).rotZ(s * 22.5).at((gx + gx1) / 2, 19.6, 8));
            double cx = (gx + gx1) / 2 + s * 0.9;
            m.add(box("prong_tip", cx - 0.5, 21.6, 7.66, cx + 0.5, 23.0, 8.34, BLADE_D).rotZ(s * 22.5).at(cx, 21.6, 8));
        }
        m.add(box("vein", 5.7, 4.6, 7.54, 10.3, 19.2, 8.46, VEIN).glow().noFaces(W, E, U, D));
        return m;
    }

    /**
     * Heavens Mace (Vorlage des Nutzers, 16.11.): Flanschenkolben im Heaven-Stil. Weißstahl-Schaft mit Goldringen und
     * Lederwicklung, Eichel-Knauf; Kopf mit acht Flanschen (je zwei Lappen mit Kerbe dazwischen), goldgesäumt, oben
     * eine Goldspitze mit leuchtendem Stein.
     */
    static List<El> heavensMace() {
        List<El> m = new ArrayList<>();
        m.add(box("pommel_tip", 7.6, -10.2, 7.6, 8.4, -9.6, 8.4, GOLD_M));
        m.add(box("pommel", 7.1, -9.6, 7.1, 8.9, -8.2, 8.9, GOLD_M));
        m.add(box("shaft", 7.45, -8.2, 7.45, 8.55, 10.4, 8.55, STEEL_H));
        m.add(box("grip", 7.3, -7.6, 7.3, 8.7, -1.0, 8.7, GRIP_LEATHER_H));
        cuff(m, -1.0, -0.4, 0.8, GOLD_M, null);
        cuff(m, 2.6, 3.2, 0.75, GOLD_M, null);
        cuff(m, 6.2, 6.8, 0.75, GOLD_M, null);
        m.add(box("collar", 6.8, 9.4, 6.8, 9.2, 10.6, 9.2, GOLD_M));
        // Kern des Kopfs (hoch, schlank)
        m.add(box("head", 6.9, 10.6, 6.9, 9.1, 18.6, 9.1, STEEL_H_DARK));
        // acht Flanschen: je Steg, großer oberer Lappen mit Spitze, kleiner unterer Lappen, tiefe Kerbe dazwischen
        double[][] parts = {
            {9.1, 10.8, 10.5, 18.2},
            {9.1, 14.4, 12.9, 17.6},
            {12.0, 17.6, 12.9, 18.9},
            {9.1, 11.0, 12.0, 13.4},
        };
        for (int r = 0; r < 2; r++) {
            for (int dir = 0; dir < 4; dir++) {
                for (double[] q : parts) {
                    double x0 = q[0], y0 = q[1], x1 = q[2], y1 = q[3];
                    El e;
                    switch (dir) {
                        case 0: e = box("flange", x0, y0, 7.72, x1, y1, 8.28, FLANGE_H); break;
                        case 1: e = box("flange", 16 - x1, y0, 7.72, 16 - x0, y1, 8.28, FLANGE_H); break;
                        case 2: e = box("flange", 7.72, y0, x0, 8.28, y1, x1, FLANGE_H); break;
                        default: e = box("flange", 7.72, y0, 16 - x1, 8.28, y1, 16 - x0, FLANGE_H); break;
                    }
                    if (r == 1) e.rotY(45).at(8, (y0 + y1) / 2, 8);
                    m.add(e);
                }
            }
        }
        // Goldspitze mit Stein
        m.add(box("crown", 7.1, 18.6, 7.1, 8.9, 19.2, 8.9, GOLD_M));
        m.add(box("finial", 7.5, 19.2, 7.5, 8.5, 20.6, 8.5, GOLD_M));
        m.add(box("finial_gem", 7.65, 20.6, 7.65, 8.35, 21.4, 8.35, GEM_H).glow());
        return m;
    }

    /** Dorn aus drei sich verjüngenden Stücken entlang einer Achse (0 x, 1 y, 2 z), optional um BALL gedreht */
    static void spike(List<El> m, int axis, int dir, double r0, char rotAxis, double ang) {
        double[][] seg = {{0, 1.25, 0.9}, {1.25, 2.4, 0.6}, {2.4, 3.5, 0.3}};
        for (double[] s : seg) {
            double a = BALL[axis] + dir * (r0 + s[0]), b = BALL[axis] + dir * (r0 + s[1]);
            double[] lo = new double[3], hi = new double[3];
            for (int k = 0; k < 3; k++) { lo[k] = BALL[k] - s[2]; hi[k] = BALL[k] + s[2]; }
            lo[axis] = Math.min(a, b); hi[axis] = Math.max(a, b);
            El e = box("spike", lo[0], lo[1], lo[2], hi[0], hi[1], hi[2], SPIKE_D);
            if (rotAxis == 'x') e.rotX(ang); else if (rotAxis == 'y') e.rotY(ang); else if (rotAxis == 'z') e.rotZ(ang);
            if (rotAxis != '-') e.at(BALL[0], BALL[1], BALL[2]);
            m.add(e);
        }
    }
    /** Kettenglied um yc; inX = Glied liegt in der x-y-Ebene, sonst in der z-y-Ebene */
    static void link(List<El> m, double yc, boolean inX) {
        double[][] bars = {{-0.9, -1.25, -0.4, 1.25}, {0.4, -1.25, 0.9, 1.25}, {-0.9, 0.85, 0.9, 1.25}, {-0.9, -1.25, 0.9, -0.85}};
        for (double[] b : bars) {
            if (inX) m.add(box("chain", 8 + b[0], yc + b[1], 7.75, 8 + b[2], yc + b[3], 8.25, CHAIN));
            else m.add(box("chain", 7.75, yc + b[1], 8 + b[0], 8.25, yc + b[3], 8 + b[2], CHAIN));
        }
    }

    static List<El> hellsMace() { return hellsMace(false); }
    /**
     * Hells Mace: Kettenmorgenstern. [icon] = kompakte Fassung nur für das 32er-Icon: kürzerer Griff und breitere
     * Fugen, damit Kopf und Seelenfeuer im kleinen Bild lesbar bleiben (das Handmodell ist unverändert).
     */
    static List<El> hellsMace(boolean icon) {
        List<El> m = new ArrayList<>();
        double gy = icon ? 3.5 : 0;
        m.add(box("pommel_spike", 7.6, -8.25 + gy, 7.6, 8.4, -7.25 + gy, 8.4, SPIKE_D));
        m.add(box("pommel", 7.1, -7.25 + gy, 7.1, 8.9, -6.0 + gy, 8.9, STEEL_D));
        m.add(box("grip", 7.3, -6.0 + gy, 7.3, 8.7, 0.5, 8.7, GRIP_D));
        cuff(m, 0.5, 1.75, 0.85, STEEL_D, GEM_D);
        m.add(box("socket", 6.9, 1.75, 6.9, 9.1, 3.0, 9.1, STEEL_D));
        m.add(box("eye", 7.6, 3.0, 7.6, 8.4, 3.9, 8.4, CHAIN));
        link(m, 4.85, true);
        link(m, 6.85, false);
        link(m, 8.85, true);
        m.add(box("ball_eye", 7.6, 9.8, 7.6, 8.4, BALL[1] - 2.4, 8.4, CHAIN));
        // Kopf: drei gekreuzte Quader ergeben einen gerundeten Würfel, in den Kanten scheint der Leuchtkern durch
        double c = BALL[1];
        m.add(box("ball", 5.5, c - 2, 6, 10.5, c + 2, 10, BALL_M));
        m.add(box("ball", 6, c - 2.5, 6, 10, c + 2.5, 10, BALL_M));
        m.add(box("ball", 6, c - 2, 5.5, 10, c + 2, 10.5, BALL_M));
        m.add(box("ball", 6.25, c - 1.75, 6.25, 9.75, c + 1.75, 9.75, BALL_M).rotY(45).at(8, c, 8));
        double core = icon ? 2.48 : 2.3;
        m.add(box("soul_core", 8 - core, c - core, 8 - core, 8 + core, c + core, 8 + core, SOUL_CORE).glow());
        // 13 Dornen: oben, vier zur Seite, vier schräg in der Waagrechten, vier schräg nach oben
        spike(m, 1, 1, 2.5, '-', 0);
        spike(m, 0, 1, 2.5, '-', 0);
        spike(m, 0, -1, 2.5, '-', 0);
        spike(m, 2, 1, 2.5, '-', 0);
        spike(m, 2, -1, 2.5, '-', 0);
        spike(m, 0, 1, 2.9, 'y', 45);
        spike(m, 0, -1, 2.9, 'y', 45);
        spike(m, 2, 1, 2.9, 'y', 45);
        spike(m, 2, -1, 2.9, 'y', 45);
        spike(m, 0, 1, 2.9, 'z', 45);
        spike(m, 0, -1, 2.9, 'z', -45);
        spike(m, 2, 1, 2.9, 'x', -45);
        spike(m, 2, -1, 2.9, 'x', 45);
        return m;
    }

    // ------------------------------------------------------------------ Atlas
    /** malt alle Flächen, packt sie und liefert die Frames des Atlas */
    static BufferedImage[] atlas(List<El> model) {
        class Face { El e; int f, w, h; int[][] px; }
        List<Face> faces = new ArrayList<>();
        for (El e : model) for (int f = 0; f < 6; f++) {
            if (e.skip[f]) continue;
            double[][] c = e.corners(f);
            double wu = dist(c[0], c[1]), hv = dist(c[0], c[2]);
            Face fa = new Face(); fa.e = e; fa.f = f;
            fa.w = Math.max(1, (int) Math.round(wu * PX)); fa.h = Math.max(1, (int) Math.round(hv * PX));
            fa.px = new int[FRAMES][fa.w * fa.h];
            for (int fr = 0; fr < FRAMES; fr++) for (int j = 0; j < fa.h; j++) for (int i = 0; i < fa.w; i++) {
                double s = (i + 0.5) / fa.w, t = (j + 0.5) / fa.h;
                double[] p = new double[3];
                for (int k = 0; k < 3; k++) p[k] = c[0][k] + s * (c[1][k] - c[0][k]) + t * (c[2][k] - c[0][k]);
                // knapp nach innen rücken, damit Rundung an Flächenkanten stabil bleibt
                for (int k = 0; k < 3; k++) p[k] = Math.max(e.from[k] + 1e-4, Math.min(e.to[k] - 1e-4, p[k]));
                int col = e.mat.col(e, p, e.world(p), f, fr);
                fa.px[fr][j * fa.w + i] = col == CLEAR ? 0 : 0xFF000000 | col;
            }
            faces.add(fa);
        }
        // gleiche Flächen teilen sich ein Rechteck; Regal-Packer, hohe Flächen zuerst
        List<Face> order = new ArrayList<>(faces);
        order.sort((a, b) -> b.h != a.h ? b.h - a.h : b.w - a.w);
        Map<String, int[]> seen = new HashMap<>();
        BufferedImage[] out = new BufferedImage[FRAMES];
        for (int i = 0; i < FRAMES; i++) out[i] = new BufferedImage(ATLAS, ATLAS, BufferedImage.TYPE_INT_ARGB);
        int x = 0, y = 0, shelf = 0;
        for (Face fa : order) {
            String key = fa.w + "x" + fa.h + ":" + Arrays.deepHashCode(fa.px) + ":" + Arrays.hashCode(fa.px[0]);
            int[] r = seen.get(key);
            if (r == null) {
                if (x + fa.w > ATLAS) { x = 0; y += shelf; shelf = 0; }
                if (y + fa.h > ATLAS) throw new IllegalStateException("Atlas zu klein (" + ATLAS + ")");
                r = new int[]{x, y, fa.w, fa.h};
                for (int fr = 0; fr < FRAMES; fr++) for (int j = 0; j < fa.h; j++) for (int i = 0; i < fa.w; i++) out[fr].setRGB(x + i, y + j, fa.px[fr][j * fa.w + i]);
                x += fa.w; shelf = Math.max(shelf, fa.h);
                seen.put(key, r);
            }
            fa.e.uv[fa.f] = r;
        }
        System.out.println("  Atlas belegt bis Zeile " + (y + shelf) + " von " + ATLAS + ", " + faces.size() + " Flächen, " + seen.size() + " Rechtecke");
        return out;
    }
    static double dist(double[] a, double[] b) { return Math.sqrt(sq(a[0] - b[0]) + sq(a[1] - b[1]) + sq(a[2] - b[2])); }

    // ------------------------------------------------------------------ JSON
    static String num(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.equals("-0") ? "0" : s;
    }
    static String vec(double... v) { StringBuilder b = new StringBuilder("["); for (int i = 0; i < v.length; i++) b.append(i > 0 ? ", " : "").append(num(v[i])); return b.append("]").toString(); }

    static String json(List<El> model, String tex, String particle, String display, String head) {
        StringBuilder b = new StringBuilder();
        b.append("{\n").append(head).append("  \"textures\": {\n    \"0\": \"").append(tex).append("\",\n    \"particle\": \"").append(particle).append("\"\n  },\n  \"elements\": [\n");
        for (int i = 0; i < model.size(); i++) {
            El e = model.get(i);
            b.append("    {\n      \"name\": \"").append(e.name).append("\",\n      \"from\": ").append(vec(e.from)).append(",\n      \"to\": ").append(vec(e.to)).append(",\n");
            if (e.rot != null) {
                String axis = e.rot[0] != 0 ? "x" : e.rot[1] != 0 ? "y" : "z";
                double ang = e.rot[0] != 0 ? e.rot[0] : e.rot[1] != 0 ? e.rot[1] : e.rot[2];
                // Vanilla-Konvention: Winkel im Bereich -180..180
                while (ang > 180) ang -= 360;
                b.append("      \"rotation\": {\"origin\": ").append(vec(e.org)).append(", \"axis\": \"").append(axis).append("\", \"angle\": ").append(num(ang)).append("},\n");
            }
            if (e.emissive) b.append("      \"light_emission\": 15,\n      \"shade\": false,\n");
            b.append("      \"faces\": {\n");
            boolean first = true;
            for (int f = 0; f < 6; f++) {
                if (e.skip[f]) continue;
                int[] r = e.uv[f];
                double k = 16.0 / ATLAS;
                b.append(first ? "" : ",\n").append("        \"").append(FACES[f]).append("\": {\"uv\": ")
                    .append(vec(r[0] * k, r[1] * k, (r[0] + r[2]) * k, (r[1] + r[3]) * k)).append(", \"texture\": \"#0\"}");
                first = false;
            }
            b.append("\n      }\n    }").append(i < model.size() - 1 ? "," : "").append("\n");
        }
        b.append("  ]").append(display == null ? "" : ",\n  \"display\": " + display).append("\n}\n");
        return b.toString();
    }

    /**
     * Display-Block. Ausgangspunkt sind die Vanilla-Werte (z-Rotation, Verschiebung t, Größe s) für 3rd/1st
     * Person; die z-Rotation wird um 45° reduziert (aufrechtes Modell statt Diagonal-Sprite).
     * Eigene Größen n3/n1 und ein eigener Griffpunkt: der Punkt [grip] unseres Modells landet genau dort,
     * wo Vanilla den Punkt [vgrip] seines Sprites in der Hand hält:
     *   t' = t + R * (s * (vgrip - Mitte) - n * (grip - Mitte))        (Mitte = 8,8,8; R = Ry(-90) * Rz(z))
     */
    static String display(double[] tp, double[] fp) {
        return "{\n"
            + "    \"thirdperson_righthand\": " + displayEntry(tp) + ",\n"
            + "    \"thirdperson_lefthand\": " + displayEntry(leftOf(tp)) + ",\n"
            + "    \"firstperson_righthand\": " + displayEntry(fp) + ",\n"
            + "    \"firstperson_lefthand\": " + displayEntry(leftOf(fp)) + ",\n"
            + "    \"head\": {\"rotation\": [0, 180, 0], \"translation\": [0, 13, 7], \"scale\": [1, 1, 1]}\n"
            + "  }";
    }
    /** ein Eintrag aus {rx, ry, rz, tx, ty, tz, Größe} */
    static String displayEntry(double[] d) {
        return "{\"rotation\": " + vec(d[0], d[1], d[2]) + ", \"translation\": " + vec(d[3], d[4], d[5]) + ", \"scale\": " + vec(d[6], d[6], d[6]) + "}";
    }
    /**
     * JSON-Werte der linken Hand, sodass sie das exakte Spiegelbild der rechten zeigt.
     * Das Spiel negiert bei der linken Hand die x-Verschiebung sowie y- und z-Rotation DES LINKEN EINTRAGS
     * (Transformation.apply), das Modell selbst wird nicht gespiegelt. Gesucht ist die Drehung L = M*R*Z
     * (M = Spiegelung an x im Bild, Z = Spiegelung des Modells an seiner z-Symmetrieebene; beide Waffen sind
     * in z symmetrisch). Deren Euler-Winkel werden mit negiertem y/z eingetragen, damit das Spiel sie zurückdreht.
     * Für Vanilla-artige Rotationen [0,-90,z] ergibt das wie in `handheld.json` [0,90,-z].
     */
    static double[] leftOf(double[] d) {
        double[][] r = rotXYZ(d), l = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) l[i][j] = (i == 0 ? -1 : 1) * r[i][j] * (j == 2 ? -1 : 1);
        double[] e = euler(l);
        return new double[]{e[0], -e[1], -e[2], d[3], d[4], d[5], d[6]};
    }
    /** Euler-Winkel {x,y,z} in Grad mit m = Rx*Ry*Rz (Minecrafts Display-Rotation) */
    static double[] euler(double[][] m) {
        double a, b, c;
        if (Math.abs(m[0][2]) > 0.999999) { a = 0; b = m[0][2] > 0 ? 90 : -90; c = Math.toDegrees(Math.atan2(m[1][0], m[1][1])); }
        else {
            b = Math.toDegrees(Math.asin(m[0][2]));
            a = Math.toDegrees(Math.atan2(-m[1][2], m[2][2]));
            c = Math.toDegrees(Math.atan2(-m[0][1], m[0][0]));
        }
        if (Math.abs(a) > 90) {                                   // gleichwertige Darstellung mit kleinem x-Winkel
            a -= Math.signum(a) * 180; b = 180 - b; c -= Math.signum(c) * 180;
            if (b > 180) b -= 360;
        }
        return new double[]{r2(a), r2(b), r2(c)};
    }
    static double r2(double v) { double r = Math.round(v * 100) / 100.0; return r == 0 ? 0 : r; }
    /**
     * Ego-Sicht als Haltung beschrieben statt als Euler-Winkel. Ausgangslage wie Vanilla (Waffe aufrecht in der
     * Ebene neben dem Spieler, -x des Modells = Schneide/Schlagfläche zeigt nach vorn), dann
     *   roll:  Drehung um die Längsachse (positiv: die linke Flachseite dreht sich zum Spieler)
     *   pitch: Spitze nach vorn (vom Spieler weg) geneigt
     *   lean:  Spitze nach links (zur Bildmitte) geneigt
     * also R = Rz(lean) * Rx(-pitch) * Ry(-90 + roll). [hand] = wo der Griffpunkt [grip] liegt, in 1/16 Block
     * relativ zum Ego-Ursprung des Spiels (0.56, -0.52, -0.72 vor der Kamera; Vanilla-Schwertgriff = [1.1, -1.3, -0.5]).
     */
    static double[] pose(double lean, double pitch, double roll, double[] hand, double n, double[] grip) {
        double[][] r = mul(Rz(lean), mul(Rx(-pitch), Ry(-90 + roll)));
        double[] b = mul(r, new double[]{n * (grip[0] - 8), n * (grip[1] - 8), n * (grip[2] - 8)}), e = euler(r);
        return new double[]{e[0], e[1], e[2], r2(hand[0] - b[0]), r2(hand[1] - b[1]), r2(hand[2] - b[2]), n};
    }
    /**
     * Display-Werte für "in der Hand gehalten": Rotation [rot], Größe n, und die Verschiebung so, dass der
     * Griffpunkt [grip] unseres Modells genau dort landet, wo Vanilla den Punkt [vgrip] seines Sprites hält.
     * Vanilla-Werte: z-Rotation zV (Diagonal-Sprite, deshalb -45° fürs aufrechte Modell), Verschiebung tV, Größe sV.
     *   t' = tV + Ry(-90)*Rz(zV-45) * sV * (vgrip - Mitte)  -  Rx*Ry*Rz(rot) * n * (grip - Mitte)        (Mitte = 8,8,8)
     */
    static double[] held(double zV, double[] tV, double sV, double[] vgrip, double[] rot, double n, double[] grip) {
        double[] a = new double[3], b = new double[3];
        for (int k = 0; k < 3; k++) { a[k] = sV * (vgrip[k] - 8); b[k] = n * (grip[k] - 8); }
        a = mul(mul(Ry(-90), Rz(zV - 45)), a); b = mul(rotXYZ(rot), b);
        return new double[]{rot[0], rot[1], rot[2], tV[0] + a[0] - b[0], tV[1] + a[1] - b[1], tV[2] + a[2] - b[2], n};
    }

    // ------------------------------------------------------------------ Renderer
    static class Quad { double[] a, b, d, n; int[] uv; boolean emissive, fx; int flat; /* != 0: einfarbig (Hand-Vorschau) */ }

    static List<Quad> quads(List<El> model) {
        List<Quad> qs = new ArrayList<>();
        double[][] normals = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
        for (El e : model) for (int f = 0; f < 6; f++) {
            if (e.skip[f]) continue;
            double[][] c = e.corners(f);
            Quad qd = new Quad();
            qd.a = e.world(c[0]); qd.b = e.world(c[1]); qd.d = e.world(c[2]);
            double[] o = e.world(e.org.clone()), nn = e.world(new double[]{e.org[0] + normals[f][0], e.org[1] + normals[f][1], e.org[2] + normals[f][2]});
            qd.n = new double[]{nn[0] - o[0], nn[1] - o[1], nn[2] - o[2]};
            qd.uv = e.uv[f]; qd.emissive = e.emissive; qd.fx = e.fx;
            qs.add(qd);
        }
        return qs;
    }

    /** Ansicht fürs Icon: um die Längsachse gedreht, von oben geneigt, dann auf die Vanilla-Diagonale gelegt */
    static double[][] iconView(double turn, double tilt) { return mul(Rz(-45), mul(Rx(tilt), Ry(turn))); }

    /**
     * Orthografischer Render. Liefert w*h ARGB; pro Pixel ss*ss Samples, dominante Farbe (min. 38 % Deckung).
     * fit = {Skala px/Einheit, vx bei Pixel 0, vy bei Pixel 0 (oben)}; solidOnly lässt Effekt-Elemente weg.
     */
    static int[] render(List<Quad> qs, BufferedImage tex, double[][] view, double[] fit, int w, int h, int ss, boolean solidOnly) {
        int n = qs.size();
        double[][] A = new double[n][], B = new double[n][], Dd = new double[n][];
        double[] shade = new double[n];
        double[] L = {-0.36, 0.74, 0.57};
        for (int i = 0; i < n; i++) {
            Quad qd = qs.get(i);
            A[i] = mul(view, sub8(qd.a)); B[i] = mul(view, sub8(qd.b)); Dd[i] = mul(view, sub8(qd.d));
            double[] nv = mul(view, qd.n);
            double dot = Math.max(0, nv[0] * L[0] + nv[1] * L[1] + nv[2] * L[2]);
            shade[i] = qd.emissive ? 1 : Math.round((0.60 + 0.40 * dot) * 12) / 12.0;
        }
        int[] out = new int[w * h];
        Map<Integer, Integer> cnt = new HashMap<>();
        for (int py = 0; py < h; py++) for (int px = 0; px < w; px++) {
            cnt.clear();
            int covered = 0;
            for (int sy = 0; sy < ss; sy++) for (int sx = 0; sx < ss; sx++) {
                double vx = fit[1] + (px + (sx + 0.5) / ss) / fit[0], vy = fit[2] - (py + (sy + 0.5) / ss) / fit[0];
                double bestZ = -1e9; int best = 0;
                for (int i = 0; i < n; i++) {
                    if (solidOnly && qs.get(i).fx) continue;
                    double ux = B[i][0] - A[i][0], uy = B[i][1] - A[i][1], wx = Dd[i][0] - A[i][0], wy = Dd[i][1] - A[i][1];
                    double det = ux * wy - uy * wx;
                    if (Math.abs(det) < 1e-9) continue;
                    double rx = vx - A[i][0], ry = vy - A[i][1];
                    double s = (rx * wy - ry * wx) / det, t = (ux * ry - uy * rx) / det;
                    if (s < 0 || s >= 1 || t < 0 || t >= 1) continue;
                    double z = A[i][2] + s * (B[i][2] - A[i][2]) + t * (Dd[i][2] - A[i][2]);
                    if (z <= bestZ) continue;
                    int[] r = qs.get(i).uv;
                    int c = tex.getRGB(r[0] + Math.min(r[2] - 1, (int) (s * r[2])), r[1] + Math.min(r[3] - 1, (int) (t * r[3])));
                    if ((c >>> 24) == 0) continue;                          // Cutout
                    bestZ = z;
                    double k = shade[i];
                    best = 0xFF000000 | ((int) (((c >> 16) & 255) * k) << 16) | ((int) (((c >> 8) & 255) * k) << 8) | (int) ((c & 255) * k);
                }
                if (bestZ > -1e8) { covered++; cnt.merge(best, 1, Integer::sum); }
            }
            if (covered >= ss * ss * 0.38) {
                int bc = 0, bn = -1;
                for (var en : cnt.entrySet()) if (en.getValue() > bn || (en.getValue() == bn && lum(en.getKey()) > lum(bc))) { bn = en.getValue(); bc = en.getKey(); }
                out[py * w + px] = bc;
            }
        }
        return out;
    }
    static double[] sub8(double[] p) { return new double[]{p[0] - 8, p[1] - 8, p[2] - 8}; }
    static int lum(int c) { return ((c >> 16) & 255) * 3 + ((c >> 8) & 255) * 6 + (c & 255); }

    /** Bildausschnitt so wählen, dass das Modell w x h (abzüglich Rand) füllt */
    static double[] fit(List<Quad> qs, double[][] view, int w, int h, double margin) {
        double x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9;
        for (Quad qd : qs) {
            if (qd.fx) continue;                                            // Flammen-Ebenen bestimmen den Ausschnitt nicht
            double[] c4 = {qd.b[0] + qd.d[0] - qd.a[0], qd.b[1] + qd.d[1] - qd.a[1], qd.b[2] + qd.d[2] - qd.a[2]};
            for (double[] p : new double[][]{qd.a, qd.b, qd.d, c4}) {
                double[] v = mul(view, sub8(p));
                x0 = Math.min(x0, v[0]); x1 = Math.max(x1, v[0]); y0 = Math.min(y0, v[1]); y1 = Math.max(y1, v[1]);
            }
        }
        double sc = Math.min((w - 2 * margin) / (x1 - x0), (h - 2 * margin) / (y1 - y0));
        double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        return new double[]{sc, cx - w / 2.0 / sc, cy + h / 2.0 / sc};
    }

    /** Umriss um die Silhouette [px] (nur feste Teile); gezeichnet wird in [all] (mit Effekten), wo dort noch nichts ist */
    static int[] outline(int[] px, int[] all, int w, int h) {
        int[] out = all.clone();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if ((px[y * w + x] >>> 24) != 0 || (all[y * w + x] >>> 24) != 0) continue;
            boolean nb = (x > 0 && (px[y * w + x - 1] >>> 24) != 0) || (x < w - 1 && (px[y * w + x + 1] >>> 24) != 0)
                || (y > 0 && (px[(y - 1) * w + x] >>> 24) != 0) || (y < h - 1 && (px[(y + 1) * w + x] >>> 24) != 0);
            if (nb) out[y * w + x] = 0xFF000000 | OUTLINE;
        }
        return out;
    }

    // ------------------------------------------------------------------ Ausgabe
    static BufferedImage strip(BufferedImage[] frames) {
        int w = frames[0].getWidth(), h = frames[0].getHeight();
        BufferedImage s = new BufferedImage(w, h * frames.length, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < frames.length; i++) s.getGraphics().drawImage(frames[i], 0, i * h, null);
        return s;
    }
    static BufferedImage img(int[] px, int w, int h) {
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        b.setRGB(0, 0, w, h, px, 0, w);
        return b;
    }
    static void big(Graphics2D g, BufferedImage im, int x, int y, int sc) {
        for (int j = 0; j < im.getHeight(); j++) for (int i = 0; i < im.getWidth(); i++) {
            int c = im.getRGB(i, j);
            if ((c >>> 24) == 0) continue;
            g.setColor(new Color(c, true)); g.fillRect(x + i * sc, y + j * sc, sc, sc);
        }
    }

    // ------------------------------------------------------------------ Bauen und Schreiben
    /** „Aufgeladene" Fassung des Atlas: alle Flächen leuchtender Elemente um CHARGE zu Weiß hin aufgehellt */
    static BufferedImage[] charged(List<El> model, BufferedImage[] atlas) {
        BufferedImage[] out = new BufferedImage[atlas.length];
        for (int i = 0; i < atlas.length; i++) {
            out[i] = new BufferedImage(ATLAS, ATLAS, BufferedImage.TYPE_INT_ARGB);
            out[i].getGraphics().drawImage(atlas[i], 0, 0, null);
        }
        java.util.Set<String> done = new java.util.HashSet<>();
        for (El e : model) {
            if (!e.emissive) continue;
            for (int f = 0; f < 6; f++) {
                if (e.skip[f]) continue;
                int[] r = e.uv[f];
                if (!done.add(Arrays.toString(r))) continue;
                for (BufferedImage im : out) for (int y = r[1]; y < r[1] + r[3]; y++) for (int x = r[0]; x < r[0] + r[2]; x++) {
                    int c = im.getRGB(x, y);
                    if ((c >>> 24) == 0) continue;
                    im.setRGB(x, y, 0xFF000000 | mix(c & 0xFFFFFF, 0xFFFFFF, CHARGE));
                }
            }
        }
        return out;
    }

    static BufferedImage[] icons(List<Quad> qs, BufferedImage[] atlas, double[][] view, double[] fit) {
        BufferedImage[] icons = new BufferedImage[FRAMES];
        int[] solid = render(qs, atlas[0], view, fit, ICON, ICON, SS, true);          // Silhouette ist in allen Frames gleich
        for (int f = 0; f < FRAMES; f++) icons[f] = img(outline(solid, render(qs, atlas[f], view, fit, ICON, ICON, SS, false), ICON, ICON), ICON, ICON);
        return icons;
    }

    static String mcmeta(int ticks) { return "{\"animation\":{\"frametime\":" + ticks + ",\"interpolate\":true}}\n"; }

    /**
     * Baut eine Waffe: Atlas, Icon, JSON. [enchVariant] = zusätzlich die Verzauberungs-Fassung (Maces),
     * [thrown] = zusätzlich das Modell des geworfenen Hammers.
     */
    static void build(Path root, Path preview, String name, List<El> model, String display, double turn, double tilt,
                      int atlasTicks, int iconTicks, int outline, boolean enchVariant, boolean thrown) throws Exception {
        build(root, preview, name, model, null, display, turn, tilt, atlasTicks, iconTicks, outline, enchVariant, thrown);
    }
    /** [iconModel] = eigene Fassung fürs Icon (null = dasselbe Modell) */
    static void build(Path root, Path preview, String name, List<El> model, List<El> iconModel, String display, double turn, double tilt,
                      int atlasTicks, int iconTicks, int outline, boolean enchVariant, boolean thrown) throws Exception {
        System.out.println(name + ": " + model.size() + " Elemente");
        OUTLINE = outline;
        BufferedImage[] atlas = atlas(model);
        List<Quad> qs = quads(model);
        double[][] view = iconView(turn, tilt);
        List<El> im = iconModel != null ? iconModel : model;
        BufferedImage[] iconAtlas = iconModel != null ? atlas(iconModel) : atlas;
        List<Quad> iqs = iconModel != null ? quads(iconModel) : qs;
        double[] fit = fit(iqs, view, ICON, ICON, 1.5);
        BufferedImage[] icons = icons(iqs, iconAtlas, view, fit);
        BufferedImage[] atlasE = enchVariant ? charged(model, atlas) : null;
        BufferedImage[] iconsE = enchVariant ? icons(iqs, charged(im, iconAtlas), view, fit) : null;

        if (preview != null) {
            Files.createDirectories(preview);
            int P = 420;
            BufferedImage sheet = new BufferedImage(P * 4 + 50, P + ICON * 6 * 2 + 90, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = sheet.createGraphics();
            g.setColor(new Color(0x59636E)); g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            g.setColor(new Color(0x14121A)); g.fillRect(P * 3 + 35, 0, P + 15, P + 20);
            double[][][] views = {iconView(turn, tilt), iconView(0, 0), iconView(90, 0), iconView(turn + 180, tilt)};
            for (int v = 0; v < 4; v++) {
                double[] ft = fit(qs, views[v], P, P, 8);
                big(g, img(render(qs, atlas[v == 3 ? 4 : 0], views[v], ft, P, P, 1, false), P, P), 10 + v * (P + 10) + (v == 3 ? 5 : 0), 10, 1);
            }
            for (int f = 0; f < FRAMES; f++) big(g, icons[f], 10 + f * (ICON * 6 + 8), P + 30, 6);
            if (iconsE != null) for (int f = 0; f < FRAMES; f += 2) big(g, iconsE[f], 10 + (f / 2) * (ICON * 6 + 8), P + 40 + ICON * 6, 6);
            int ix = 10 + 4 * (ICON * 6 + 8) + 20;
            for (int k = 0; k < 2; k++) {
                g.setColor(k == 0 ? new Color(0x8B8B8B) : new Color(0xC6C6C6)); g.fillRect(ix + k * 120, P + 40 + ICON * 6, 100, 100);
                big(g, icons[0], ix + k * 120 + 18, P + 58 + ICON * 6, 2);
                big(g, icons[0], ix + k * 120 + 2, P + 42 + ICON * 6, 1);
            }
            g.dispose();
            ImageIO.write(sheet, "png", preview.resolve(name + "_sheet.png").toFile());
            BufferedImage at = new BufferedImage(ATLAS * 4 * 2 + 10, ATLAS * 4, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = at.createGraphics();
            g2.setColor(new Color(0x59636E)); g2.fillRect(0, 0, at.getWidth(), at.getHeight());
            big(g2, atlas[0], 0, 0, 4); big(g2, atlas[4], ATLAS * 4 + 10, 0, 4);
            g2.dispose();
            ImageIO.write(at, "png", preview.resolve(name + "_atlas.png").toFile());
            System.out.println("  Vorschau: " + preview.resolve(name + "_sheet.png"));
            return;
        }
        Path assets = root.resolve("src/main/resources/assets/hha");
        Path tex = assets.resolve("textures/item");
        Path models = assets.resolve("models/item");
        Files.writeString(models.resolve(name + "_3d.json"), json(model, "hha:item/" + name + "_3d", "hha:item/" + name, display, ""));
        ImageIO.write(strip(atlas), "png", tex.resolve(name + "_3d.png").toFile());
        Files.writeString(tex.resolve(name + "_3d.png.mcmeta"), mcmeta(atlasTicks));
        ImageIO.write(strip(icons), "png", tex.resolve(name + ".png").toFile());
        Files.writeString(tex.resolve(name + ".png.mcmeta"), mcmeta(iconTicks));
        System.out.println("  geschrieben: models/item/" + name + "_3d.json, textures/item/" + name + "_3d.png, textures/item/" + name + ".png");
        if (enchVariant) {
            Files.writeString(models.resolve(name + "_ench_3d.json"), "{\n  \"parent\": \"hha:item/" + name + "_3d\",\n  \"textures\": {\n    \"0\": \"hha:item/"
                + name + "_ench_3d\",\n    \"particle\": \"hha:item/" + name + "_ench\"\n  }\n}\n");
            ImageIO.write(strip(atlasE), "png", tex.resolve(name + "_ench_3d.png").toFile());
            Files.writeString(tex.resolve(name + "_ench_3d.png.mcmeta"), mcmeta(atlasTicks));
            ImageIO.write(strip(iconsE), "png", tex.resolve(name + "_ench.png").toFile());
            Files.writeString(tex.resolve(name + "_ench.png.mcmeta"), mcmeta(iconTicks));
            System.out.println("  geschrieben: models/item/" + name + "_ench_3d.json, textures/item/" + name + "_ench_3d.png, " + name + "_ench.png");
        }
        if (name.equals("hells_mace")) flailParts(models, name, model, display, enchVariant);
        if (thrown) {
            double y0 = 1e9, y1 = -1e9;
            for (Quad qd : qs) for (double[] pt : new double[][]{qd.a, qd.b, qd.d}) { y0 = Math.min(y0, pt[1]); y1 = Math.max(y1, pt[1]); }
            double ty = (8 - (y0 + y1) / 2) * THROWN_SCALE;
            // Der Renderer (HeavensMaceEntityRenderer) dreht um y und neigt um z; das Modell wird dafür auf seine Mitte gelegt.
            Files.writeString(models.resolve(name + "_thrown.json"), "{\n  \"parent\": \"hha:item/" + name + "_3d\",\n  \"display\": {\n"
                + "    \"ground\": {\"rotation\": [0, 0, 0], \"translation\": " + vec(0, ty, 0) + ", \"scale\": " + vec(THROWN_SCALE, THROWN_SCALE, THROWN_SCALE) + "}\n  }\n}\n");
            System.out.println("  geschrieben: models/item/" + name + "_thrown.json");
        }
    }

    /**
     * Teile des Kettenmorgensterns für die Kettenphysik in der Hand (FlailModelRenderer): Griff (mit Display), drei
     * Kettenglieder und Kopf. Alle Teile behalten ihre Lage im Gesamtmodell und teilen sich dessen Atlas; der Renderer
     * dreht jedes Glied um seinen Aufhängepunkt (siehe FlailModelRenderer.REST).
     */
    static void flailParts(Path models, String name, List<El> model, String display, boolean ench) throws Exception {
        List<El> handle = new ArrayList<>(), head = new ArrayList<>();
        List<List<El>> links = new ArrayList<>();
        int phase = 0; // 0 Griff, 1 Kette, 2 Kopf
        for (El e : model) {
            if (phase == 0 && e.name.equals("chain")) phase = 1;
            if (phase == 1 && !e.name.equals("chain")) phase = 2;
            if (phase == 0) handle.add(e);
            else if (phase == 2) head.add(e);
            else {
                if (links.isEmpty() || links.get(links.size() - 1).size() == 4) links.add(new ArrayList<>());
                links.get(links.size() - 1).add(e);
            }
        }
        if (links.size() != 3) throw new IllegalStateException("Kettenmorgenstern: 3 Glieder erwartet, " + links.size() + " gefunden");
        String tex = "hha:item/" + name + "_3d", particle = "hha:item/" + name;
        Map<String, List<El>> parts = new LinkedHashMap<>();
        parts.put("handle", handle);
        for (int i = 0; i < 3; i++) parts.put("link" + (i + 1), links.get(i));
        parts.put("head", head);
        for (Map.Entry<String, List<El>> p : parts.entrySet()) {
            String part = name + "_" + p.getKey();
            Files.writeString(models.resolve(part + "_3d.json"), json(p.getValue(), tex, particle, p.getKey().equals("handle") ? display : null, ""));
            if (ench) Files.writeString(models.resolve(part + "_ench_3d.json"), "{\n  \"parent\": \"hha:item/" + part + "_3d\",\n  \"textures\": {\n    \"0\": \"hha:item/"
                + name + "_ench_3d\",\n    \"particle\": \"hha:item/" + name + "_ench\"\n  }\n}\n");
        }
        System.out.println("  geschrieben: Kettenteile " + parts.keySet() + " (" + handle.size() + "/" + links.get(0).size() * 3 + "/" + head.size() + " Elemente)");
    }

    // ------------------------------------------------------------------ Hand-Vorschau (--hand <dir>)
    // Rechnet nach, was das Spiel mit den Display-Werten macht, und rendert die Waffe so, wie sie in der Ego-Sicht
    // (rechte + linke Hand, 854x480, FOV 70) und am Spielermodell (Seite / vorn / F5 von hinten) erscheint.
    // Damit lassen sich Display-Werte in Sekunden ausprobieren statt mit einem Spielstart je Versuch.
    //   Ego-Sicht:   Kamera im Ursprung, Blick -z;  p_cam = T(±0.56, -0.52, -0.72) * Display * p
    //   3rd Person:  Spieler = scale(-1,-1,1) * 0.9375 * T(0,-1.501,0); Arm-Drehpunkt (-5,2,0)/16, Arm um -18° nach
    //                vorn; Item = Arm * Rx(-90) * Ry(180) * T(1/16, 0.125, -0.625) * Display * p
    //   Display:     T(translation/16) * Rx*Ry*Rz * scale * T(-0.5) * p/16   (linke Hand: x-Verschiebung, y-/z-Rotation gespiegelt)
    // Varianten zum Vergleichen per System-Property, mehrere durch ';' getrennt, je "rx,ry,rz,tx,ty,tz,s":
    //   java -D<waffe>.fp="..." -D<waffe>.tp="..." [-Donly=<waffe>] tools/textures/GenHeavenHellModels.java . --hand <dir>
    static double[][] rotXYZ(double[] r) { return mul(Rx(r[0]), mul(Ry(r[1]), Rz(r[2]))); }

    /** affine Abbildung p -> m*p + o */
    static class Xf {
        final double[][] m; final double[] o;
        Xf(double[][] m, double[] o) { this.m = m; this.o = o; }
        static Xf move(double x, double y, double z) { return new Xf(Rz(0), new double[]{x, y, z}); }
        static Xf rot(double[][] r) { return new Xf(r, new double[3]); }
        static Xf scale(double x, double y, double z) { return new Xf(new double[][]{{x, 0, 0}, {0, y, 0}, {0, 0, z}}, new double[3]); }
        double[] at(double[] p) { double[] v = mul(m, p); return new double[]{v[0] + o[0], v[1] + o[1], v[2] + o[2]}; }
        /** Verkettung: die letzte Abbildung wird zuerst angewendet (wie ein MatrixStack) */
        static Xf chain(Xf... x) {
            Xf r = x[x.length - 1];
            for (int i = x.length - 2; i >= 0; i--) r = new Xf(mul(x[i].m, r.m), x[i].at(r.o));
            return r;
        }
    }
    /** Display-Eintrag {rx,ry,rz,tx,ty,tz,s} als Abbildung von Modell-Einheiten (0..16) in Blöcke; left: so wie das Spiel den Eintrag der linken Hand anwendet */
    static Xf displayXf(double[] d, boolean left) {
        int i = left ? -1 : 1;
        return Xf.chain(Xf.move(i * d[3] / 16, d[4] / 16, d[5] / 16), Xf.rot(rotXYZ(new double[]{d[0], i * d[1], i * d[2]})),
            Xf.scale(d[6], d[6], d[6]), Xf.move(-0.5, -0.5, -0.5), Xf.scale(1 / 16.0, 1 / 16.0, 1 / 16.0));
    }
    static List<Quad> place(List<Quad> qs, Xf x) {
        List<Quad> out = new ArrayList<>();
        for (Quad s : qs) {
            Quad qd = new Quad();
            qd.a = x.at(s.a); qd.b = x.at(s.b); qd.d = x.at(s.d);
            double[] n = mul(x.m, s.n); double l = Math.sqrt(sq(n[0]) + sq(n[1]) + sq(n[2]));
            qd.n = new double[]{n[0] / l, n[1] / l, n[2] / l};
            qd.uv = s.uv; qd.emissive = s.emissive; qd.fx = s.fx; qd.flat = s.flat;
            out.add(qd);
        }
        return out;
    }
    static void box(List<Quad> out, Xf x, double x1, double y1, double z1, double x2, double y2, double z2, int col) {
        El e = new El("box", x1, y1, z1, x2, y2, z2, null);
        double[][] normals = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
        List<Quad> qs = new ArrayList<>();
        for (int f = 0; f < 6; f++) {
            double[][] c = e.corners(f);
            Quad qd = new Quad(); qd.a = c[0]; qd.b = c[1]; qd.d = c[2]; qd.n = normals[f]; qd.flat = 0xFF000000 | col;
            qs.add(qd);
        }
        out.addAll(place(qs, x));
    }
    static Xf lookAt(double[] eye, double[] target) {
        double[] f = {target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]};
        double l = Math.sqrt(sq(f[0]) + sq(f[1]) + sq(f[2]));
        for (int k = 0; k < 3; k++) f[k] /= l;
        double[] r = {-f[2], 0, f[0]};                                         // f x (0,1,0)
        l = Math.hypot(r[0], r[2]); r[0] /= l; r[2] /= l;
        double[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
        double[][] m = {r, u, {-f[0], -f[1], -f[2]}};
        double[] o = mul(m, eye);
        return new Xf(m, new double[]{-o[0], -o[1], -o[2]});
    }
    /** perspektivischer Render von Quads im Kameraraum (Blick -z) auf [img] */
    static void renderPersp(List<Quad> qs, BufferedImage tex, BufferedImage img, double fovDeg) {
        int w = img.getWidth(), h = img.getHeight(), n = qs.size();
        double tan = Math.tan(Math.toRadians(fovDeg) / 2), aspect = (double) w / h;
        double[] L = {-0.30, 0.80, 0.52};
        double[][] U = new double[n][], V = new double[n][], Nn = new double[n][];
        double[] uu = new double[n], vv = new double[n], an = new double[n], shade = new double[n];
        for (int i = 0; i < n; i++) {
            Quad qd = qs.get(i);
            U[i] = new double[]{qd.b[0] - qd.a[0], qd.b[1] - qd.a[1], qd.b[2] - qd.a[2]};
            V[i] = new double[]{qd.d[0] - qd.a[0], qd.d[1] - qd.a[1], qd.d[2] - qd.a[2]};
            Nn[i] = new double[]{U[i][1] * V[i][2] - U[i][2] * V[i][1], U[i][2] * V[i][0] - U[i][0] * V[i][2], U[i][0] * V[i][1] - U[i][1] * V[i][0]};
            uu[i] = sq(U[i][0]) + sq(U[i][1]) + sq(U[i][2]); vv[i] = sq(V[i][0]) + sq(V[i][1]) + sq(V[i][2]);
            an[i] = qd.a[0] * Nn[i][0] + qd.a[1] * Nn[i][1] + qd.a[2] * Nn[i][2];
            shade[i] = qd.emissive ? 1 : 0.55 + 0.45 * Math.max(0, qd.n[0] * L[0] + qd.n[1] * L[1] + qd.n[2] * L[2]);
        }
        for (int py = 0; py < h; py++) for (int px = 0; px < w; px++) {
            double dx = (2 * (px + 0.5) / w - 1) * tan * aspect, dy = (1 - 2 * (py + 0.5) / h) * tan, dz = -1;
            double bestK = 1e9; int best = 0;
            for (int i = 0; i < n; i++) {
                double dn = dx * Nn[i][0] + dy * Nn[i][1] + dz * Nn[i][2];
                if (Math.abs(dn) < 1e-12) continue;
                double k = an[i] / dn;
                if (k < 0.05 || k >= bestK) continue;
                Quad qd = qs.get(i);
                double rx = k * dx - qd.a[0], ry = k * dy - qd.a[1], rz = k * dz - qd.a[2];
                double s = (rx * U[i][0] + ry * U[i][1] + rz * U[i][2]) / uu[i], t = (rx * V[i][0] + ry * V[i][1] + rz * V[i][2]) / vv[i];
                if (s < 0 || s >= 1 || t < 0 || t >= 1) continue;
                int c;
                if (qd.flat != 0) c = qd.flat;
                else {
                    int[] r = qd.uv;
                    c = tex.getRGB(r[0] + Math.min(r[2] - 1, (int) (s * r[2])), r[1] + Math.min(r[3] - 1, (int) (t * r[3])));
                    if ((c >>> 24) == 0) continue;
                }
                bestK = k;
                double sh = shade[i];
                best = 0xFF000000 | ((int) (((c >> 16) & 255) * sh) << 16) | ((int) (((c >> 8) & 255) * sh) << 8) | (int) ((c & 255) * sh);
            }
            if (bestK < 1e8) img.setRGB(px, py, best);
        }
    }
    static BufferedImage backdrop(int w, int h, boolean hud) {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.setColor(new Color(0x9DBEF5)); g.fillRect(0, 0, w, h / 2);
        g.setColor(new Color(0x4B4F58)); g.fillRect(0, h / 2, w, h - h / 2);
        if (hud) {
            g.setColor(Color.WHITE); g.fillRect(w / 2 - 5, h / 2, 11, 1); g.fillRect(w / 2, h / 2 - 5, 1, 11);
            g.setColor(new Color(0x30000000, true)); g.fillRect(w / 2 - 182, h - 44, 364, 44);      // Hotbar
        }
        g.dispose();
        return im;
    }
    /** Ego-Sicht mit Display-Werten d */
    static BufferedImage firstPerson(List<Quad> qs, BufferedImage tex, double[] d, boolean left) {
        BufferedImage im = backdrop(854, 480, true);
        renderPersp(place(qs, Xf.chain(Xf.move(left ? -0.56 : 0.56, -0.52, -0.72), displayXf(d, left))), tex, im, 70);
        return im;
    }
    /** Spielermodell (Kästen) mit der Waffe in der rechten Hand; Welt: Spieler blickt nach -z, Füße auf y=0 */
    static List<Quad> rig(List<Quad> item, double[] d) {
        List<Quad> out = new ArrayList<>();
        Xf body = Xf.chain(Xf.scale(-0.9375, -0.9375, 0.9375), Xf.move(0, -1.501, 0)), px = Xf.scale(1 / 16.0, 1 / 16.0, 1 / 16.0);
        Xf arm = Xf.chain(body, Xf.move(-5 / 16.0, 2 / 16.0, 0), Xf.rot(Rx(-18)));
        Xf armL = Xf.chain(body, Xf.move(5 / 16.0, 2 / 16.0, 0));
        box(out, Xf.chain(body, px), -4, -8, -4, 4, 0, 4, 0xB08A70);
        box(out, Xf.chain(body, px), -4, 0, -2, 4, 12, 2, 0x3C7A8C);
        box(out, Xf.chain(arm, px), -3, -2, -2, 1, 10, 2, 0x9A7860);
        box(out, Xf.chain(armL, px), -1, -2, -2, 3, 10, 2, 0x9A7860);
        box(out, Xf.chain(body, px), -3.9, 12, -2, 0.1, 24, 2, 0x34407A);
        box(out, Xf.chain(body, px), -0.1, 12, -2, 3.9, 24, 2, 0x2C3668);
        box(out, Xf.move(0, 0, 0), -8, -0.2, -8, 8, 0, 8, 0x4B4F58);
        out.addAll(place(item, Xf.chain(arm, Xf.rot(Rx(-90)), Xf.rot(Ry(180)), Xf.move(1 / 16.0, 0.125, -0.625), displayXf(d, false))));
        return out;
    }
    /** Varianten aus der System-Property; Kurzform (mit "p" davor) wird über [shortForm] in Display-Werte umgerechnet */
    static double[][] variants(String prop, double[] dflt, java.util.function.UnaryOperator<double[]> shortForm) {
        String s = System.getProperty(prop);
        if (s == null || s.isBlank()) return new double[][]{dflt};
        String[] parts = s.split(";");
        double[][] out = new double[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            boolean sf = p.startsWith("p");
            double[] v = Arrays.stream((sf ? p.substring(1) : p).trim().split(",")).mapToDouble(Double::parseDouble).toArray();
            out[i] = sf ? shortForm.apply(v) : v;
            if (sf) System.out.println("  " + prop + " " + p + " -> " + vec(out[i]));
        }
        return out;
    }
    static void handPreview(Path dir, String name, List<El> model, double[] fp, double[] tp, java.util.function.UnaryOperator<double[]> fpShort, java.util.function.UnaryOperator<double[]> tpShort) throws Exception {
        Files.createDirectories(dir);
        BufferedImage tex = atlas(model)[0];
        List<Quad> qs = quads(model);
        double[][] fps = variants(name + ".fp", fp, fpShort), tps = variants(name + ".tp", tp, tpShort);
        int W = 854, H = 480;
        BufferedImage sheet = new BufferedImage(W * 2 + 10, (H + 10) * fps.length, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        for (int i = 0; i < fps.length; i++) {
            g.drawImage(firstPerson(qs, tex, fps[i], false), 0, i * (H + 10), null);
            g.drawImage(firstPerson(qs, tex, leftOf(fps[i]), true), W + 10, i * (H + 10), null);
            g.setColor(Color.YELLOW); g.drawString(name + " fp " + vec(fps[i]), 6, i * (H + 10) + 14);
        }
        g.dispose();
        ImageIO.write(sheet, "png", dir.resolve(name + "_hand_1st.png").toFile());
        int P = 420;
        // Seite (rechts), vorn, F5 von hinten (4 Blöcke hinter den Augen, FOV 70)
        double[][][] cams = {{{7, 1.3, -0.6}, {0, 1.3, -0.6}}, {{-1.5, 1.5, -7}, {0, 1.2, 0}}, {{0, 1.62, 4}, {0, 1.62, 0}}};
        double[] fov = {30, 30, 70};
        sheet = new BufferedImage((P + 10) * 3, (P + 10) * tps.length, BufferedImage.TYPE_INT_ARGB);
        g = sheet.createGraphics();
        for (int i = 0; i < tps.length; i++) {
            List<Quad> world = rig(qs, tps[i]);
            for (int v = 0; v < 3; v++) {
                BufferedImage im = backdrop(P, P, false);
                renderPersp(place(world, lookAt(cams[v][0], cams[v][1])), tex, im, fov[v]);
                g.drawImage(im, v * (P + 10), i * (P + 10), null);
            }
            g.setColor(Color.YELLOW); g.drawString(name + " tp " + vec(tps[i]), 6, i * (P + 10) + 14);
        }
        g.dispose();
        ImageIO.write(sheet, "png", dir.resolve(name + "_hand_3rd.png").toFile());
        System.out.println("  Hand-Vorschau: " + dir.resolve(name + "_hand_1st.png") + ", " + name + "_hand_3rd.png");
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        String mode = args.length > 2 ? args[1] : "";
        Path dir = args.length > 2 ? Path.of(args[2]) : null;
        java.util.function.UnaryOperator<double[]> swordFp = v -> pose(v[0], v[1], v[2], new double[]{v[3], v[4], v[5]}, v[6], SWORD_GRIP);
        java.util.function.UnaryOperator<double[]> swordTp = v -> held(55, new double[]{0, 4, 0.5}, 0.85, SWORD_GRIP_VANILLA, new double[]{0, -90, v[0]}, v[1], new double[]{8, v[2], 8});
        java.util.function.UnaryOperator<double[]> maceFp = v -> pose(v[0], v[1], v[2], new double[]{v[3], v[4], v[5]}, v[6], MACE_GRIP);
        java.util.function.UnaryOperator<double[]> maceTp = v -> held(55, new double[]{0, 4, 1}, 1, MACE_GRIP_VANILLA, new double[]{0, -90, v[0]}, v[1], new double[]{8, v[2], 8});
        if (mode.equals("--hand")) {
            String only = System.getProperty("only", "");
            if (only.isEmpty() || only.equals("heavens_sword")) handPreview(dir, "heavens_sword", heavensSword(), HEAVEN_SWORD_FP, SWORD_TP, swordFp, swordTp);
            if (only.isEmpty() || only.equals("hells_sword")) handPreview(dir, "hells_sword", hellsSword(), HELL_SWORD_FP, SWORD_TP, swordFp, swordTp);
            if (only.isEmpty() || only.equals("heavens_mace")) handPreview(dir, "heavens_mace", heavensMace(), MACE_FP, HEAVEN_MACE_TP, maceFp, maceTp);
            if (only.isEmpty() || only.equals("hells_mace")) handPreview(dir, "hells_mace", hellsMace(), MACE_FP, MACE_TP, maceFp, maceTp);
            return;
        }
        Path preview = mode.equals("--preview") ? dir : null;
        build(root, preview, "heavens_sword", heavensSword(), display(SWORD_TP, HEAVEN_SWORD_FP), 25, 15, 3, 3, OUTLINE_HEAVEN, false, false);
        build(root, preview, "hells_sword", hellsSword(), display(SWORD_TP, HELL_SWORD_FP), 25, 15, 3, 3, OUTLINE_HELL, false, false);
        build(root, preview, "heavens_mace", heavensMace(), display(HEAVEN_MACE_TP, MACE_FP), 25, 18, 4, 4, OUTLINE_HEAVEN, true, true);
        build(root, preview, "hells_mace", hellsMace(), hellsMace(true), display(MACE_TP, MACE_FP), 25, 18, 4, 4, OUTLINE_HELL, true, false);
    }
}
