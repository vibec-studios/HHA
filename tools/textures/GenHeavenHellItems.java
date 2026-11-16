import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Die übrigen 16x16-Items von Heaven und Hell (Überarbeitung 2, PLAN 9.3) im Stil der Schwester-Mods:
 * schattierte Pixel-Art mit dunklem Umriss, dieselben Rampen wie Rüstung und Waffen.
 *
 *   heaven_ingot / hell_ingot   Barrenform wie Netherit/Blood/Void-Barren. Heaven: Wolkenstahl mit Goldeinlage,
 *                               Hell: Schwarzstahl mit Seelenfeuer-Rissen. 4 Bilder: Ruhe, Glanzlicht wandert über
 *                               den Barren, Einlage glimmt auf, Funkeln.
 *   hermes_feather              weiße Feder mit Goldkiel, Fahne mit Kerben
 *   hades_map                   der Schwarze Brief: schwarzes Kuvert mit grünem Wachssiegel
 *   hermes_summons              der Botenbrief: helles Kuvert mit Goldsiegel und kleinen Flügeln
 *   jotunheims_upgrade_template Vanilla-Schmiedevorlage, Rahmen in kaltem Schieferblau, das Emblem halb Gold
 *                               (Heaven) halb Seelenfeuer (Hell): sie nimmt beide Barren
 *   heaven/hell_banner_pattern  Vanilla-Schriftrolle mit Goldstern bzw. Seelenflamme
 *   entity/banner|shield/heaven_star, hell_flame   Bannermuster (weiße Maske mit Graustufen wie Vanilla)
 *
 * Aufruf (im Projektordner):
 *   java tools/textures/GenHeavenHellItems.java .                    alles schreiben
 *   java tools/textures/GenHeavenHellItems.java . --preview <dir>    nur <dir>/items_sheet.png
 * Optional letztes Argument: Pfad zur minecraft-client.jar (nur für die Schmiedevorlage).
 */
public class GenHeavenHellItems {

    // ------------------------------------------------------------------ Paletten (wie Rüstung/Waffen)
    static final int[] HST = {0x46505D, 0x5E6B7A, 0x7A8696, 0x9AA6B5, 0xB8C3CF, 0xD6DEE7, 0xF2F6FA};
    static final int[] GOLD = {0x5C300A, 0x864A10, 0xB06A16, 0xD9921F, 0xFFC23C, 0xFFDE85, 0xFFF4C2, 0xFFFFFF};
    static final int[] BST = {0x1F1D24, 0x2A2730, 0x37333E, 0x48434F, 0x5D5766, 0x77707F, 0x908A98};
    static final int[] SOUL = {0x032A36, 0x064C5E, 0x0B7690, 0x1EA8C8, 0x3CD6EF, 0x8EEEFB, 0xD8FDFF, 0xFFFFFF};
    static final int[] FEATHER = {0x6F7A8A, 0x97A2B1, 0xBCC5D1, 0xDCE2EA, 0xF2F5F9, 0xFFFFFF};
    static final int OUT_HEAVEN = 0x232934, OUT_HELL = 0x07080B;

    static Path tex;

