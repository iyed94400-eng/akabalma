package com.susano;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.Optional;
import java.util.UUID;

/**
 * Le Susanoo : entite purement "visuelle + etat", collee au joueur proprietaire.
 * Toute la logique de jeu est dans {@link SusanooManager}.
 */
public class SusanooEntity extends Entity {
    public static final int MAX_FORM = 4;
    /** Echelle du modele par forme (index = forme). */
    public static final float[] SCALE = {1f, 1.25f, 1.9f, 2.8f, 8.0f};
    public static final float[] MAX_SHIELD = {0f, 20f, 40f, 80f, 160f};
    /** Duree des animations (ticks) : [forme][0 = melee, 1 = speciale]. */
    public static final int[][] ANIM_LEN = {{0, 0, 0, 0}, {10, 16, 0, 0}, {12, 18, 0, 0}, {14, 22, 0, 0}, {20, 40, 26, 24}};
    /** Attaques : 0 = melee, 1 = speciale, 2 = pietinement, 3 = coup de pied (jambes : forme 4 seulement). */
    public static final int KIND_STOMP = 2, KIND_KICK = 3;
    /** Intensite de l'aura et rayon de l'anneau au sol (unites modele) par forme. */
    public static final float[] AURA = {0f, 0.35f, 0.55f, 0.8f, 1f};
    public static final float[] RING = {0f, 0.8f, 0.8f, 0.75f, 0.9f};
    /**
     * Forme parfaite : hauteur des pieds du joueur au-dessus du sol. Calee sur la geometrie des jambes
     * (echelle 8 : hanche 5,76 blocs sous la poitrine, jambes de 7,36 blocs -> genoux legerement flechis).
     */
    public static final double HOVER_HEIGHT = 11.0;
    /** Reglage : multiplie le nombre de particules de l'aura (0.5 = moitie moins, pour les PC modestes). */
    public static final float AURA_PARTICLES = 1.0f;

