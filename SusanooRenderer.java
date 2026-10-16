package com.susano.client;

import com.susano.SusanooColor;
import com.susano.SusanooEntity;
import com.susano.client.model.QuadSink;
import com.susano.client.model.SusanooModel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Dessine le Susanoo autour du joueur proprietaire, sans aucun retard :
 * la position vient du joueur (interpolee), pas de l'entite.
 */
public class SusanooRenderer extends EntityRenderer<SusanooEntity> {
    private static final Identifier TEXTURE = new Identifier("susanoo", "textures/entity/susanoo.png");
    /** Hauteur du centre de la poitrine au-dessus des pieds. */
    private static final float PIVOT = 1.15f;

    public SusanooRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.shadowRadius = 0f;
    }

    @Override
    public Identifier getTexture(SusanooEntity entity) {
        return TEXTURE;
    }

    @Override
    public boolean shouldRender(SusanooEntity entity, Frustum frustum, double x, double y, double z) {
        return entity.getOwner() != null && entity.scaleAnim >= 0f;
    }

    @Override
    public void render(SusanooEntity entity, float yaw, float td, MatrixStack ms,
                       VertexConsumerProvider vcp, int light) {
        PlayerEntity owner = entity.getOwner();
        if (owner == null || entity.scaleAnim < 0f) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d o = owner.getLerpedPos(td);
        Vec3d e = entity.getLerpedPos(td);
        float s = MathHelper.lerp(td, entity.prevScaleAnim, entity.scaleAnim);
        float headYaw = MathHelper.lerpAngleDegrees(td, owner.prevHeadYaw, owner.headYaw);
        float age = entity.age + td;
        float fade = Math.min(1f, age / 14f);

        SusanooModel.Pose pose = new SusanooModel.Pose();
        pose.form = entity.getForm();
        pose.time = age;
        pose.pitch = MathHelper.lerp(td, owner.prevPitch, owner.getPitch());
        pose.limbPos = owner.limbAnimator.getPos(td);
        pose.limbSpeed = Math.min(1f, owner.limbAnimator.getSpeed(td));
        int form = pose.form;
        pose.aura = SusanooEntity.AURA[form];
        float chestY = (float) (o.y + PIVOT);
        float gLerp = (float) MathHelper.lerp(td, entity.prevGroundY, entity.groundY);
        pose.ground = (gLerp - chestY) / s;
        boolean ringOk = form == 4 || o.y - gLerp < 6.0;
        pose.ringRadius = ringOk ? SusanooEntity.RING[form] : 0f;
        if (form == 4) {
            for (int i = 0; i < 2; i++) {
                pose.legSwing[i] = MathHelper.lerp(td, entity.prevLegSwing[i], entity.legSwing[i]);
                pose.legSide[i] = MathHelper.lerp(td, entity.prevLegSide[i], entity.legSide[i]);
                pose.legLift[i] = MathHelper.lerp(td, entity.prevLegLift[i], entity.legLift[i]);
                float fg = (float) MathHelper.lerp(td, entity.prevFootGround[i], entity.footGround[i]);
                pose.footGround[i] = (fg - chestY) / s;
            }
        }
        int ticks = entity.getAttackTicks();
        pose.kind = entity.getAttackKind();
        if (ticks > 0) {
            int len = Math.max(1, SusanooEntity.ANIM_LEN[pose.form][pose.kind]);
            pose.attack = MathHelper.clamp((len - (ticks - td)) / len, 0f, 1f);
        }

        // camera dans le repere du modele (pour masquer ce qui est trop pres)
        boolean fp = mc.options.getPerspective().isFirstPerson() && owner == mc.player;
        Vec3d cam = mc.gameRenderer.getCamera().getPos();
        double vx = cam.x - o.x, vy = cam.y - o.y - PIVOT, vz = cam.z - o.z;
        double rad = Math.toRadians(headYaw);
        double c = Math.cos(rad), sn = Math.sin(rad);
        pose.hasCam = true;
        pose.camX = (float) ((vx * c + vz * sn) / s);
        pose.camY = (float) (vy / s);
        pose.camZ = (float) ((-vx * sn + vz * c) / s);
        pose.camMargin = (fp ? Math.max(0.7f, 0.45f * s) : 0.4f) / s;

        SusanooColor col = SusanooColor.byId(entity.getColorId());
        int bl = Math.max(LightmapTextureManager.getBlockLightCoordinates(light), 7);
        int sl = LightmapTextureManager.getSkyLightCoordinates(light);
        int coreLight = LightmapTextureManager.pack(bl, sl);
        float flicker = 0.85f + 0.15f * (float) Math.sin(age * 0.35f);

        ms.push();
        ms.translate(o.x - e.x, o.y - e.y + PIVOT, o.z - e.z);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-headYaw));
        ms.scale(s, s, s);
        MatrixStack.Entry entry = ms.peek();

        // passe 1 : corps sombre
        Sink body = new Sink(vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE)), entry, age);
        body.light = coreLight;
        body.setColor(col.core[0] * fade, col.core[1] * fade, col.core[2] * fade, 255);
        new SusanooModel(body).build(pose, false);

        // passe 2 : coque lumineuse translucide, yeux et fleche
        Sink glow = new Sink(vcp.getBuffer(RenderLayer.getEntityTranslucentEmissive(TEXTURE)), entry, age);
        glow.light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        glow.glowColor = col.glow;
        glow.fade = fade;
        glow.flicker = flicker;
        glow.glowMode = true;
        new SusanooModel(glow).build(pose, true);

        // passes d'aura : coques sombres / colorees qui ondulent autour du corps (sans flammes ni anneau)
        int shells = form >= 4 ? 3 : form == 3 ? 2 : form == 2 ? 1 : 0;
        pose.drawAura = false;
        for (int i = 0; i < shells; i++) {
            float k = 1.03f + 0.035f * i + 0.012f * (float) Math.sin(age * 0.23f + i * 2.1f);
            ms.push();
            ms.scale(k, k * (1f + 0.03f * i), k);
            Sink sh = new Sink(vcp.getBuffer(RenderLayer.getEntityTranslucentEmissive(TEXTURE)), ms.peek(), age + i * 23f);
            sh.light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
            sh.glowColor = col.glow;
            sh.fade = fade;
            sh.flicker = 0.8f + 0.2f * (float) Math.sin(age * 0.29f + i * 1.7f);
            sh.glowMode = true;
            sh.shell = true;
            sh.shellIndex = i;
            new SusanooModel(sh).build(pose, true);
            ms.pop();
        }
        ms.pop();
    }

    /** Transforme les quads du modele en sommets Minecraft. */
    private static final class Sink implements QuadSink {
        final VertexConsumer vc;
        final Matrix4f pm;
        final Matrix3f nm;
        final float time;
        int light;
        int r = 255, g = 255, b = 255, a = 255;
        boolean glowMode;
        boolean shell;
        int shellIndex;
        int[] glowColor;
        float fade = 1f, flicker = 1f;
        int faceCounter;

        Sink(VertexConsumer vc, MatrixStack.Entry entry, float time) {
            this.vc = vc;
            this.pm = entry.getPositionMatrix();
            this.nm = entry.getNormalMatrix();
            this.time = time;
        }

        void setColor(float r, float g, float b, int a) {
            this.r = (int) r;
            this.g = (int) g;
            this.b = (int) b;
            this.a = a;
        }

        @Override
        public void quad(float[] p, float nx, float ny, float nz, int tag) {
            if (shell) {
                if (tag != TAG_BODY) {
                    return;
                }
                float al = (shellIndex == 1 ? 0.15f : 0.26f - 0.04f * shellIndex) * fade * flicker;
                if (shellIndex == 1) {
                    setColor(glowColor[0] * 0.8f, glowColor[1] * 0.8f, glowColor[2] * 0.8f, (int) (255 * al));
                } else {
                    setColor(10, 5, 18, (int) (255 * al));
                }
            } else if (glowMode) {
                if (tag == TAG_BODY) {
                    setColor(glowColor[0], glowColor[1], glowColor[2], (int) (255 * 0.40f * fade * flicker));
                } else if (tag == TAG_BRIGHT) {
                    setColor(glowColor[0] * 0.35f + 165, glowColor[1] * 0.35f + 165, glowColor[2] * 0.35f + 165, (int) (255 * fade));
                } else if (tag == TAG_AURA) {
                    setColor(12, 6, 20, (int) (255 * 0.60f * fade * flicker));
                } else if (tag == TAG_AURA_HOT) {
                    setColor(glowColor[0] * 0.7f + 60, glowColor[1] * 0.7f + 60, glowColor[2] * 0.7f + 60, (int) (255 * 0.85f * fade));
                } else {
                    setColor(glowColor[0] * 0.6f + 100, glowColor[1] * 0.6f + 100, glowColor[2] * 0.6f + 100, (int) (235 * fade));
                }
            }
            float h = (faceCounter++ * 0.61803399f) % 1f;
            float u0 = 0.5f * ((h + time * 0.003f) % 1f);
            float v0 = 0.5f * (((h * 1.7f) + time * 0.004f) % 1f);
            float[] us = {u0, u0 + 0.5f, u0 + 0.5f, u0};
            float[] vs = {v0, v0, v0 + 0.5f, v0 + 0.5f};
            for (int i = 0; i < 4; i++) {
                vc.vertex(pm, p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
                        .color(r, g, b, a)
                        .texture(us[i], vs[i])
                        .overlay(OverlayTexture.DEFAULT_UV)
                        .light(light)
                        .normal(nm, nx, ny, nz)
                        .next();
            }
        }
    }
}