    static int[][] grid() { return new int[16][16]; }
    static BufferedImage img(int[][] g) {
        BufferedImage im = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) if (g[y][x] != 0) im.setRGB(x, y, g[y][x]);
        return im;
    }
    static int opaque(int rgb) { return 0xFF000000 | rgb; }
    static int pick(int[] pal, double i) { return pal[Math.max(0, Math.min(pal.length - 1, (int) Math.floor(i)))]; }
    /** 1-px-Umriss um alles Gefüllte (auch diagonal), nur auf leere Pixel */
    static void outline(int[][] g, int col, boolean diagonal) {
        boolean[][] add = new boolean[16][16];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            if (g[y][x] != 0) continue;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                if ((dx == 0 && dy == 0) || (!diagonal && dx != 0 && dy != 0)) continue;
                int nx = x + dx, ny = y + dy;
                if (nx >= 0 && ny >= 0 && nx < 16 && ny < 16 && g[ny][nx] != 0 && g[ny][nx] != opaque(col)) add[y][x] = true;
            }
        }
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) if (add[y][x]) g[y][x] = opaque(col);
    }
    static double hash(int a, int b, int c) {
        int h = a * 374761393 + b * 668265263 + c * 2147483647 + 0x9E3779B9;
        h = (h ^ (h >>> 13)) * 1274126177; h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    // ------------------------------------------------------------------ Barren
    /** K Umriss, 1-7 Metall, a-h Akzent (Gold bzw. Seelenfeuer); gleiche Form wie Netherit-/Blood-/Void-Barren */
    static final String[] INGOT_HEAVEN = {
        "................",
        "................",
        "................",
        "....KKKKKKKKKKK.",
        "...K7776666554K.",
        "..K766cefec665K.",
        ".K66cefggfec654K",
        "K7777766655K544K",
        "K6655555544K443K",
        "K5e5444e443K33K.",
        "K54ee4444e3K2K..",
        "K4443333e33KK...",
        "KKKKKKKKKKKK....",
        "................",
        "................",
        "................",
    };
    static final String[] INGOT_HELL = {
        "................",
        "................",
        "................",
        "....KKKKKKKKKKK.",
        "...K6665555443K.",
        "..K65c6556dc54K.",
        ".K5dc55e5554cd4K",
        "K776665d544K2c3K",
        "K55444d4433K2b2K",
        "K43c333d332K12K.",
        "K43cd3333c2K1K..",
        "K3332222b22KK...",
        "KKKKKKKKKKKK....",
        "................",
        "................",
        "................",
    };
    static final String RAMP = "abcdefgh", METAL = "1234567";

    /** Bild f (0..3): 0 Ruhe, 1-2 Glanzlicht wandert diagonal über den Barren, Akzent glimmt auf, 3 Funkeln */
    static BufferedImage ingot(String[] map, int[] metal, int[] accent, int outline, int f) {
        int[][] g = grid();
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            char ch = map[y].charAt(x);
            if (ch == '.') continue;
            if (ch == 'K') { g[y][x] = opaque(outline); continue; }
            int band = x + y;                                                   // Diagonale des Glanzlichts
            int glint = f == 1 ? (band >= 9 && band <= 10 ? 2 : band == 8 || band == 11 ? 1 : 0)
                : f == 2 ? (band >= 15 && band <= 16 ? 2 : band == 14 || band == 17 ? 1 : 0) : 0;
            int mi = METAL.indexOf(ch), ai = RAMP.indexOf(ch);
            if (mi >= 0) g[y][x] = opaque(metal[Math.min(metal.length - 1, mi + glint)]);
            else g[y][x] = opaque(accent[Math.min(accent.length - 1, ai + glint + (f == 2 ? 1 : 0))]);
        }
        if (f == 3) {                                                           // Funkeln auf der Oberseite
            g[5][10] = opaque(accent[7]); g[4][10] = opaque(accent[5]); g[6][10] = opaque(accent[5]);
            g[5][9] = opaque(accent[5]); g[5][11] = opaque(accent[5]);
        }
        return img(g);
    }
    static final String INGOT_MCMETA = "{\"animation\":{\"interpolate\":true,\"frames\":[{\"index\":0,\"time\":40},{\"index\":1,\"time\":3},{\"index\":2,\"time\":3},{\"index\":3,\"time\":6}]}}\n";

    // ------------------------------------------------------------------ Feder
    /** Feder auf der Diagonale: Kiel von unten links (2,14) nach oben rechts (13,1) */
    static BufferedImage feather() {
        int[][] g = grid();
        double ax = 2.2, ay = 13.8, bx = 13.4, by = 1.2;
        double lx = bx - ax, ly = by - ay, len = Math.hypot(lx, ly);
        double ux = lx / len, uy = ly / len, nx = -uy, ny = ux;              // n zeigt nach oben links
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            double px = x + 0.5 - ax, py = y + 0.5 - ay;
            double t = (px * ux + py * uy) / len, d = px * nx + py * ny;
            if (t < 0 || t > 1.02) continue;
            double vane = t < 0.22 ? 0 : 4.1 * Math.pow(Math.sin(Math.PI * Math.min(1, (t - 0.22) / 0.82)), 0.7) * (d > 0 ? 1.0 : 0.82);
            boolean quill = Math.abs(d) < 0.55;
            if (!quill && Math.abs(d) >= vane) continue;
            if (!quill && Math.abs(d) > vane * 0.45 && Math.floorMod((int) Math.floor(t * 17 + Math.abs(d) * 0.9), 5) == 0) continue; // Kerben
            if (quill) g[y][x] = opaque(t < 0.3 ? GOLD[2] : t < 0.6 ? GOLD[4] : GOLD[5]);
            else {
                double k = Math.abs(d) / Math.max(0.5, vane);
                double idx = (d > 0 ? 4.1 : 2.9) - k * 1.6 + t * 0.8;
                g[y][x] = opaque(pick(FEATHER, idx));
            }
        }
        outline(g, OUT_HEAVEN, false);
        return img(g);
    }

    // ------------------------------------------------------------------ Briefe
    /**
     * Kuvert 14x10 mit Klappe (V) und Wachssiegel. body = {Schatten, Fläche, hell, Kante}, seal = Siegelrampe,
     * wings = kleine Flügel am Siegel (Botenbrief).
     */
    static BufferedImage letter(int[] body, int[] seal, double sealBase, int outline, boolean wings) {
        int[][] g = grid();
        int x0 = 1, x1 = 14, y0 = 3, y1 = 12;
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            int c = body[1];
            if (y == y0) c = body[3];                                         // obere Kante (Licht)
            if (y == y1 || x == x1) c = body[0];                              // Schatten unten/rechts
            // Klappe: V von den oberen Ecken zur Mitte; Fläche darüber eine Spur heller
            double vx = Math.abs(x + 0.5 - 8.0), vy = y + 0.5 - y0;
            double edge = 4.6 - vx * 0.62;                                    // Höhe der Klappenkante bei vx
            if (vy < edge - 0.5 && y > y0) c = body[2];
            if (Math.abs(vy - edge) < 0.55 && y > y0) c = body[0];
            // Seitenfalz unten (Λ von den unteren Ecken)
            double fold = (y1 - y) - (vx - 1.5) * 0.55;
            if (Math.abs(fold) < 0.5 && y < y1 && x > x0 && x < x1 && vx > 3) c = mix(body[1], body[0], 0.5);
            g[y][x] = opaque(c);
        }
        // Wachssiegel unter der Klappenspitze
        double sx = 8.0, sy = 8.6;
        for (int y = 5; y <= 11; y++) for (int x = 5; x <= 10; x++) {
            double r = Math.hypot(x + 0.5 - sx, y + 0.5 - sy);
            if (r > 2.3) continue;
            double light = (sx - (x + 0.5)) * 0.35 + (sy - (y + 0.5)) * 0.45;
            g[y][x] = opaque(pick(seal, sealBase + light + (r < 1.0 ? 0.6 : 0) - (r > 1.7 ? 0.9 : 0)));
        }
        g[7][7] = opaque(seal[seal.length - 1]);                               // Glanzpunkt
        if (wings) {                                                          // kleine Flügel links/rechts am Siegel
            int[][] wl = {{4, 7}, {5, 7}, {3, 8}, {4, 8}, {4, 9}};
            for (int[] p : wl) { g[p[1]][p[0]] = opaque(FEATHER[p[1] == 7 ? 5 : 3]); g[p[1]][15 - p[0]] = opaque(FEATHER[p[1] == 7 ? 5 : 3]); }
        }
        outline(g, outline, false);
        return img(g);
    }
    static int mix(int a, int b, double k) {
        int r = (int) Math.round(((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k);
        int gg = (int) Math.round(((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k);
        int bl = (int) Math.round((a & 255) * (1 - k) + (b & 255) * k);
        return (r << 16) | (gg << 8) | bl;
    }

    // ------------------------------------------------------------------ Schmiedevorlage
    static BufferedImage template(ZipFile jar) throws Exception {
        BufferedImage v;
        try (InputStream in = jar.getInputStream(jar.getEntry("assets/minecraft/textures/item/netherite_upgrade_smithing_template.png"))) { v = ImageIO.read(in); }
        int[] slate = {0x141A24, 0x1F2836, 0x2D3A4E, 0x3E4F68, 0x556A86, 0x6F86A3};
        int[][] g = grid();
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            int c = v.getRGB(x, y);
            if ((c >>> 24) == 0) continue;
            float[] hsb = Color.RGBtoHSB((c >> 16) & 255, (c >> 8) & 255, c & 255, null);
            boolean emblem = hsb[0] > 0.4f && hsb[0] < 0.6f && hsb[1] > 0.4f;
            double l = hsb[2];
            if (emblem) g[y][x] = opaque(x < 8 ? pick(GOLD, 1.2 + l * 5.0) : pick(SOUL, 0.8 + l * 5.0));
            else g[y][x] = opaque(pick(slate, (l - 0.18) / 0.36 * 5.0));
        }
        return img(g);
    }

    // ------------------------------------------------------------------ Bannermuster
    /** Vanilla-Schriftrolle der Bannermuster, Symbol entfernt (aus globe_banner_pattern) */
    static final String[] SCROLL = {
        "................",
        "...AAAAAAAAAA...",
        "..BCCCCCCCCDDB..",
        ".EBCCCCCCCCCDBE.",
        ".FEADDDDDDDDAEF.",
        "..FBBBAAAAABBF..",
        "...GDCCCCCDDG...",
        "...GCCCCCCCDG...",
        "..GACCCCCCCDG...",
        "..GDCCCCCCCCB...",
        "..GDCCCCCCCCAG..",
        "..GDCCCCCCCCDG..",
        "..GDCCCCCCCCDG..",
        "..GCCCCCCCCCDG..",
        "..GGGGGGGGGGGG..",
        "................",
    };
    static final Map<Character, Integer> SCROLL_PAL = Map.of('A', 0xB2A684, 'B', 0x968B6C, 'C', 0xEBEBEB, 'D', 0xDAD2BC,
        'E', 0x704431, 'F', 0x5B3523, 'G', 0x847651);
    static final String[] STAR = {
        "...a...",
        ".b.a.b.",
        "..cac..",
        "aacCcaa",
        "..cac..",
        ".b.a.b.",
        "...a...",
    };
    static final String[] FLAME = {
        "...a...",
        "...aa..",
        "..aab..",
        ".aabba.",
        ".abccba",
        "abccCba",
        "abcCCca",
        ".abbba.",
    };
    static BufferedImage pattern(String[] sym, int ox, int oy, int dark, int mid, int light, int bright) {
        int[][] g = grid();
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            char ch = SCROLL[y].charAt(x);
            if (ch != '.') g[y][x] = opaque(SCROLL_PAL.get(ch));
        }
        for (int y = 0; y < sym.length; y++) for (int x = 0; x < sym[y].length(); x++) {
            char ch = sym[y].charAt(x);
            int c = ch == 'a' ? mid : ch == 'b' ? dark : ch == 'c' ? light : ch == 'C' ? bright : -1;
            if (c >= 0) g[oy + y][ox + x] = opaque(c);
        }
        return img(g);
    }

    /** Bannermuster-Textur 64x64 (Vorderseite 20x40 ab (1,1)) bzw. Schild 64x64 (12x22 ab (1,1)): weiße Maske, Graustufen-Schatten */
    static BufferedImage bannerMask(boolean star, boolean shield) {
        BufferedImage im = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        double w = shield ? 12 : 20, h = shield ? 22 : 40;
        double cx = 1 + w / 2, cy = 1 + (shield ? 11.5 : 17);
        double s = shield ? 0.72 : 1.0;
        for (int y = 1; y < 1 + h; y++) for (int x = 1; x < 1 + w; x++) {
            double px = (x + 0.5 - cx) / s, py = (y + 0.5 - cy) / s;
            double v = star ? starField(px, py) : flameField(px, py);
            if (v <= 0) continue;
            int gray = (int) Math.round(255 * Math.min(1, 0.70 + 0.30 * v));
            im.setRGB(x, y, 0xFF000000 | (gray << 16) | (gray << 8) | gray);
        }
        return im;
    }
    /** strahlender Stern: vier lange, vier kurze Strahlen, Ring, Kern; > 0 = Muster, Wert = Helligkeit */
    static double starField(double x, double y) {
        double r = Math.hypot(x, y);
        double ax = Math.abs(x), ay = Math.abs(y);
        if (r < 2.6) return 1;
        if (r > 4.2 && r < 5.3) return 0.6;                                         // Ring
        if (ay < 15 && ax < 1.7 * (1 - ay / 15)) return 0.95 - ay / 40;             // senkrechte Strahlen
        if (ax < 10 && ay < 1.7 * (1 - ax / 10)) return 0.95 - ax / 30;             // waagrechte Strahlen
        double d = Math.abs(ax - ay) / Math.sqrt(2);
        if (r < 8.5 && d < 1.1 * (1 - r / 8.5)) return 0.8;                          // Diagonalen
        double sp = hash((int) Math.floor(x + 50), (int) Math.floor(y + 50), 7);
        if (r > 7.5 && r < 10 && sp < 0.03) return 0.45;                             // Funken
        return 0;
    }
    /** Seelenflamme: große Mittelzunge mit Schwung, zwei Seitenzungen, innen heller */
    static double flameField(double x, double y) {
        if (y > 15 || y < -15) return 0;
        double t = (15 - y) / 30.0;                                                 // 0 Fuß .. 1 Spitze
        double width = 8.5 * Math.pow(Math.sin(Math.PI * Math.min(1, t * 1.1 + 0.08)), 0.8) * (1 - t * 0.5);
        double sway = Math.sin(t * 4.6) * 2.0 * t;
        double dx = x - sway;
        if (Math.abs(dx) <= width) {
            double inner = 1 - Math.abs(dx) / Math.max(0.5, width);
            return 0.3 + inner * 0.7;
        }
        for (int s = -1; s <= 1; s += 2) {                                          // Seitenzungen
            double tt = t / 0.6;
            if (tt <= 0 || tt >= 1) continue;
            double cx = s * (6.5 + tt * 2.2), w = 2.0 * Math.sin(Math.PI * Math.min(1, tt * 1.2 + 0.1)) * (1 - tt * 0.6);
            if (Math.abs(x - cx) < w) return 0.45;
        }
        return 0;
    }

    static void write(BufferedImage im, String rel) throws Exception {
        File f = tex.resolve(rel).toFile();
        f.getParentFile().mkdirs();
        ImageIO.write(im, "png", f);
        System.out.println("geschrieben: " + rel);
    }
    static BufferedImage strip(List<BufferedImage> fr) {
        BufferedImage s = new BufferedImage(16, 16 * fr.size(), BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < fr.size(); i++) s.getGraphics().drawImage(fr.get(i), 0, 16 * i, null);
        return s;
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        tex = root.resolve("src/main/resources/assets/hha/textures");
        String preview = null;
        String jarPath = System.getProperty("user.home") + "/.gradle/caches/fabric-loom/1.21.11/minecraft-client.jar";
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--preview")) preview = args[++i];
            else jarPath = args[i];
        }
        ZipFile jar = new ZipFile(jarPath);
        List<BufferedImage> heavenIngot = new ArrayList<>(), hellIngot = new ArrayList<>();
        for (int f = 0; f < 4; f++) {
            heavenIngot.add(ingot(INGOT_HEAVEN, HST, GOLD, OUT_HEAVEN, f));
            hellIngot.add(ingot(INGOT_HELL, BST, SOUL, OUT_HELL, f));
        }
        Map<String, BufferedImage> items = new java.util.LinkedHashMap<>();
        items.put("hermes_feather", feather());
        items.put("hades_map", letter(new int[]{0x0E0D11, 0x1C1A21, 0x29262F, 0x3D3946}, new int[]{0x07301A, 0x0E4A24, 0x1C7A30, 0x36B04A, 0x7CE88A, 0xC8FFD0}, 3.0, OUT_HELL, false));
        items.put("hermes_summons", letter(new int[]{0xA89D86, 0xD9D0BC, 0xEFE9DA, 0xFFFDF5}, GOLD, 4.0, OUT_HEAVEN, true));
        items.put("jotunheims_upgrade_template", template(jar));
        items.put("heaven_banner_pattern", pattern(STAR, 4, 7, GOLD[1], GOLD[3], GOLD[4], GOLD[6]));
        items.put("hell_banner_pattern", pattern(FLAME, 4, 6, SOUL[1], SOUL[2], SOUL[4], SOUL[6]));
        BufferedImage[] masks = {bannerMask(true, false), bannerMask(false, false), bannerMask(true, true), bannerMask(false, true)};
        jar.close();

        if (preview != null) {
            Files.createDirectories(Path.of(preview));
            int S = 8;
            BufferedImage sheet = new BufferedImage(10 * (16 * S + 10) + 10, 3 * (16 * S + 10) + 64 * 3 + 20, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = sheet.createGraphics();
            g.setColor(new Color(0x59636E)); g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            int x = 10;
            for (BufferedImage fr : heavenIngot) { big(g, fr, x, 10, S); x += 16 * S + 10; }
            for (BufferedImage fr : hellIngot) { big(g, fr, x, 10, S); x += 16 * S + 10; }
            x = 10;
            for (BufferedImage im : items.values()) { big(g, im, x, 20 + 16 * S, S); x += 16 * S + 10; }
            x = 10;
            for (BufferedImage m : masks) {
                g.setColor(new Color(0x7A2E2E)); g.fillRect(x, 30 + 32 * S, 22 * 3, 42 * 3);
                big(g, m, x - 3, 30 + 32 * S - 3, 3); x += 22 * 3 + 20;
            }
            g.dispose();
            ImageIO.write(sheet, "png", new File(preview, "items_sheet.png"));
            System.out.println("Vorschau: " + preview + "/items_sheet.png");
            return;
        }
        write(strip(heavenIngot), "item/heaven_ingot.png");
        Files.writeString(tex.resolve("item/heaven_ingot.png.mcmeta"), INGOT_MCMETA);
        write(strip(hellIngot), "item/hell_ingot.png");
        Files.writeString(tex.resolve("item/hell_ingot.png.mcmeta"), INGOT_MCMETA);
        for (var e : items.entrySet()) write(e.getValue(), "item/" + e.getKey() + ".png");
        write(masks[0], "entity/banner/heaven_star.png");
        write(masks[1], "entity/banner/hell_flame.png");
        write(masks[2], "entity/shield/heaven_star.png");
        write(masks[3], "entity/shield/hell_flame.png");
    }

    static void big(Graphics2D g, BufferedImage im, int x0, int y0, int sc) {
        for (int y = 0; y < im.getHeight(); y++) for (int x = 0; x < im.getWidth(); x++) {
            int c = im.getRGB(x, y);
            if ((c >>> 24) == 0) continue;
            g.setColor(new Color(c, true)); g.fillRect(x0 + x * sc, y0 + y * sc, sc, sc);
        }
    }
}
