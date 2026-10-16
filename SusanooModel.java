package com.susano.client.model;

import java.util.ArrayDeque;

/**
 * Geometrie procedurale du Susanoo (design original : esprit guerrier spectral).
 * <p>
 * Aucune dependance a Minecraft : le modele envoie des quads a un {@link QuadSink}.
 * Repere : origine = centre de la poitrine du joueur, +Y haut, +Z devant.
 * Le cote droit du joueur est x &lt; 0.
 * <p>
 * Le rendu se fait en deux passes : {@code glow=false} (corps sombre) puis
 * {@code glow=true} (coque lumineuse translucide, un peu plus epaisse, + yeux / fleche).
 */
public final class SusanooModel {

    /** Etat d'animation. */
    public static final class Pose {
        /** 1 = cotes, 2 = squelette, 3 = arme, 4 = parfait. */
        public int form = 1;
        /** Temps en ticks (avec partialTick). */
        public float time;
        /** Progression de l'attaque 0..1, negatif = pas d'attaque. */
        public float attack = -1f;
        /** 0 = melee, 1 = technique speciale (fleche), 2 = pietinement, 3 = coup de pied. */
        public int kind;
        public float limbPos, limbSpeed;
        /** Intensite de l'aura (0 = aucune, 1 = maximale). */
        public float aura;
        /** Vrai : dessine la geometrie d'aura (flammes, anneau). Faux pour les coques supplementaires. */
        public boolean drawAura = true;
        /** Rayon de l'anneau d'aura au sol (unites modele, 0 = aucun) et hauteur du sol (NaN = inconnue). */
        public float ringRadius;
        public float ground = Float.NaN;
        /** Jambes (forme parfaite) : balancement avant en degres, ecart lateral, levee du pied (0..1),
         *  et hauteur du sol sous chaque pied en unites modele (NaN = inconnue). Index 0 = droite, 1 = gauche. */
        public final float[] legSwing = new float[2];
        public final float[] legSide = new float[2];
        public final float[] legLift = new float[2];
        public final float[] footGround = {Float.NaN, Float.NaN};
        /** Inclinaison de la tete du joueur en degres (positif = regarde vers le bas). */
        public float pitch;
        /** Si vrai, les pieces plus proches que camMargin de la camera sont masquees. */
        public boolean hasCam;
        public float camX, camY, camZ, camMargin;
    }