    private static final TrackedData<Optional<UUID>> OWNER =
            DataTracker.registerData(SusanooEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);
    private static final TrackedData<Integer> FORM =
            DataTracker.registerData(SusanooEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> COLOR =
            DataTracker.registerData(SusanooEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> ATTACK_TICKS =
            DataTracker.registerData(SusanooEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> ATTACK_KIND =
            DataTracker.registerData(SusanooEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> SHIELD =
            DataTracker.registerData(SusanooEntity.class, TrackedDataHandlerRegistry.FLOAT);

    // cote client : animation de croissance
    public float scaleAnim = -1f;
    public float prevScaleAnim = -1f;
    // cote client : sol et physique des jambes (index 0 = droite, 1 = gauche)
    public double groundY, prevGroundY;
    public final double[] footGround = new double[2], prevFootGround = new double[2];
    public final float[] legSwing = new float[2], prevLegSwing = new float[2], legVel = new float[2];
    public final float[] legSide = new float[2], prevLegSide = new float[2], legSideVel = new float[2];
    public final float[] legLift = new float[2], prevLegLift = new float[2];
    private boolean groundInit;

    // cote serveur
    public long cooldownUntil;
    public long lastHitTime;
    public double lastX, lastZ;

    public SusanooEntity(EntityType<? extends SusanooEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
    }

    @Override
    protected void initDataTracker() {
        this.dataTracker.startTracking(OWNER, Optional.empty());
        this.dataTracker.startTracking(FORM, 1);
        this.dataTracker.startTracking(COLOR, 0);
        this.dataTracker.startTracking(ATTACK_TICKS, 0);
        this.dataTracker.startTracking(ATTACK_KIND, 0);
        this.dataTracker.startTracking(SHIELD, 20f);
    }

    // ---- acces aux donnees synchronisees
    public UUID getOwnerUuid() { return this.dataTracker.get(OWNER).orElse(null); }
    public void setOwnerUuid(UUID id) { this.dataTracker.set(OWNER, Optional.ofNullable(id)); }
    public int getForm() { return this.dataTracker.get(FORM); }
    public void setForm(int f) { this.dataTracker.set(FORM, Math.max(1, Math.min(MAX_FORM, f))); }
    public int getColorId() { return this.dataTracker.get(COLOR); }
    public void setColorId(int c) { this.dataTracker.set(COLOR, c); }
    public int getAttackTicks() { return this.dataTracker.get(ATTACK_TICKS); }
    public void setAttackTicks(int t) { this.dataTracker.set(ATTACK_TICKS, t); }
    public int getAttackKind() { return this.dataTracker.get(ATTACK_KIND); }
    public void setAttackKind(int k) { this.dataTracker.set(ATTACK_KIND, k); }
    public float getShield() { return this.dataTracker.get(SHIELD); }
    public void setShield(float s) { this.dataTracker.set(SHIELD, s); }
    public float getMaxShield() { return MAX_SHIELD[getForm()]; }

    public PlayerEntity getOwner() {
        UUID id = getOwnerUuid();
        return id == null ? null : this.getWorld().getPlayerByUuid(id);
    }

    @Override
    public void tick() {
        super.tick();
        PlayerEntity owner = getOwner();
        if (owner != null) {
            this.setPosition(owner.getX(), owner.getY(), owner.getZ());
        }
        if (this.getWorld().isClient) {
            clientTick(owner);
        }
    }

    private void clientTick(PlayerEntity owner) {
        float target = SCALE[getForm()];
        prevScaleAnim = scaleAnim < 0 ? target * 0.3f : scaleAnim;
        if (scaleAnim < 0) {
            scaleAnim = target * 0.3f;
            prevScaleAnim = scaleAnim;
        }
        scaleAnim += (target - scaleAnim) * 0.14f;
        if (owner == null) {
            return;
        }
        updateGround(owner);
        if (getForm() == 4) {
            legPhysics(owner);
        } else {
            for (int i = 0; i < 2; i++) {
                prevLegSwing[i] = legSwing[i] = 0f;
                prevLegSide[i] = legSide[i] = 0f;
                prevLegLift[i] = legLift[i] = 0f;
                legVel[i] = legSideVel[i] = 0f;
            }
        }
        auraParticles(owner);
    }

    /** Hauteur du sol sous le joueur (et sous chaque pied en forme parfaite). */
    private void updateGround(PlayerEntity owner) {
        double g = groundBelow(owner.getX(), owner.getY() + 0.5, owner.getZ(), owner.getY());
        if (!groundInit) {
            groundY = g;
            footGround[0] = footGround[1] = g;
            groundInit = true;
        }
        prevGroundY = groundY;
        groundY += (g - groundY) * 0.5;
        if (getForm() == 4) {
            double yaw = Math.toRadians(owner.headYaw);
            double c = Math.cos(yaw), sn = Math.sin(yaw);
            float s = scaleAnim;
            for (int i = 0; i < 2; i++) {
                double mx = (i == 0 ? -1 : 1) * com.susano.client.model.SusanooModel.HIP_X * s;
                double fx = owner.getX() + mx * c;
                double fz = owner.getZ() + mx * sn;
                double fg = groundBelow(fx, owner.getY() + 0.5, fz, g);
                prevFootGround[i] = footGround[i];
                footGround[i] += (fg - footGround[i]) * 0.45;
            }
        } else {
            for (int i = 0; i < 2; i++) {
                prevFootGround[i] = footGround[i];
                footGround[i] = groundY;
            }
        }
    }

    private double groundBelow(double x, double fromY, double z, double fallback) {
        Vec3d from = new Vec3d(x, fromY, z);
        BlockHitResult hit = this.getWorld().raycast(new RaycastContext(from, from.add(0, -96, 0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getPos().y : fallback;
    }

    /** Les jambes ont de l'inertie : ressorts amortis, trainent derriere le mouvement et reviennent en oscillant. */
    private void legPhysics(PlayerEntity owner) {
        double yaw = Math.toRadians(owner.headYaw);
        double vx = owner.getX() - owner.prevX, vz = owner.getZ() - owner.prevZ;
        float fwd = (float) (-vx * Math.sin(yaw) + vz * Math.cos(yaw));
        float side = (float) (vx * Math.cos(yaw) + vz * Math.sin(yaw));
        float reach = (com.susano.client.model.SusanooModel.LEG_1 + com.susano.client.model.SusanooModel.LEG_2) * scaleAnim;
        float hipH = (float) (owner.getY() + 1.15 - 0.72 * scaleAnim - groundY);
        // pieds au sol : vraie marche ; en l'air : les jambes pendent et se balancent
        float planted = hipH <= reach + 0.5f ? 1f : 0.3f;
        float speed = Math.min(1f, owner.limbAnimator.getSpeed(1f)) * planted;
        float phase = owner.limbAnimator.getPos(1f) * 0.6662f;
        for (int i = 0; i < 2; i++) {
            float ph = phase + (i == 0 ? 0f : (float) Math.PI);
            prevLegSwing[i] = legSwing[i];
            prevLegSide[i] = legSide[i];
            prevLegLift[i] = legLift[i];
            float tSwing = (float) Math.sin(ph) * speed * 24f - fwd * 70f;
            tSwing = Math.max(-45f, Math.min(45f, tSwing));
            legVel[i] = legVel[i] * 0.72f + (tSwing - legSwing[i]) * 0.2f;
            legSwing[i] += legVel[i];
            float tSide = Math.max(-18f, Math.min(18f, side * 45f)) + (float) Math.sin(this.age * 0.07f + i * 2f) * 1.2f;
            legSideVel[i] = legSideVel[i] * 0.78f + (tSide - legSide[i]) * 0.16f;
            legSide[i] += legSideVel[i];
            legLift[i] = Math.max(0f, (float) Math.cos(ph)) * speed;
        }
    }

    private static final DustParticleEffect DARK_DUST = new DustParticleEffect(new Vector3f(0.04f, 0.02f, 0.07f), 3.2f);

    /** Aura : fumee noire, flammes, ames, etincelles et anneau de braises au sol. */
    private void auraParticles(PlayerEntity owner) {
        int f = getForm();
        SusanooColor c = SusanooColor.byId(getColorId());
        DustParticleEffect glow = new DustParticleEffect(
                new Vector3f(c.glow[0] / 255f, c.glow[1] / 255f, c.glow[2] / 255f), 1.6f + 0.2f * f);
        float s = scaleAnim;
        double cx = owner.getX(), cy = owner.getY() + 1.15, cz = owner.getZ();
        double top = cy + (f == 4 ? 1.35 : 1.0) * s;
        double bottom = f == 4 ? groundY + 0.4 : Math.max(owner.getY(), cy - 0.9 * s);
        var flame = c.id == 1 ? ParticleTypes.FLAME : ParticleTypes.SOUL_FIRE_FLAME;
        int smoke = Math.round((2 + f * 5) * AURA_PARTICLES);
        for (int i = 0; i < smoke; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = (0.22 + random.nextDouble() * 0.6) * s;
            double y = bottom + random.nextDouble() * (top - bottom);
            double x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r;
            if (i % 3 == 0) {
                this.getWorld().addParticle(ParticleTypes.LARGE_SMOKE, x, y, z, 0, 0.03 + random.nextDouble() * 0.05, 0);
            } else {
                this.getWorld().addParticle(DARK_DUST, x, y, z, 0, 0.04 + random.nextDouble() * 0.05, 0);
            }
        }
        int hot = Math.round((1 + f * 2) * AURA_PARTICLES);
        for (int i = 0; i < hot; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = (0.2 + random.nextDouble() * 0.55) * s;
            double y = bottom + random.nextDouble() * (top - bottom);
            double x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r;
            this.getWorld().addParticle(i % 2 == 0 ? flame : glow, x, y, z, 0, 0.05 + random.nextDouble() * 0.06, 0);
        }
        if (f >= 2 && random.nextFloat() < 0.15f * f * AURA_PARTICLES) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = (0.3 + random.nextDouble() * 0.5) * s;
            this.getWorld().addParticle(ParticleTypes.SOUL, cx + Math.cos(a) * r, bottom + random.nextDouble() * (top - bottom),
                    cz + Math.sin(a) * r, 0, 0.03, 0);
        }
        if (f == 4) {
            for (int i = 0; i < Math.round(3 * AURA_PARTICLES); i++) {
                double a = random.nextDouble() * Math.PI * 2;
                double r = (0.3 + random.nextDouble() * 0.7) * s;
                this.getWorld().addParticle(ParticleTypes.ELECTRIC_SPARK, cx + Math.cos(a) * r,
                        cy + (random.nextDouble() - 0.5) * 2.0 * s, cz + Math.sin(a) * r,
                        (random.nextDouble() - 0.5) * 0.6, (random.nextDouble() - 0.5) * 0.6, (random.nextDouble() - 0.5) * 0.6);
            }
        }
        if (f >= 2) {
            double ring = RING[f] * s;
            int n = Math.round((f * 3) * AURA_PARTICLES);
            for (int i = 0; i < n; i++) {
                double a = random.nextDouble() * Math.PI * 2;
                double r = ring * (0.8 + random.nextDouble() * 0.3);
                double x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r;
                this.getWorld().addParticle(i % 3 == 0 ? flame : ParticleTypes.LARGE_SMOKE, x, groundY + 0.1, z,
                        0, 0.05 + random.nextDouble() * 0.08, 0);
            }
        }
    }

    // ---- une entite "fantome" : jamais ciblee, jamais sauvegardee
    @Override public boolean canHit() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean shouldSave() { return false; }
    @Override public boolean isInvulnerableTo(DamageSource source) { return true; }
    @Override public boolean damage(DamageSource source, float amount) { return false; }
    @Override public boolean isAttackable() { return false; }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) { }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) { }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this);
    }
}
