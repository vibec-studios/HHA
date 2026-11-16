import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/**
 * Heaven- und Hell-Rüstung (Überarbeitung 2, PLAN 9.3) im Stil der Schwester-Mods HHA Blood und HHA Void:
 * Vanilla-Netherit-Rüstung als Grundform, darüber Rüstungs-Trims, dazu eine leuchtende Glow-Ebene.
 * Vorlage: GenVoidArmour.java (HHA Void) bzw. GenTrimArmour.java (HHA Blood).
 *
 *   Heaven: Wolkenstahl. Die Netherit-Textur wird Pixel für Pixel nach ihrer Helligkeit auf die helle, kühle
 *           Rampe METAL_HEAVEN umgefärbt (Fugen schiefergrau, Plattenkanten fast weiß; die Plattenstruktur
 *           bleibt lesbar). Trims in Gold (TRIM_HEAVEN), die Glow-Ebene leuchtet warmweiß.
 *           Muster: Helm sentry, Brust spire, Hose flow, Schuhe wayfinder.
 *   Hell:   Schwarzstahl = unverändertes Vanilla-Netherit (wie Blood). Trims in Seelenfeuer (TRIM_HELL),
 *           Glow türkis. Muster: Helm silence, Brust rib, Hose ward, Schuhe dune.
 *
 * Trims werden genau so zusammengesetzt, wie Minecraft sie einfärbt (Paletten-Permutation des Graustufen-Musters
 * über trims/color_palettes/trim_palette.png). Helm, Brust und Schuhe teilen sich EINE Ebene (humanoid), deshalb
 * wird das Muster je UV-Bereich gewählt: Kopf/Hut -> Helm, Körper/Arme -> Brust, Beine -> Schuhe. Die
 * Hosen-Ebene (humanoid_leggings) ist komplett das Hosen-Muster.
 *
 * Die *_glow-Ebenen enthalten nur die hellen Trim-Stufen. Der Client zeichnet sie lichtunabhängig und pulsierend
 * (mixin/client/EquipmentRendererMixin.java, equipment/heaven.json bzw. hell.json; Puls 3,2 s, 75–100 %).
 *
 * Icons: 16x16, Netherit-Silhouette, Metall in vier Stufen plus dunkler Umriss von 1 Texel, Trim-Akzent aus dem
 * Vanilla-Item-Trim. 4 Bilder (interpoliert, je 16 Ticks = 3,2 s je Atemzug): der Trim atmet hell und wieder dunkel.
 *
 * Schreibt (unter src/main/resources/assets/hha/textures):
 *   entity/equipment/humanoid/<set>.png + <set>_glow.png
 *   entity/equipment/humanoid_leggings/<set>.png + <set>_glow.png
 *   item/<set>_{helmet,chestplate,leggings,boots}.png (+ .mcmeta)
 *
 * Aufruf (im Projektordner):
 *   java tools/textures/GenHeavenHellArmour.java .                      Texturen schreiben
 *   java tools/textures/GenHeavenHellArmour.java . --preview <dir>      nur Vorschau: <dir>/puppe_heaven.png, puppe_hell.png
 * Optional letztes Argument: Pfad zur minecraft-client.jar
 * (Standard ~/.gradle/caches/fabric-loom/1.21.11/minecraft-client.jar).
 */
public class GenHeavenHellArmour {

    // ------------------------------------------------------------------ Stellschrauben

    /** Wolkenstahl, seit 16.11. auf Wunsch des Nutzers deutlich heller (dunkelste Stufe nur noch Mittelgrau) */
    static final int[] METAL_HEAVEN = {0x5C6676, 0x78828F, 0x96A1AE, 0xB4BEC9, 0xCFD7E0, 0xE4E9EF, 0xF4F7FA, 0xFFFFFF};
    /** Helligkeitskurve des Wolkenstahls (< 1 = heller); die Fugen bleiben dunkel genug, damit die Platten lesbar sind */
    static final double METAL_GAMMA = 0.72;
    /** Icon-Metall Heaven: vier Stufen + Umriss */
    static final int[] ICON_HEAVEN = {0x8E99A8, 0xBFC9D4, 0xE7EDF3, 0xFFFFFF};
    static final int OUTLINE_HEAVEN = 0x3A4352;