    private static final float GLOW_PAD = 0.012f;
    private static final float GLOW_MUL = 1.45f;
    private static final int[][] FACES = {
            {0, 2, 6, 4}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 1, 3, 2}, {4, 5, 7, 6}
    };

    private final QuadSink sink;
    private final ArrayDeque<Mat4> stack = new ArrayDeque<>();
    private Mat4 cur = new Mat4();
    private boolean glow;
    private Pose pose;
    private int tag;
    private final float[] pts = new float[24];
    private final float[] quad = new float[12];
    private final float[] ang = new float[4];

    public SusanooModel(QuadSink sink) {
        this.sink = sink;
    }

    // ------------------------------------------------------------------ API

    public void build(Pose pose, boolean glowPass) {
        this.pose = pose;
        this.glow = glowPass;
        this.cur = new Mat4();
        this.stack.clear();
        this.tag = QuadSink.TAG_BODY;

        float breathe = (float) Math.sin(pose.time * 0.09f) * 0.010f;
        push();
        tr(0, breathe, 0);
        switch (pose.form) {
            case 1 -> formRibcage();
            case 2 -> formSkeleton();
            case 3 -> formArmored(false);
            default -> formArmored(true);
        }
        pop();
        if (glowPass) {
            arrow();
            if (pose.drawAura) {
                aura();
                groundRing();
            }
        }
    }

    // ------------------------------------------------------------------ formes

    private void formRibcage() {
        spine(-0.50f, 0.60f, -0.30f, 0.040f);
        ribcage(8, -0.38f, 0.50f, 0.46f, 0.33f, 0.026f);
        shoulderGirdle(0.50f, 0.50f, 0.034f);
        for (int s = -1; s <= 1; s += 2) {
            arm(s, 0.50f, 0.50f, 0.42f, 0.40f, 0.036f, 0.026f, 1, false);
        }
        brightBox(-0.05f, 0.03f, 0.27f, 0.05f, 0.12f, 0.30f); // noyau de chakra
    }

    private void formSkeleton() {
        spine(-0.95f, 0.64f, -0.30f, 0.050f);
        ribcage(9, -0.42f, 0.54f, 0.50f, 0.36f, 0.030f);
        pelvis();
        shoulderGirdle(0.54f, 0.54f, 0.040f);
        // cou
        bone(0, 0.62f, -0.22f, 0, 0.78f, -0.10f, 0.05f, 0.04f);
        skull(0.90f);
        for (int s = -1; s <= 1; s += 2) {
            arm(s, 0.54f, 0.54f, 0.46f, 0.44f, 0.046f, 0.032f, 2, false);
            // pointe d'epaule
            bone(s * 0.54f, 0.56f, 0, s * 0.62f, 0.78f, -0.04f, 0.045f, 0.008f);
        }
        brightBox(-0.06f, 0.04f, 0.31f, 0.06f, 0.14f, 0.34f);
    }

    private void formArmored(boolean perfect) {
        spine(-0.60f, 0.52f, -0.24f, 0.05f);
        torsoArmor(perfect);
        for (int s = -1; s <= 1; s += 2) {
            pauldron(s, perfect);
            arm(s, 0.50f, 0.40f, 0.44f, 0.44f, 0.075f, 0.066f, perfect ? 4 : 3, s < 0);
        }
        helmet(0.80f, perfect);
        if (perfect) {
            wings();
            halo();
            legs();
        }
    }

    // ------------------------------------------------------------------ morceaux de squelette

    private void spine(float y0, float y1, float z, float t) {
        bone(0, y0, z, 0, y1, z, t, t);
        int n = Math.max(2, Math.round((y1 - y0) / 0.115f));
        for (int i = 0; i <= n; i++) {
            float y = y0 + (y1 - y0) * i / n;
            bone(0, y, z - 0.01f, 0, y + 0.045f, z - 0.15f, t * 0.75f, t * 0.2f);
        }
    }

    /** N paires de cotes en arc, du dos vers le sternum. */
    private void ribcage(int n, float yBot, float yTop, float a, float b, float t) {
        final int seg = 6;
        for (int k = 0; k < n; k++) {
            float u = (k + 0.5f) / n;
            float ak = a * (0.72f + 0.45f * (float) Math.sin(Math.PI * u));
            float bk = b * (0.80f + 0.30f * (float) Math.sin(Math.PI * u));
            float y = yBot + (yTop - yBot) * k / (n - 1);
            for (int s = -1; s <= 1; s += 2) {
                float px = 0, py = 0, pz = 0;
                for (int j = 0; j <= seg; j++) {
                    float f = j / (float) seg;
                    double phi = Math.toRadians(8 + f * (152 - 8));
                    float x = (float) (s * ak * Math.sin(phi));
                    float z = (float) (-bk * Math.cos(phi));
                    float yy = y - 0.09f * f;
                    if (j > 0) {
                        bone(px, py, pz, x, yy, z, t, t);
                    }
                    px = x;
                    py = yy;
                    pz = z;
                }
            }
        }
        // sternum
        float zf = b * 0.93f;
        bone(0, yTop - 0.04f, zf, 0, yBot - 0.10f, zf, t * 1.6f, t * 1.3f);
    }

    private void shoulderGirdle(float sx, float sy, float t) {
        for (int s = -1; s <= 1; s += 2) {
            bone(0, sy + 0.04f, -0.28f, s * sx, sy, -0.03f, t, t * 1.2f);
            bone(s * sx, sy, -0.03f, s * sx * 0.55f, sy - 0.02f, 0.22f, t * 1.1f, t * 0.8f);
        }
    }

    private void pelvis() {
        for (int s = -1; s <= 1; s += 2) {
            bone(0, -0.92f, -0.28f, s * 0.30f, -0.86f, 0.0f, 0.06f, 0.05f);
        }
    }

    private void skull(float cy) {
        box(-0.16f, cy - 0.15f, -0.17f, 0.16f, cy + 0.17f, 0.17f);
        box(-0.17f, cy + 0.02f, 0.13f, 0.17f, cy + 0.07f, 0.19f);
        box(-0.11f, cy - 0.12f, 0.10f, 0.11f, cy + 0.02f, 0.19f);
        bone(0, cy - 0.02f, 0.19f, 0, cy - 0.09f, 0.23f, 0.02f, 0.012f);
        box(-0.11f, cy - 0.23f, -0.02f, 0.11f, cy - 0.14f, 0.17f);
        for (int i = -2; i <= 2; i++) {
            box(i * 0.042f - 0.012f, cy - 0.14f, 0.15f, i * 0.042f + 0.012f, cy - 0.095f, 0.175f);
        }
        for (int s = -1; s <= 1; s += 2) {
            brightBoxX(s, 0.03f, 0.11f, cy - 0.04f, 0.185f, cy + 0.02f, 0.205f);
            // petites cornes
            bone(s * 0.13f, cy + 0.14f, 0.02f, s * 0.20f, cy + 0.32f, -0.02f, 0.04f, 0.008f);
        }
    }

    // ------------------------------------------------------------------ armure

    private void torsoArmor(boolean perfect) {
        // plastron, dos, flancs
        bone2(0, 0.40f, 0.20f, 0, -0.34f, 0.17f, 0.27f, 0.045f, 0.20f, 0.04f, 0, 0, 1);
        bone2(0, 0.40f, -0.20f, 0, -0.34f, -0.17f, 0.29f, 0.04f, 0.22f, 0.04f, 0, 0, 1);
        for (int s = -1; s <= 1; s += 2) {
            box(s * 0.22f - 0.04f, -0.32f, -0.18f, s * 0.22f + 0.04f, 0.38f, 0.18f);
            // lames de ceinture
            for (int i = 0; i < 4; i++) {
                float y = -0.36f - 0.08f * i;
                float w = 0.30f - 0.015f * i;
                box(-w, y - 0.045f, -0.19f, w, y + 0.045f, 0.20f);
            }
        }
        // cotes visibles sous l'armure (bandes)
        for (int i = 0; i < 4; i++) {
            float y = 0.30f - 0.15f * i;
            box(-0.25f, y - 0.025f, 0.205f, 0.25f, y + 0.025f, 0.235f);
        }
        // cou / col
        box(-0.12f, 0.38f, -0.12f, 0.12f, 0.52f, 0.14f);
        // noyau lumineux
        brightBox(-0.065f, 0.02f, 0.235f, 0.065f, 0.16f, 0.255f);
        if (perfect) {
            brightBox(-0.18f, 0.20f, 0.236f, -0.12f, 0.26f, 0.246f);
            brightBox(0.12f, 0.20f, 0.236f, 0.18f, 0.26f, 0.246f);
        }
    }

    private void pauldron(int s, boolean perfect) {
        push();
        tr(s * 0.46f, 0.42f, 0);
        rz(-22f * s);
        box(-0.12f, -0.01f, -0.16f, 0.12f, 0.05f, 0.16f);
        box(-0.15f, -0.08f, -0.18f, 0.15f, -0.01f, 0.18f);
        box(-0.17f, -0.15f, -0.15f, 0.17f, -0.08f, 0.15f);
        float k = perfect ? 1.5f : 1f;
        bone(0, 0.05f, 0, s * 0.04f, 0.05f + 0.22f * k, -0.03f, 0.055f, 0.008f);
        bone(s * 0.10f, 0.0f, 0.0f, s * 0.22f, 0.12f * k, -0.05f, 0.04f, 0.006f);
        pop();
    }

    private void helmet(float cy, boolean perfect) {
        // calotte
        box(-0.17f, cy - 0.15f, -0.19f, 0.17f, cy + 0.16f, 0.19f);
        // visiere / masque
        bone2(0, cy + 0.03f, 0.19f, 0, cy - 0.16f, 0.21f, 0.15f, 0.03f, 0.10f, 0.035f, 0, 0, 1);
        box(-0.07f, cy - 0.20f, 0.17f, 0.07f, cy - 0.13f, 0.23f);
        for (int s = -1; s <= 1; s += 2) {
            // joues
            box(s * 0.17f - 0.02f, cy - 0.18f, -0.08f, s * 0.17f + 0.02f, cy - 0.02f, 0.18f);
            // yeux
            brightBoxX(s, 0.03f, 0.12f, cy - 0.005f, 0.205f, cy + 0.035f, 0.225f);
            // crocs
            bone(s * 0.055f, cy - 0.17f, 0.22f, s * 0.065f, cy - 0.30f, 0.25f, 0.022f, 0.004f);
            // cornes courbes
            float k = perfect ? 1.35f : 1f;
            float[] hx = {0.13f, 0.23f, 0.30f, 0.30f};
            float[] hy = {0.14f, 0.28f, 0.46f, 0.64f};
            float[] hz = {0.02f, -0.03f, -0.11f, -0.22f};
            float[] ht = {0.055f, 0.045f, 0.03f, 0.008f};
            for (int i = 0; i < 3; i++) {
                bone(s * hx[i] * k, cy + hy[i] * k, hz[i] * k,
                        s * hx[i + 1] * k, cy + hy[i + 1] * k, hz[i + 1] * k,
                        ht[i] * k, ht[i + 1] * k);
            }
        }
        // crete
        bone(0, cy + 0.16f, 0.13f, 0, cy + 0.34f, -0.22f, 0.035f, 0.008f);
        if (perfect) {
            for (int s = -1; s <= 1; s += 2) {
                bone(s * 0.06f, cy + 0.16f, 0.08f, s * 0.12f, cy + 0.42f, -0.18f, 0.03f, 0.006f);
            }
        }
    }

    private void wings() {
        for (int s = -1; s <= 1; s += 2) {
            push();
            tr(s * 0.14f, 0.30f, -0.26f);
            rz(s * (float) Math.sin(pose.time * 0.05f) * 3f);
            for (int i = 0; i < 6; i++) {
                float phi = (float) Math.toRadians(18 + i * 15);
                float len = 1.45f - 0.13f * i;
                float dx = s * (float) Math.sin(phi), dy = (float) Math.cos(phi), dz = -0.30f;
                float inv = 1f / (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                dx *= inv;
                dy *= inv;
                dz *= inv;
                float mx = dx * len * 0.55f, my = dy * len * 0.55f, mz = dz * len * 0.55f;
                float ex = mx + (dx + s * 0.20f) * len * 0.50f;
                float ey = my + (dy - 0.32f) * len * 0.50f;
                float ez = mz + (dz - 0.20f) * len * 0.50f;
                bone(0, 0, 0, mx, my, mz, 0.05f, 0.032f);
                bone(mx, my, mz, ex, ey, ez, 0.032f, 0.006f);
            }
            pop();
        }
    }

    private void halo() {
        final int n = 22;
        float cx = 0, cy = 0.84f, cz = -0.34f, r = 0.64f;
        float spin = pose.time * 1.2f;
        float px = 0, py = 0;
        for (int i = 0; i <= n; i++) {
            double a = Math.toRadians(i * 360.0 / n);
            float x = cx + r * (float) Math.cos(a);
            float y = cy + r * (float) Math.sin(a);
            if (i > 0) {
                bone(px, py, cz, x, y, cz, 0.020f, 0.020f);
            }
            if (i % 2 == 0 && i < n) {
                double a2 = a + Math.toRadians(spin);
                float sx = cx + (r + 0.20f) * (float) Math.cos(a2);
                float sy = cy + (r + 0.20f) * (float) Math.sin(a2);
                float bx = cx + r * (float) Math.cos(a2);
                float by = cy + r * (float) Math.sin(a2);
                bone(bx, by, cz, sx, sy, cz, 0.026f, 0.004f);
            }
            px = x;
            py = y;
        }
    }


    // ------------------------------------------------------------------ jambes (forme parfaite)

    /** Geometrie des jambes (unites modele) : hanche, longueur cuisse / tibia. Reprises par SusanooEntity. */
    public static final float HIP_X = 0.17f, HIP_Y = -0.72f, LEG_1 = 0.46f, LEG_2 = 0.46f;

    private void legs() {
        // ceinture basse et tassettes
        box(-0.30f, HIP_Y - 0.10f, -0.20f, 0.30f, HIP_Y + 0.06f, 0.22f);
        box(-0.26f, HIP_Y - 0.26f, 0.15f, 0.26f, HIP_Y - 0.08f, 0.22f);
        box(-0.26f, HIP_Y - 0.24f, -0.22f, 0.26f, HIP_Y - 0.08f, -0.16f);
        brightBox(-0.05f, HIP_Y - 0.06f, 0.225f, 0.05f, HIP_Y + 0.02f, 0.235f);
        leg(-1);
        leg(1);
    }

    /** s = -1 : jambe droite, +1 : gauche. Cinematique inverse 2 segments vers le sol. */
    private void leg(int s) {
        int i = s < 0 ? 0 : 1;
        float reach = LEG_1 + LEG_2;
        float gy = Float.isNaN(pose.footGround[i]) ? HIP_Y - 0.88f * reach : pose.footGround[i];
        float drop = Math.max(0.15f, HIP_Y - gy + 0.03f);
        float sw = Math.max(-55f, Math.min(55f, pose.legSwing[i]));
        float tz = (float) Math.tan(Math.toRadians(sw)) * Math.min(drop, reach);
        float ay = pose.legLift[i] * 0.09f, az = 0f;
        // attaques : la jambe droite pietine / donne le coup de pied
        if (pose.attack >= 0f && s < 0 && (pose.kind == 2 || pose.kind == 3)) {
            float p = pose.attack;
            if (pose.kind == 2) {
                if (p < 0.5f) {
                    float e = smooth(p / 0.5f);
                    ay = lerp(0f, 0.48f, e);
                    az = lerp(0f, 0.30f, e);
                } else if (p < 0.62f) {
                    float e = (p - 0.5f) / 0.12f;
                    e *= e;
                    ay = lerp(0.48f, -0.04f, e);
                    az = lerp(0.30f, 0.22f, e);
                } else {
                    float e = smooth((p - 0.62f) / 0.38f);
                    ay = lerp(-0.04f, 0f, e);
                    az = lerp(0.22f, 0f, e);
                }
            } else {
                if (p < 0.34f) {
                    float e = smooth(p / 0.34f);
                    ay = lerp(0f, 0.10f, e);
                    az = lerp(0f, -0.30f, e);
                } else if (p < 0.56f) {
                    float e = (p - 0.34f) / 0.22f;
                    e = 1f - (1f - e) * (1f - e);
                    ay = lerp(0.10f, 0.50f, e);
                    az = lerp(-0.30f, 0.85f, e);
                } else {
                    float e = smooth((p - 0.56f) / 0.44f);
                    ay = lerp(0.50f, 0f, e);
                    az = lerp(0.85f, 0f, e);
                }
            }
        }
        float tgz = tz + az, tgy = -drop + ay;
        float d = (float) Math.sqrt(tgz * tgz + tgy * tgy);
        float dc = Math.max(0.12f, Math.min(reach - 0.003f, d));
        float phi = (float) Math.atan2(tgz, -tgy);
        float cosA = (LEG_1 * LEG_1 + dc * dc - LEG_2 * LEG_2) / (2f * LEG_1 * dc);
        float alpha = (float) Math.acos(Math.max(-1f, Math.min(1f, cosA)));
        float cosG = (LEG_1 * LEG_1 + LEG_2 * LEG_2 - dc * dc) / (2f * LEG_1 * LEG_2);
        float gamma = (float) Math.acos(Math.max(-1f, Math.min(1f, cosG)));
        float a1 = phi + alpha;
        float a2 = a1 - ((float) Math.PI - gamma);
        float kz = LEG_1 * (float) Math.sin(a1), ky = -LEG_1 * (float) Math.cos(a1);
        float fz = kz + LEG_2 * (float) Math.sin(a2), fy = ky - LEG_2 * (float) Math.cos(a2);

        push();
        tr(s * HIP_X, HIP_Y, 0f);
        rz(s * (4f + pose.legSide[i]));
        // hanche
        box(-0.095f, -0.09f, -0.095f, 0.095f, 0.09f, 0.095f);
        // cuisse + plaque
        bone2(0, 0, 0, 0, ky, kz, 0.095f, 0.085f, 0.078f, 0.068f, 0, 0, 1);
        float n1z = -ky / LEG_1, n1y = kz / LEG_1;
        bone2(0, 0.04f * n1y, 0.075f * n1z, 0, ky + 0.04f * n1y, kz + 0.075f * n1z, 0.075f, 0.022f, 0.06f, 0.02f, 0, 0, 1);
        // genou + pointe
        push();
        tr(0, ky, kz);
        box(-0.085f, -0.085f, -0.085f, 0.085f, 0.085f, 0.085f);
        bone(0, 0, 0.05f * n1z, 0, 0.05f * n1y + 0.04f, 0.19f * n1z, 0.05f, 0.006f);
        pop();
        // tibia + jambiere
        float sx = fz - kz, sy = fy - ky;
        float sl = Math.max(1e-4f, (float) Math.sqrt(sx * sx + sy * sy));
        float n2z = -sy / sl, n2y = sx / sl;
        bone2(0, ky, kz, 0, fy, fz, 0.080f, 0.074f, 0.060f, 0.056f, 0, 0, 1);
        bone2(0, ky + sy * 0.18f + 0.04f * n2y, kz + sx * 0.18f + 0.07f * n2z,
                0, ky + sy * 0.80f + 0.04f * n2y, kz + sx * 0.80f + 0.07f * n2z, 0.07f, 0.022f, 0.066f, 0.02f, 0, 0, 1);
        // pied pose a plat : talon, dessus, orteils-griffes
        box(-0.095f, fy - 0.035f, fz - 0.09f, 0.095f, fy + 0.055f, fz + 0.24f);
        bone(0, fy + 0.03f, fz - 0.06f, 0, fy + 0.07f, fz - 0.20f, 0.04f, 0.006f);
        for (int k = -1; k <= 1; k++) {
            bone(k * 0.055f, fy, fz + 0.22f, k * 0.062f, fy - 0.02f, fz + 0.43f, 0.032f, 0.005f);
        }
        pop();
    }

    // ------------------------------------------------------------------ aura

    private static float frac(float x) {
        return x - (float) Math.floor(x);
    }

    /** Flammes sombres a coeur incandescent qui s'elevent du corps. */
    private void aura() {
        float a = pose.aura;
        if (a <= 0f) {
            return;
        }
        float t = pose.time;
        int n = Math.round(8 + 26 * a);
        for (int i = 0; i < n; i++) {
            float h1 = frac(i * 0.61803399f), h2 = frac(i * 0.7548777f + 0.31f), h3 = frac(i * 0.5698403f + 0.57f);
            double ang = i * 2.399963;
            float rr = 0.26f + 0.36f * h2;
            float y0 = lerp(pose.form >= 3 ? -0.62f : -0.40f, 0.52f, h1);
            float cx = (float) Math.cos(ang), sx = (float) Math.sin(ang);
            flame(rr * cx, y0, rr * 0.75f * sx - 0.04f, cx, sx, i, h3, a, t);
        }
        if (pose.form >= 2) {
            float cy = pose.form == 2 ? 0.90f : 0.80f;
            for (int s = -1; s <= 1; s += 2) {
                // traînees de feu derriere les yeux
                tag = QuadSink.TAG_AURA_HOT;
                float w = 0.5f + 0.5f * (float) Math.sin(t * 0.4f + s);
                bone(s * 0.08f, cy + 0.015f, 0.22f, s * (0.17f + 0.03f * w), cy + 0.09f, -0.02f, 0.016f, 0.004f);
                bone(s * (0.17f + 0.03f * w), cy + 0.09f, -0.02f, s * (0.24f + 0.04f * w), cy + 0.20f + 0.04f * w, -0.30f, 0.012f, 0.002f);
                tag = QuadSink.TAG_BODY;
            }
        }
        if (pose.form >= 3) {
            float cy = 0.80f;
            for (int k = 0; k < 8; k++) {
                double ang = k * 0.785398 + 0.3;
                float cx = (float) Math.cos(ang), sx = (float) Math.sin(ang);
                flame(0.14f * cx, cy + 0.12f, 0.12f * sx, cx, sx, 100 + k, 0.55f + 0.1f * k % 0.4f, a, t);
            }
            for (int s = -1; s <= 1; s += 2) {
                for (int k = 0; k < 4; k++) {
                    flame(s * (0.40f + 0.05f * k), 0.46f, -0.05f + 0.05f * k, s, 0.2f, 200 + k + s * 7, 0.5f + 0.15f * k, a, t);
                }
            }
        }
    }

    private void flame(float bx, float by, float bz, float ox, float oz, int seed, float h, float a, float t) {
        float len = (0.20f + 0.34f * h) * (0.75f + 0.5f * a);
        len *= 0.78f + 0.22f * (float) Math.sin(t * 0.31f + seed * 1.3f);
        float w0 = (0.032f + 0.03f * a) / GLOW_MUL;
        float px = bx, py = by, pz = bz;
        final int seg = 4;
        for (int k = 1; k <= seg; k++) {
            float f = k / (float) seg;
            float swX = (float) Math.sin(t * 0.12f + seed * 1.7f + k * 0.9f) * 0.07f * f * (0.6f + a);
            float swZ = (float) Math.cos(t * 0.10f + seed * 2.1f + k * 0.8f) * 0.06f * f;
            float nx = bx + ox * 0.16f * f + swX;
            float ny = by + len * f;
            float nz = bz + oz * 0.12f * f + swZ;
            float t0 = w0 * (1f - (k - 1f) / seg), t1 = w0 * (1f - (float) k / seg) + 0.003f;
            tag = QuadSink.TAG_AURA;
            bone(px, py, pz, nx, ny, nz, t0, t1);
            if (k >= 2) {
                tag = QuadSink.TAG_AURA_HOT;
                bone(px, py, pz, nx, ny, nz, t0 * 0.5f, t1 * 0.5f);
            }
            px = nx;
            py = ny;
            pz = nz;
        }
        tag = QuadSink.TAG_BODY;
    }

    /** Anneau de flammes et runes sur le sol, sous le Susanoo. */
    private void groundRing() {
        if (pose.ringRadius <= 0f || Float.isNaN(pose.ground) || pose.aura <= 0f) {
            return;
        }
        float r = pose.ringRadius, y = pose.ground + 0.006f, t = pose.time;
        final int n = 40;
        float px = 0, pz = 0, qx = 0, qz = 0;
        for (int i = 0; i <= n; i++) {
            double a = Math.toRadians(i * 360.0 / n);
            float x = r * (float) Math.cos(a), z = r * (float) Math.sin(a);
            float x2 = r * 0.80f * (float) Math.cos(a), z2 = r * 0.80f * (float) Math.sin(a);
            if (i > 0) {
                tag = QuadSink.TAG_AURA;
                bone(px, y, pz, x, y, z, 0.03f * r, 0.004f);
                tag = QuadSink.TAG_AURA_HOT;
                bone(qx, y + 0.004f, qz, x2, y + 0.004f, z2, 0.008f * r, 0.003f);
            }
            px = x;
            pz = z;
            qx = x2;
            qz = z2;
        }
        // pointes tournantes (runes) et flammes debout sur l'anneau
        int spikes = 14;
        float spin = t * 0.015f;
        for (int i = 0; i < spikes; i++) {
            double a = i * Math.PI * 2 / spikes + spin;
            float cx = (float) Math.cos(a), sx = (float) Math.sin(a);
            tag = QuadSink.TAG_AURA_HOT;
            bone(r * 0.80f * cx, y + 0.004f, r * 0.80f * sx, r * 1.10f * cx, y + 0.004f, r * 1.10f * sx, 0.006f * r, 0.002f);
            float fl = (0.10f + 0.07f * (float) Math.sin(t * 0.25f + i * 1.9f) + 0.06f * frac(i * 0.618f)) * Math.min(1.6f, r + 0.4f);
            tag = QuadSink.TAG_AURA;
            bone(r * cx, y, r * sx, r * cx, y + fl, r * sx, 0.012f * r + 0.006f, 0.002f);
        }
        tag = QuadSink.TAG_BODY;
    }

    // ------------------------------------------------------------------ bras

    /** s = -1 : bras droit, +1 : bras gauche. */
    private void arm(int s, float sx, float sy, float l1, float l2, float t0, float t1, int form, boolean sword) {
        armAngles(s, form, sword, ang);
        push();
        tr(s * sx, sy, 0);
        rz(s * ang[2]);
        rx(ang[0]);
        // epaule
        if (form >= 3) {
            box(-0.07f, -0.07f, -0.07f, 0.07f, 0.07f, 0.07f);
        }
        bone(0, 0, 0, 0, -l1, 0, t0, t0 * 0.92f);
        tr(0, -l1, 0);
        rx(ang[1]);
        // coude
        if (form >= 3) {
            box(-0.06f, -0.06f, -0.06f, 0.06f, 0.06f, 0.06f);
            bone(0, 0, -0.04f, 0, 0.02f, -0.19f, 0.045f, 0.006f);
        } else if (form == 2) {
            bone(0, 0, -0.03f, 0, 0.0f, -0.15f, 0.035f, 0.006f);
        }
        bone(0, 0, 0, 0, -l2, 0, t0 * 0.95f, t1);
        if (form >= 3) {
            // brassard
            box(-t0 * 1.25f, -l2 * 0.70f, -t0 * 1.25f, t0 * 1.25f, -l2 * 0.25f, t0 * 1.25f);
        }
        tr(0, -l2, 0);
        rx(ang[3]);
        if (form >= 3) {
            // poing
            box(-0.075f, -0.10f, -0.075f, 0.075f, 0.02f, 0.075f);
            if (sword) {
                sword();
            } else {
                for (int i = -1; i <= 1; i++) {
                    bone(i * 0.045f, -0.09f, 0.06f, i * 0.05f, -0.20f, 0.10f, 0.02f, 0.006f);
                }
            }
        } else {
            box(-0.05f, -0.07f, -0.04f, 0.05f, 0.0f, 0.04f);
            for (int i = 0; i < 4; i++) {
                float x = -0.045f + i * 0.03f;
                bone(x, -0.06f, 0.0f, x * 1.2f, -0.17f, 0.03f, 0.015f, 0.008f);
                bone(x * 1.2f, -0.17f, 0.03f, x * 1.3f, -0.25f, 0.10f, 0.008f, 0.002f);
            }
            bone(s * 0.05f, -0.03f, 0.0f, s * 0.11f, -0.11f, 0.07f, 0.015f, 0.004f);
        }
        pop();
    }

    private void sword() {
        // la lame prolonge l'avant-bras ; garde et poignee
        bone(0, 0.10f, 0, 0, -0.08f, 0, 0.032f, 0.032f);
        box(-0.15f, -0.13f, -0.035f, 0.15f, -0.08f, 0.035f);
        bone2(0, -0.13f, 0, 0, -1.55f, 0, 0.014f, 0.075f, 0.006f, 0.012f, 0, 0, 1);
        if (glow && pose.drawAura && pose.aura > 0f) {
            // gaine de chakra le long de la lame + langues de feu
            tag = QuadSink.TAG_AURA_HOT;
            bone2(0, -0.14f, 0, 0, -1.55f, 0, 0.012f, 0.052f, 0.004f, 0.020f, 0, 0, 1);
            tag = QuadSink.TAG_BODY;
            for (int k = 0; k < 7; k++) {
                float y = -0.30f - k * 0.18f;
                flame(0, y, 0.03f, 0, 1f, 300 + k, 0.3f + 0.1f * (k % 3), pose.aura * 0.8f, pose.time);
            }
        }
    }

    private void armAngles(int s, int form, boolean sword, float[] o) {
        float p = pose.attack;
        float sway = (float) Math.sin(pose.limbPos * 0.6662f + (s > 0 ? Math.PI : 0)) * pose.limbSpeed * 35f;
        float bob = (float) Math.sin(pose.time * 0.07f + s) * 2f;
        float a1 = -14f + sway + bob, a2 = -18f, abd = 9f, wr = 0f;
        if (form >= 3 && sword) {
            a1 = -22f + bob;
            a2 = -62f;
            wr = -58f;
        }
        if (p >= 0f && pose.kind <= 1) {
            if (pose.kind == 1) {
                float e = smooth(Math.min(p / 0.55f, 1f));
                float rel = p > 0.85f ? (p - 0.85f) / 0.15f : 0f;
                a1 = lerp(a1, -88f + pose.pitch * 0.5f, e) + rel * 14f;
                a2 = lerp(a2, -8f, e);
                abd = lerp(abd, 14f, e);
                wr = lerp(wr, 0f, e);
            } else if (form >= 3) {
                float amp = sword ? 1f : 0.55f;
                float i1 = a1, i2 = a2, iw = wr;
                if (p < 0.38f) {
                    float e = 1f - (1f - p / 0.38f) * (1f - p / 0.38f);
                    a1 = lerp(i1, -160f * amp + i1 * (1 - amp), e);
                    a2 = lerp(i2, -30f, e);
                    wr = lerp(iw, -20f * (sword ? 1 : 0), e);
                } else if (p < 0.62f) {
                    float q = (p - 0.38f) / 0.24f;
                    float e = q * q;
                    a1 = lerp(-160f * amp + i1 * (1 - amp), -40f, e);
                    a2 = lerp(-30f, 0f, e);
                    wr = lerp(-20f * (sword ? 1 : 0), 0f, e);
                } else {
                    float e = smooth((p - 0.62f) / 0.38f);
                    a1 = lerp(-40f, i1, e);
                    a2 = lerp(0f, i2, e);
                    wr = lerp(0f, iw, e);
                }
            } else {
                float i1 = a1, i2 = a2;
                if (p < 0.35f) {
                    float e = smooth(p / 0.35f);
                    a1 = lerp(i1, -35f, e);
                    a2 = lerp(i2, -95f, e);
                } else if (p < 0.60f) {
                    float e = smooth((p - 0.35f) / 0.25f);
                    a1 = lerp(-35f, -92f, e);
                    a2 = lerp(-95f, -4f, e);
                } else {
                    float e = smooth((p - 0.60f) / 0.40f);
                    a1 = lerp(-92f, i1, e);
                    a2 = lerp(-4f, i2, e);
                }
            }
        }
        o[0] = a1;
        o[1] = a2;
        o[2] = abd;
        o[3] = wr;
    }

    private void arrow() {
        if (pose.attack < 0f || pose.kind != 1) {
            return;
        }
        float p = pose.attack;
        float charge = Math.min(1f, p / 0.7f);
        float fade = p > 0.88f ? Math.max(0f, 1f - (p - 0.88f) / 0.12f) : 1f;
        float len = (0.5f + 1.7f * charge) * fade;
        if (len < 0.05f) {
            return;
        }
        tag = QuadSink.TAG_ARROW;
        push();
        tr(0, 0.30f, 0);
        rx(pose.pitch);
        float w = 0.035f + 0.05f * charge;
        bone(0, 0, 0.30f, 0, 0, 0.30f + len, w, 0.004f);
        bone(0, 0, 0.30f, 0, 0, 0.12f, w * 0.2f, w * 0.9f);
        for (int s = 0; s < 4; s++) {
            push();
            rz(45f * s + pose.time * 6f);
            bone(0, w * 1.4f, 0.16f, 0, w * 3.2f * (0.4f + charge), 0.30f, w * 0.7f, w * 0.1f);
            pop();
        }
        pop();
        tag = QuadSink.TAG_BODY;
    }

    // ------------------------------------------------------------------ primitives

    private void push() {
        stack.push(cur.copy());
    }

    private void pop() {
        cur = stack.pop();
    }

    private void tr(float x, float y, float z) {
        cur.translate(x, y, z);
    }

    private void rx(float d) {
        cur.rotX(d);
    }

    private void rz(float d) {
        cur.rotZ(d);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    private void box(float x0, float y0, float z0, float x1, float y1, float z1) {
        float g = glow ? GLOW_PAD : 0f;
        x0 -= g;
        y0 -= g;
        z0 -= g;
        x1 += g;
        y1 += g;
        z1 += g;
        for (int k = 0; k < 8; k++) {
            float x = (k & 1) == 0 ? x0 : x1;
            float y = (k & 2) == 0 ? y0 : y1;
            float z = (k & 4) == 0 ? z0 : z1;
            cur.apply(x, y, z, pts, k * 3);
        }
        emit();
    }

    /** Piece lumineuse (yeux, noyau) : uniquement en passe de lueur. */
    private void brightBox(float x0, float y0, float z0, float x1, float y1, float z1) {
        if (!glow) {
            return;
        }
        int old = tag;
        tag = QuadSink.TAG_BRIGHT;
        box(x0, y0, z0, x1, y1, z1);
        tag = old;
    }

    /** Piece lumineuse symetrique : x de xa a xb cote s (+1 / -1). */
    private void brightBoxX(int s, float xa, float xb, float y0, float z0, float y1, float z1) {
        float lo = Math.min(s * xa, s * xb), hi = Math.max(s * xa, s * xb);
        brightBox(lo, y0, z0, hi, y1, z1);
    }

    private void bone(float ax, float ay, float az, float bx, float by, float bz, float t0, float t1) {
        float dy = by - ay;
        float dx = bx - ax, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        boolean vertical = len > 1e-5f && Math.abs(dy / len) > 0.9f;
        bone2(ax, ay, az, bx, by, bz, t0, t0, t1, t1, vertical ? 1 : 0, vertical ? 0 : 1, 0);
    }

    /** Barre a section rectangulaire effilee ; (hx,hy,hz) oriente l'axe "u". */
    private void bone2(float ax, float ay, float az, float bx, float by, float bz,
                       float tu0, float tv0, float tu1, float tv1, float hx, float hy, float hz) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-5f) {
            return;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        // u = hint x d
        float ux = hy * dz - hz * dy, uy = hz * dx - hx * dz, uz = hx * dy - hy * dx;
        float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (ul < 1e-4f) {
            // hint parallele a l'axe : on en choisit un autre
            hx = 1;
            hy = 0;
            hz = 0;
            ux = hy * dz - hz * dy;
            uy = hz * dx - hx * dz;
            uz = hx * dy - hy * dx;
            ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        }
        ux /= ul;
        uy /= ul;
        uz /= ul;
        // v = d x u
        float vx = dy * uz - dz * uy, vy = dz * ux - dx * uz, vz = dx * uy - dy * ux;
        if (glow) {
            tu0 = tu0 * GLOW_MUL + GLOW_PAD;
            tv0 = tv0 * GLOW_MUL + GLOW_PAD;
            tu1 = tu1 * GLOW_MUL + GLOW_PAD;
            tv1 = tv1 * GLOW_MUL + GLOW_PAD;
        }
        for (int k = 0; k < 8; k++) {
            boolean top = (k & 4) != 0;
            float su = (k & 1) != 0 ? 1f : -1f;
            float sv = (k & 2) != 0 ? 1f : -1f;
            float bxp = top ? bx : ax, byp = top ? by : ay, bzp = top ? bz : az;
            float hu = top ? tu1 : tu0, hv = top ? tv1 : tv0;
            cur.apply(bxp + ux * su * hu + vx * sv * hv,
                    byp + uy * su * hu + vy * sv * hv,
                    bzp + uz * su * hu + vz * sv * hv, pts, k * 3);
        }
        emit();
    }

    private void emit() {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        float cx = 0, cy = 0, cz = 0;
        for (int k = 0; k < 8; k++) {
            float x = pts[k * 3], y = pts[k * 3 + 1], z = pts[k * 3 + 2];
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
            cx += x;
            cy += y;
            cz += z;
        }
        if (pose.hasCam) {
            float dx = Math.max(Math.max(minX - pose.camX, 0f), pose.camX - maxX);
            float dy = Math.max(Math.max(minY - pose.camY, 0f), pose.camY - maxY);
            float dz = Math.max(Math.max(minZ - pose.camZ, 0f), pose.camZ - maxZ);
            if (dx * dx + dy * dy + dz * dz < pose.camMargin * pose.camMargin) {
                return;
            }
        }
        cx /= 8f;
        cy /= 8f;
        cz /= 8f;
        for (int[] f : FACES) {
            float fx = 0, fy = 0, fz = 0;
            for (int i = 0; i < 4; i++) {
                float x = pts[f[i] * 3], y = pts[f[i] * 3 + 1], z = pts[f[i] * 3 + 2];
                quad[i * 3] = x;
                quad[i * 3 + 1] = y;
                quad[i * 3 + 2] = z;
                fx += x;
                fy += y;
                fz += z;
            }
            fx = fx / 4f - cx;
            fy = fy / 4f - cy;
            fz = fz / 4f - cz;
            float e1x = quad[3] - quad[0], e1y = quad[4] - quad[1], e1z = quad[5] - quad[2];
            float e2x = quad[9] - quad[0], e2y = quad[10] - quad[1], e2z = quad[11] - quad[2];
            float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl < 1e-9f) {
                continue;
            }
            nx /= nl;
            ny /= nl;
            nz /= nl;
            if (nx * fx + ny * fy + nz * fz < 0f) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
            sink.quad(quad, nx, ny, nz, tag);
        }
    }
}
