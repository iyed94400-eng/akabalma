package com.susano.client.model;

/**
 * Petite transformation affine (rotation 3x3 + translation), sans dependance a Minecraft.
 * Sert uniquement a placer les pieces du modele dans l'espace "modele".
 */
final class Mat4 {
    // r = matrice 3x3 en ligne (r[row*3+col]), t = translation
    final float[] r = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    final float[] t = {0, 0, 0};

    Mat4 copy() {
        Mat4 m = new Mat4();
        System.arraycopy(r, 0, m.r, 0, 9);
        System.arraycopy(t, 0, m.t, 0, 3);
        return m;
    }

    void set(Mat4 o) {
        System.arraycopy(o.r, 0, r, 0, 9);
        System.arraycopy(o.t, 0, t, 0, 3);
    }

    /** this = this * Translation(x,y,z) */
    void translate(float x, float y, float z) {
        t[0] += r[0] * x + r[1] * y + r[2] * z;
        t[1] += r[3] * x + r[4] * y + r[5] * z;
        t[2] += r[6] * x + r[7] * y + r[8] * z;
    }

    void scale(float s) {
        for (int i = 0; i < 9; i++) r[i] *= s;
    }

    /** this = this * Rx(deg) */
    void rotX(float deg) {
        double a = Math.toRadians(deg);
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        for (int row = 0; row < 3; row++) {
            float y = r[row * 3 + 1], z = r[row * 3 + 2];
            r[row * 3 + 1] = y * c + z * s;
            r[row * 3 + 2] = -y * s + z * c;
        }
    }

    /** this = this * Ry(deg) */
    void rotY(float deg) {
        double a = Math.toRadians(deg);
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        for (int row = 0; row < 3; row++) {
            float x = r[row * 3], z = r[row * 3 + 2];
            r[row * 3] = x * c - z * s;
            r[row * 3 + 2] = x * s + z * c;
        }
    }

    /** this = this * Rz(deg) */
    void rotZ(float deg) {
        double a = Math.toRadians(deg);
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        for (int row = 0; row < 3; row++) {
            float x = r[row * 3], y = r[row * 3 + 1];
            r[row * 3] = x * c + y * s;
            r[row * 3 + 1] = -x * s + y * c;
        }
    }

    void apply(float x, float y, float z, float[] out, int off) {
        out[off] = r[0] * x + r[1] * y + r[2] * z + t[0];
        out[off + 1] = r[3] * x + r[4] * y + r[5] * z + t[1];
        out[off + 2] = r[6] * x + r[7] * y + r[8] * z + t[2];
    }
}