    /** Trim Gold (PLAN 9.3: #b06a16 -> #ffc23c -> #fff4c2), dunkel ergänzt */
    static final int[] TRIM_HEAVEN = {0x5C300A, 0x864A10, 0xB06A16, 0xD9921F, 0xFFC23C, 0xFFDE85, 0xFFF4C2};
    /** Trim Seelenfeuer (PLAN 9.3: #0b7690 -> #3cd6ef -> #d8fdff), dunkel ergänzt */
    static final int[] TRIM_HELL = {0x05303D, 0x07506A, 0x0B7690, 0x1EA8C8, 0x3CD6EF, 0x8EEEFB, 0xD8FDFF};
    /** Glow: warmweiß (Heaven) bzw. türkis (Hell), von der unteren bis zur hellsten Glow-Stufe */
    static final int[] GLOW_HEAVEN = {0xFFD27A, 0xFFE9B4, 0xFFF8E6};
    static final int[] GLOW_HELL = {0x2CC6E6, 0x7EEBFB, 0xE2FEFF};
    /** nur Trim-Stufen ab diesem Anteil (0 dunkelste .. 1 hellste) leuchten; darunter bleibt der beleuchtete Trim */
    static final double GLOW_FROM = 0.5;
    /**
     * Die Vanilla-Muster benutzen fast nur die oberen Graustufen (128..224 von 0..224). Diese Kurve spreizt sie
     * über die ganze Rampe (128 -> dunkles Gold, 224 -> hellstes), sonst wäre der Trim fast einfarbig hell.
     */
    static final double TRIM_CURVE = 1.8;
    /** Trim-Helligkeit der vier Icon-Bilder (Atemzug) */
    static final double[] ICON_BREATH = {0.0, 0.12, 0.25, 0.12};

    record Set(String name, String helmet, String chest, String legs, String boots, int[] metal, int[] trim, int[] glow) {}

    static final Set HEAVEN = new Set("heaven", "sentry", "spire", "flow", "wayfinder", METAL_HEAVEN, TRIM_HEAVEN, GLOW_HEAVEN);
    static final Set HELL = new Set("hell", "silence", "rib", "ward", "dune", null, TRIM_HELL, GLOW_HELL);
    static final Set[] SETS = {HEAVEN, HELL};

    static ZipFile jar;
    static Path tex;
    /** Graustufen-Schlüssel der Trim-Muster und ihre Stufe t (0 dunkelste .. 1 hellste) */
    static int[] key;
    static double[] keyT;

    static BufferedImage vanilla(String path) throws Exception {
        var e = jar.getEntry("assets/minecraft/textures/" + path);
        if (e == null) throw new IllegalStateException("fehlt in der jar: " + path);
        try (InputStream in = jar.getInputStream(e)) {
            return raw(ImageIO.read(in));
        }
    }

