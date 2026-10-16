import com.susano.client.model.QuadSink;
import com.susano.client.model.SusanooModel;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Apercu 2D (painter's algorithm) de la geometrie du Susanoo. Outil de dev uniquement. */
public class Preview {
    record Q(float[] p, float nx, float ny, float nz, int tag, boolean glow) {}

    static List<Q> collect(SusanooModel.Pose pose) {
        List<Q> out = new ArrayList<>();
        final boolean[] g = {false};
        SusanooModel m = new SusanooModel((p, nx, ny, nz, tag) -> out.add(new Q(p.clone(), nx, ny, nz, tag, g[0])));
        g[0] = false;
        m.build(pose, false);
        g[0] = true;
        m.build(pose, true);
        return out;
    }

    static void draw(Graphics2D g2, List<Q> quads, int cx, int cy, float scale, float yawDeg, float pitchDeg,
                     Color core, Color glow) {
        double yaw = Math.toRadians(yawDeg), pit = Math.toRadians(pitchDeg);
        double cyw = Math.cos(yaw), syw = Math.sin(yaw), cp = Math.cos(pit), sp = Math.sin(pit);
        // vue : on tourne le modele autour de Y puis on incline autour de X ; camera regarde vers -z
        List<float[]> proj = new ArrayList<>();
        List<Q> order = new ArrayList<>(quads);
        order.sort(Comparator.comparingDouble((Q q) -> q.glow ? 1 : 0)
                .thenComparingDouble(q -> -depth(q, cyw, syw, cp, sp)));
        // tri : coeur d'abord, puis lueur ; dans chaque groupe, du plus loin au plus proche
        order.sort(Comparator.<Q, Integer>comparing(q -> q.glow ? 1 : 0)
                .thenComparingDouble(q -> depth(q, cyw, syw, cp, sp)));
        for (Q q : order) {
            Path2D.Float path = new Path2D.Float();
            for (int i = 0; i < 4; i++) {
                double x = q.p[i * 3], y = q.p[i * 3 + 1], z = q.p[i * 3 + 2];
                double x1 = x * cyw + z * syw, z1 = -x * syw + z * cyw;
                double y2 = y * cp - z1 * sp, z2 = y * sp + z1 * cp;
                float sx = cx + (float) x1 * scale, sy = cy - (float) y2 * scale;
                if (i == 0) path.moveTo(sx, sy); else path.lineTo(sx, sy);
            }
            path.closePath();
            // normale en espace camera
            double nx = q.nx * cyw + q.nz * syw, nz1 = -q.nx * syw + q.nz * cyw;
            double ny = q.ny * cp - nz1 * sp, nz2 = q.ny * sp + nz1 * cp;
            double light = 0.55 + 0.45 * Math.max(0, nx * -0.3 + ny * 0.6 + nz2 * 0.74);
            Color c;
            if (!q.glow) {
                c = new Color(clamp(core.getRed() * light), clamp(core.getGreen() * light), clamp(core.getBlue() * light), 255);
            } else if (q.tag == QuadSink.TAG_BODY) {
                c = new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 70);
            } else if (q.tag == QuadSink.TAG_AURA) {
                c = new Color(10, 6, 16, 150);
            } else if (q.tag == QuadSink.TAG_AURA_HOT) {
                c = new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 200);
            } else {
                c = new Color(255, 255, 255, 235);
                if (q.tag == QuadSink.TAG_ARROW) c = new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 230);
            }
            g2.setColor(c);
            g2.fill(path);
            if (!q.glow) {
                g2.setColor(new Color(0, 0, 0, 90));
                g2.setStroke(new BasicStroke(0.6f));
                g2.draw(path);
            }
        }
    }

    static double depth(Q q, double cyw, double syw, double cp, double sp) {
        double x = 0, y = 0, z = 0;
        for (int i = 0; i < 4; i++) {
            x += q.p[i * 3]; y += q.p[i * 3 + 1]; z += q.p[i * 3 + 2];
        }
        x /= 4; y /= 4; z /= 4;
        double x1 = x * cyw + z * syw, z1 = -x * syw + z * cyw;
        return y * sp + z1 * cp; // plus grand = plus proche de la camera
    }

    static int clamp(double v) { return (int) Math.max(0, Math.min(255, v)); }

    public static void main(String[] a) throws Exception {
        // args: out.png "core r,g,b" "glow r,g,b" ; rows defined below
        String out = a[0];
        Color core = parse(a[1]), glow = parse(a[2]);
        String mode = a.length > 3 ? a[3] : "forms";
        int cw = 440, ch = 560;
        List<float[]> cells = new ArrayList<>(); // form, yaw, pitch, attack, kind, scale
        if (mode.equals("forms")) {
            for (int f = 1; f <= 4; f++) {
                cells.add(new float[]{f, 0, 0, -1, 0});
                cells.add(new float[]{f, 90, 0, -1, 0});
                cells.add(new float[]{f, 150, 12, -1, 0});
            }
        } else if (mode.equals("attacks")) {
            float[][] ps = {{1, 30, 12, 0.5f, 0}, {2, 30, 12, 0.5f, 0}, {3, 40, 12, 0.22f, 0}, {3, 40, 12, 0.5f, 0},
                    {4, 40, 12, 0.22f, 0}, {4, 40, 12, 0.5f, 0}, {1, 30, 12, 0.6f, 1}, {3, 30, 12, 0.6f, 1}, {4, 30, 12, 0.6f, 1}};
            for (float[] p : ps) cells.add(p);
        }
        else if (mode.equals("legs")) {
            float[][] ps = {{4, 0, 0, -1, 0}, {4, 90, 0, -1, 0}, {4, 90, 0, 0.45f, 2}, {4, 90, 0, 0.5f, 3}, {4, 40, 0, 0.5f, 0}, {3, 0, 0, -1, 0}};
            for (float[] p : ps) cells.add(p);
        }
        int cols = 3, rows = (cells.size() + cols - 1) / cols;
        BufferedImage img = new BufferedImage(cw * cols, ch * rows, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(150, 195, 235));
        g2.fillRect(0, 0, img.getWidth(), img.getHeight());
        for (int i = 0; i < cells.size(); i++) {
            float[] c = cells.get(i);
            SusanooModel.Pose pose = new SusanooModel.Pose();
            pose.form = (int) c[0];
            pose.attack = c[3];
            pose.kind = (int) c[4];
            pose.time = 10;
            pose.aura = System.getProperty("noaura") != null ? 0f : new float[]{0, 0.35f, 0.55f, 0.8f, 1f}[pose.form];
            float ps = pose.form == 4 ? 8f : new float[]{0, 1f, 1.25f, 1.9f, 8f}[pose.form];
            pose.ringRadius = pose.form == 4 ? 0.9f : 0.8f;
            if (pose.form == 4) {
                // joueur a 11 blocs au-dessus du sol : sol a -(1.15 + 11 - 1.15) / 8 sous la poitrine
                float g = -(11f + 1.15f - 0.0f) / ps + 0.0f;
                pose.ground = g;
                pose.footGround[0] = g;
                pose.footGround[1] = g;
                if (mode.equals("legs") && i == 99) { pose.legSwing[0] = 22; pose.legSwing[1] = -22; pose.legLift[1] = 0.8f; pose.legSide[0] = 6; }
            } else {
                pose.ground = -1.15f / ps;
            }
            int col = i % cols, row = i / cols;
            Graphics2D cg = (Graphics2D) g2.create(col * cw, row * ch, cw, ch);
            cg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // sol : y = -1.15 / scale non connu ici ; on trace juste une ligne a y=-0.9
            float scale = pose.form == 4 ? 165 : 300;
            draw(cg, collect(pose), cw / 2, (int) (ch * (pose.form == 4 ? 0.34 : 0.60)), scale, c[1], c[2], core, glow);
            cg.setColor(Color.WHITE);
            cg.drawString("form " + (int) c[0] + "  yaw " + (int) c[1] + "  atk " + c[3] + " kind " + (int) c[4], 8, 16);
            cg.dispose();
        }
        ImageIO.write(img, "png", new File(out));
    }

    static Color parse(String s) {
        String[] p = s.split(",");
        return new Color(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }
}