    /**
     * Rohe Pixelwerte, so wie Minecrafts NativeImage sie liest. Javas getRGB()/drawImage() wenden auf
     * Graustufen-PNGs eine Linear->sRGB-Umrechnung an, die den exakten Paletten-Abgleich zerstören würde.
     */
    static BufferedImage raw(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var r = src.getRaster();
        int bands = r.getNumBands();
        boolean indexed = src.getColorModel() instanceof java.awt.image.IndexColorModel;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int argb;
            if (indexed) argb = src.getRGB(x, y);
            else if (bands == 1) { int g = r.getSample(x, y, 0); argb = 0xFF000000 | (g << 16) | (g << 8) | g; }
            else if (bands == 2) { int g = r.getSample(x, y, 0), a = r.getSample(x, y, 1); argb = (a << 24) | (g << 16) | (g << 8) | g; }
            else if (bands == 3) argb = 0xFF000000 | (r.getSample(x, y, 0) << 16) | (r.getSample(x, y, 1) << 8) | r.getSample(x, y, 2);
            else argb = (r.getSample(x, y, 3) << 24) | (r.getSample(x, y, 0) << 16) | (r.getSample(x, y, 1) << 8) | r.getSample(x, y, 2);
            out.setRGB(x, y, argb);
        }
        return out;
    }

    static double lum(int c) { return (((c >> 16) & 255) * 0.299 + ((c >> 8) & 255) * 0.587 + (c & 255) * 0.114) / 255.0; }

    /** Farbe an Stelle t (0..1) einer Rampe, linear zwischen den Stützfarben */
    static int ramp(int[] pal, double t) {
        double f = Math.max(0, Math.min(1, t)) * (pal.length - 1);
        int i = Math.min(pal.length - 2, (int) f);
        double k = f - i;
        int a = pal[i], b = pal[i + 1];
        int r = (int) Math.round(((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k);
        int bl = (int) Math.round((a & 255) * (1 - k) + (b & 255) * k);
        return (r << 16) | (g << 8) | bl;
    }

    // ------------------------------------------------------------------ Paletten der Trims

    /** Trim-Palette (8 Einträge passend zu [key]); [lift] hebt alle Stufen an (Atemzug der Icons) */
    static int[] trimPalette(Set s, double lift) {
        int[] p = new int[key.length];
        for (int i = 0; i < key.length; i++) p[i] = 0xFF000000 | ramp(s.trim(), keyT[i] * (1 - lift) + lift);
        return p;
    }

    /** Glow-Palette: dunkle Stufen durchsichtig (dort bleibt der beleuchtete Trim und gibt den Linien Tiefe) */
    static int[] glowPalette(Set s) {
        int[] p = new int[key.length];
        for (int i = 0; i < key.length; i++) {
            if (keyT[i] < GLOW_FROM) { p[i] = 0; continue; }
            p[i] = 0xFF000000 | ramp(s.glow(), (keyT[i] - GLOW_FROM) / (1 - GLOW_FROM));
        }
        return p;
    }

    /** Paletten-Permutation: jedes Pixel, dessen RGB key[i] entspricht, wird pal[i]; pal[i] mit Alpha 0 entfällt. */
    static BufferedImage recolor(BufferedImage pattern, int[] pal) {
        BufferedImage out = new BufferedImage(pattern.getWidth(), pattern.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < pattern.getHeight(); y++) for (int x = 0; x < pattern.getWidth(); x++) {
            int c = pattern.getRGB(x, y);
            int a = c >>> 24;
            if (a == 0) continue;
            int rgb = c & 0xFFFFFF;
            int idx = -1;
            for (int i = 0; i < key.length; i++) if (key[i] == rgb) { idx = i; break; }
            if (idx < 0) {                                   // kein Schlüssel: nächste Stufe nach Helligkeit
                double best = 9;
                for (int i = 0; i < key.length; i++) { double d = Math.abs(lum(key[i]) - lum(rgb)); if (d < best) { best = d; idx = i; } }
            }
            int m = pal[idx];
            if ((m >>> 24) == 0) continue;
            out.setRGB(x, y, (a << 24) | (m & 0xFFFFFF));
        }
        return out;
    }

    // ------------------------------------------------------------------ Metall

    /** Netherit -> Set-Metall nach Helligkeit (Heaven); Hell (metal == null) bleibt Vanilla-Netherit */
    static BufferedImage metal(BufferedImage base, Set s) {
        int w = base.getWidth(), h = base.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        if (s.metal() == null) { out.getGraphics().drawImage(base, 0, 0, null); return out; }
        double lo = 1, hi = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int c = base.getRGB(x, y);
            if ((c >>> 24) == 0) continue;
            lo = Math.min(lo, lum(c)); hi = Math.max(hi, lum(c));
        }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int c = base.getRGB(x, y);
            if ((c >>> 24) == 0) continue;
            double t = Math.pow((lum(c) - lo) / Math.max(1e-6, hi - lo), METAL_GAMMA);
            out.setRGB(x, y, (c & 0xFF000000) | ramp(s.metal(), t));
        }
        return out;
    }

    static int over(int dst, int src) {
        int sa = src >>> 24;
        if (sa == 0) return dst;
        if (sa == 255) return src;
        int da = dst >>> 24;
        double a = sa / 255.0, b = da / 255.0 * (1 - a);
        double oa = a + b;
        int r = (int) Math.round((((src >> 16) & 255) * a + ((dst >> 16) & 255) * b) / oa);
        int g = (int) Math.round((((src >> 8) & 255) * a + ((dst >> 8) & 255) * b) / oa);
        int bl = (int) Math.round(((src & 255) * a + (dst & 255) * b) / oa);
        return ((int) Math.round(oa * 255) << 24) | (r << 16) | (g << 8) | bl;
    }

    /** base + trim, aber nur innerhalb des UV-Rechtecks. */
    static void overlay(BufferedImage base, BufferedImage trim, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) base.setRGB(x, y, over(base.getRGB(x, y), trim.getRGB(x, y)));
    }

    static void write(BufferedImage img, String rel) throws Exception {
        File f = tex.resolve(rel).toFile();
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
        System.out.println("geschrieben: " + rel);
    }

    /** [0]=humanoid, [1]=humanoid glow, [2]=leggings, [3]=leggings glow */
    static BufferedImage[] layers(Set s) throws Exception {
        int[] pal = trimPalette(s, 0), glow = glowPalette(s);
        BufferedImage hum = metal(vanilla("entity/equipment/humanoid/netherite.png"), s);
        BufferedImage helmetPat = vanilla("trims/entity/humanoid/" + s.helmet() + ".png");
        BufferedImage chestPat = vanilla("trims/entity/humanoid/" + s.chest() + ".png");
        BufferedImage bootsPat = vanilla("trims/entity/humanoid/" + s.boots() + ".png");
        overlay(hum, recolor(helmetPat, pal), 0, 0, 64, 16);     // Kopf + Hut
        overlay(hum, recolor(bootsPat, pal), 0, 16, 16, 32);     // Beine (Schuhe)
        overlay(hum, recolor(chestPat, pal), 16, 16, 64, 32);    // Körper + Arme
        BufferedImage humGlow = new BufferedImage(hum.getWidth(), hum.getHeight(), BufferedImage.TYPE_INT_ARGB);
        overlay(humGlow, recolor(helmetPat, glow), 0, 0, 64, 16);
        overlay(humGlow, recolor(bootsPat, glow), 0, 16, 16, 32);
        overlay(humGlow, recolor(chestPat, glow), 16, 16, 64, 32);

        BufferedImage leg = metal(vanilla("entity/equipment/humanoid_leggings/netherite.png"), s);
        BufferedImage legPat = vanilla("trims/entity/humanoid_leggings/" + s.legs() + ".png");
        overlay(leg, recolor(legPat, pal), 0, 0, leg.getWidth(), leg.getHeight());
        BufferedImage legGlow = new BufferedImage(leg.getWidth(), leg.getHeight(), BufferedImage.TYPE_INT_ARGB);
        overlay(legGlow, recolor(legPat, glow), 0, 0, leg.getWidth(), leg.getHeight());
        return new BufferedImage[]{hum, humGlow, leg, legGlow};
    }

    // ------------------------------------------------------------------ Icons

    static boolean opaque(BufferedImage im, int x, int y) {
        return x >= 0 && y >= 0 && x < im.getWidth() && y < im.getHeight() && (im.getRGB(x, y) >>> 24) != 0;
    }

    /**
     * Icon-Metall: Heaven = vier Stufen Wolkenstahl nach der Helligkeit des Netherit-Icons, Randtexel (an
     * Durchsichtiges grenzend) werden zum dunklen Umriss. Hell = Vanilla-Netherit-Icon (hat schon seinen Umriss).
     */
    static BufferedImage iconMetal(BufferedImage net, Set s) {
        BufferedImage out = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        if (s.metal() == null) { out.getGraphics().drawImage(net, 0, 0, null); return out; }
        double lo = 1, hi = 0;
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            if (!opaque(net, x, y) || edge(net, x, y)) continue;
            double l = lum(net.getRGB(x, y)); lo = Math.min(lo, l); hi = Math.max(hi, l);
        }
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            if (!opaque(net, x, y)) continue;
            if (edge(net, x, y)) { out.setRGB(x, y, 0xFF000000 | OUTLINE_HEAVEN); continue; }
            double t = (lum(net.getRGB(x, y)) - lo) / Math.max(1e-6, hi - lo);
            int i = Math.min(ICON_HEAVEN.length - 1, (int) Math.floor(Math.pow(t, 0.8) * ICON_HEAVEN.length));
            out.setRGB(x, y, 0xFF000000 | ICON_HEAVEN[i]);
        }
        return out;
    }
    static boolean edge(BufferedImage im, int x, int y) {
        return !opaque(im, x - 1, y) || !opaque(im, x + 1, y) || !opaque(im, x, y - 1) || !opaque(im, x, y + 1);
    }

    static BufferedImage icon(String piece, Set s) throws Exception {
        BufferedImage metal = iconMetal(vanilla("item/netherite_" + piece + ".png"), s);
        BufferedImage trim = vanilla("trims/items/" + piece + "_trim.png");
        BufferedImage strip = new BufferedImage(16, 16 * ICON_BREATH.length, BufferedImage.TYPE_INT_ARGB);
        for (int f = 0; f < ICON_BREATH.length; f++) {
            BufferedImage frame = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            frame.getGraphics().drawImage(metal, 0, 0, null);
            overlay(frame, recolor(trim, trimPalette(s, ICON_BREATH[f])), 0, 0, 16, 16);
            strip.getGraphics().drawImage(frame, 0, f * 16, null);
        }
        return strip;
    }

    // ------------------------------------------------------------------ Hauptprogramm

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        tex = root.resolve("src/main/resources/assets/hha/textures");
        String preview = null;
        String jarPath = System.getProperty("user.home") + "/.gradle/caches/fabric-loom/1.21.11/minecraft-client.jar";
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--preview")) preview = args[++i];
            else jarPath = args[i];
        }
        jar = new ZipFile(jarPath);

        BufferedImage keyImg = vanilla("trims/color_palettes/trim_palette.png");
        key = new int[keyImg.getWidth()];
        keyT = new double[key.length];
        double lo = 1, hi = 0;
        for (int i = 0; i < key.length; i++) { key[i] = keyImg.getRGB(i, 0) & 0xFFFFFF; lo = Math.min(lo, lum(key[i])); hi = Math.max(hi, lum(key[i])); }
        for (int i = 0; i < key.length; i++) keyT[i] = Math.pow((lum(key[i]) - lo) / (hi - lo), TRIM_CURVE);

        for (Set s : SETS) {
            BufferedImage[] l = layers(s);
            if (preview != null) {
                Files.createDirectories(Path.of(preview));
                preview(Path.of(preview, "puppe_" + s.name() + ".png").toString(), s, l);
                continue;
            }
            write(l[0], "entity/equipment/humanoid/" + s.name() + ".png");
            write(l[1], "entity/equipment/humanoid/" + s.name() + "_glow.png");
            write(l[2], "entity/equipment/humanoid_leggings/" + s.name() + ".png");
            write(l[3], "entity/equipment/humanoid_leggings/" + s.name() + "_glow.png");
            for (String piece : new String[]{"helmet", "chestplate", "leggings", "boots"}) {
                write(icon(piece, s), "item/" + s.name() + "_" + piece + ".png");
                Files.writeString(tex.resolve("item/" + s.name() + "_" + piece + ".png.mcmeta"),
                    "{\"animation\":{\"frametime\":16,\"interpolate\":true,\"frames\":[0,1,2,3]}}\n");
            }
        }
        jar.close();
    }

    // ------------------------------------------------------------------ Vorschau

    static void blit(BufferedImage dst, int dx, int dy, BufferedImage src, int sx, int sy, int w, int h, boolean mirror) {
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int c = src.getRGB(sx + (mirror ? w - 1 - x : x), sy + y);
            if ((c >>> 24) == 0) continue;
            dst.setRGB(dx + x, dy + y, over(dst.getRGB(dx + x, dy + y), c));
        }
    }

    /** Papierpuppe 16x32: front=true Vorderseite, sonst Rückseite. dark = Nacht (Basis abgedunkelt, Glow voll). */
    static BufferedImage doll(BufferedImage[] l, boolean front, boolean dark) {
        BufferedImage d = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = d.createGraphics();
        g.setColor(new Color(0x8A6A55));
        g.fillRect(4, 0, 8, 8); g.fillRect(0, 8, 16, 12); g.fillRect(4, 20, 8, 12);
        g.dispose();
        for (int pass = 0; pass < 2; pass++) {
            BufferedImage hum = l[pass], leg = l[2 + pass];
            int hx = front ? 8 : 24, bx = front ? 20 : 32, ax = front ? 44 : 52, lx = front ? 4 : 12;
            blit(d, 4, 8, leg, bx, 20, 8, 12, false);                         // Hose: Körper + Beine
            blit(d, front ? 4 : 8, 20, leg, lx, 20, 4, 12, false);
            blit(d, front ? 8 : 4, 20, leg, lx, 20, 4, 12, true);
            blit(d, front ? 4 : 8, 20, hum, lx, 20, 4, 12, false);            // Schuhe
            blit(d, front ? 8 : 4, 20, hum, lx, 20, 4, 12, true);
            blit(d, 4, 8, hum, bx, 20, 8, 12, false);                         // Brust + Arme
            blit(d, front ? 0 : 12, 8, hum, ax, 20, 4, 12, false);
            blit(d, front ? 12 : 0, 8, hum, ax, 20, 4, 12, true);
            blit(d, 4, 0, hum, hx, 8, 8, 8, false);                           // Helm
            if (pass == 0 && dark) {
                for (int y = 0; y < 32; y++) for (int x = 0; x < 16; x++) {
                    int c = d.getRGB(x, y);
                    if ((c >>> 24) == 0) continue;
                    int r = (int) (((c >> 16) & 255) * 0.22), gg = (int) (((c >> 8) & 255) * 0.22), b = (int) ((c & 255) * 0.3);
                    d.setRGB(x, y, 0xFF000000 | (r << 16) | (gg << 8) | b);
                }
            }
        }
        return d;
    }

    static void big(Graphics2D g, BufferedImage im, int sx, int sy, int w, int h, int x0, int y0, int sc) {
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int c = im.getRGB(sx + x, sy + y);
            if ((c >>> 24) == 0) continue;
            g.setColor(new Color(c, true));
            g.fillRect(x0 + x * sc, y0 + y * sc, sc, sc);
        }
    }

    /** Puppe vorn/hinten bei Tag und Nacht, darunter Ebenen-Texturen und die vier Icons in allen Bildern */
    static void preview(String out, Set s, BufferedImage[] l) throws Exception {
        int S = 10, cellW = 16 * S + 14;
        int W = 4 * cellW + 30, H = 32 * S + 40 + 32 * 5 + 20 + 16 * 5 * 4 + 40;
        BufferedImage img = new BufferedImage(Math.max(W, 4 * 64 * 5 / 2 + 60), H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x5A6A78)); g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.WHITE);
        g.drawString(s.name() + ": Helm " + s.helmet() + " | Brust " + s.chest() + " | Hose " + s.legs() + " | Schuhe " + s.boots()
            + "   (Tag vorn/hinten, Nacht vorn/hinten)", 10, 16);
        BufferedImage[] dolls = {doll(l, true, false), doll(l, false, false), doll(l, true, true), doll(l, false, true)};
        int oy = 26;
        for (int k = 0; k < 4; k++) {
            int x0 = 10 + k * cellW;
            if (k >= 2) { g.setColor(new Color(0x101018)); g.fillRect(x0 - 4, oy - 2, 16 * S + 8, 32 * S + 4); }
            big(g, dolls[k], 0, 0, 16, 32, x0, oy, S);
        }
        oy += 32 * S + 14;
        for (int k = 0; k < 4; k++) {
            int x0 = 10 + k * (64 * 5 / 2 + 12);
            g.setColor(k % 2 == 1 ? new Color(0x101018) : new Color(0x485662));
            g.fillRect(x0 - 2, oy - 2, 64 * 5 / 2 + 4, 32 * 5 / 2 + 4);
            BufferedImage im = l[k];
            for (int y = 0; y < im.getHeight(); y++) for (int x = 0; x < im.getWidth(); x++) {
                int c = im.getRGB(x, y); if ((c >>> 24) == 0) continue;
                g.setColor(new Color(c, true)); g.fillRect(x0 + x * 5 / 2, oy + y * 5 / 2, 3, 3);
            }
        }
        oy += 32 * 5 / 2 + 16;
        String[] pieces = {"helmet", "chestplate", "leggings", "boots"};
        for (int p = 0; p < 4; p++) {
            BufferedImage strip = icon(pieces[p], s);
            for (int f = 0; f < ICON_BREATH.length; f++) big(g, strip, 0, f * 16, 16, 16, 10 + f * 90, oy + p * 84, 5);
        }
        g.dispose();
        ImageIO.write(img, "png", new File(out));
        System.out.println("Vorschau: " + out);
    }
}
