package com.susano;

import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.RaycastContext;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Logique serveur : invocation, formes, attaques, bouclier de chakra, physique. */
public final class SusanooManager {
    private static final Map<UUID, SusanooEntity> ACTIVE = new HashMap<>();
    private static final UUID KB_ID = UUID.fromString("5b0f3f6e-2f4a-4c1e-9d55-2d6b7c1a8e01");

    // statistiques par forme (index = forme 1..4)
    private static final double[] REACH = {0, 4.5, 6, 9, 16};
    private static final float[] DMG = {0, 6, 10, 16, 30};
    private static final float[] KB = {0, 0.8f, 1.1f, 1.4f, 2.0f};
    private static final double[] ARC = {0, 0.35, 0.2, 0.0, -0.3};
    private static final float[] MELEE_BLOCK_HARDNESS = {0, 0f, 0.8f, 2.0f, 6.0f};
    private static final int[] MELEE_CD = {0, 4, 6, 8, 12};

    private static final double[] BEAM_RANGE = {0, 40, 55, 70, 110};
    private static final double[] BEAM_RADIUS = {0, 0.8, 1.0, 1.6, 3.0};
    private static final float[] BEAM_DMG = {0, 12, 18, 28, 60};
    private static final double[] BLAST_RADIUS = {0, 2, 3, 5, 9};
    private static final float[] BLAST_HARDNESS = {0, 1.5f, 3f, 6f, 6f};
    private static final int[] SPECIAL_CD = {0, 50, 60, 80, 140};

    // jambes (forme parfaite)
    private static final float STOMP_DMG = 45f, KICK_DMG = 38f, TRAMPLE_DMG = 14f;
    private static final double STOMP_WAVE = 15.0, STOMP_CRATER = 6.5, KICK_REACH = 20.0;
    private static final int STOMP_CD = 30, KICK_CD = 24;
    // aura : rayon d'effet par forme, debuff sur les creatures proches, sons d'ambiance
    private static final double[] AURA_R = {0, 4, 7, 12, 24};
    private static final boolean AURA_DEBUFF = true;
    private static final boolean AURA_SOUNDS = true;
    /** Joueurs dont le vol a ete accorde par la forme parfaite (a restituer ensuite). */
    private static final Set<UUID> FLYING = new HashSet<>();

    private SusanooManager() { }

    // ------------------------------------------------------------ etat

    public static SusanooEntity get(ServerPlayerEntity p) {
        SusanooEntity e = ACTIVE.get(p.getUuid());
        if (e != null && e.isRemoved()) {
            ACTIVE.remove(p.getUuid());
            clearModifier(p);
            return null;
        }
        return e;
    }

    public static void clear(MinecraftServer server) {
        for (Map.Entry<UUID, SusanooEntity> en : new HashMap<>(ACTIVE).entrySet()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(en.getKey());
            if (p != null) {
                finish(p, en.getValue());
            } else {
                en.getValue().discard();
            }
        }
        ACTIVE.clear();
        FLYING.clear();
    }

    // ------------------------------------------------------------ invocation

    /** Clic droit sur un Mangekyo : invoque, change de couleur ou renvoie. */
    public static void toggleWith(ServerPlayerEntity p, SusanooColor color) {
        SusanooEntity e = get(p);
        if (e == null) {
            activate(p, color);
        } else if (e.getColorId() != color.id) {
            e.setColorId(color.id);
            playAt(p, SoundEvents.BLOCK_BEACON_POWER_SELECT, 1f, 1.2f);
        } else {
            dismiss(p);
        }
    }

    /** Touche G. */
    public static void toggleKey(ServerPlayerEntity p) {
        if (get(p) != null) {
            dismiss(p);
            return;
        }
        SusanooColor c = findColor(p);
        if (c == null) {
            p.sendMessage(Text.translatable("message.susanoo.need_mangekyo"), true);
            return;
        }
        activate(p, c);
    }

    private static SusanooColor findColor(ServerPlayerEntity p) {
        SusanooColor c = colorOf(p.getMainHandStack());
        if (c == null) c = colorOf(p.getOffHandStack());
        if (c == null) {
            for (ItemStack s : p.getInventory().main) {
                c = colorOf(s);
                if (c != null) break;
            }
        }
        return c;
    }

    private static SusanooColor colorOf(ItemStack s) {
        return s.getItem() instanceof SusanooItem si ? si.getColor() : null;
    }

    private static void activate(ServerPlayerEntity p, SusanooColor color) {
        ServerWorld w = p.getServerWorld();
        SusanooEntity e = SusanooMod.SUSANOO.create(w);
        if (e == null) return;
        e.setOwnerUuid(p.getUuid());
        e.setColorId(color.id);
        e.setForm(1);
        e.setShield(SusanooEntity.MAX_SHIELD[1]);
        e.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ(), p.getYaw(), 0f);
        e.lastX = p.getX();
        e.lastZ = p.getZ();
        w.spawnEntity(e);
        ACTIVE.put(p.getUuid(), e);
        applyModifier(p, 1);
        playAt(p, SoundEvents.BLOCK_BEACON_ACTIVATE, 1.5f, 0.7f);
        p.sendMessage(Text.translatable("message.susanoo.summoned"), true);
    }

    public static void dismiss(ServerPlayerEntity p) {
        SusanooEntity e = ACTIVE.remove(p.getUuid());
        if (e != null) {
            finish(p, e);
        }
    }

    private static void finish(ServerPlayerEntity p, SusanooEntity e) {
        if (p != null) {
            clearModifier(p);
            if (FLYING.remove(p.getUuid())) {
                restoreFlight(p);
            }
            playAt(p, SoundEvents.BLOCK_BEACON_DEACTIVATE, 1.5f, 0.8f);
        }
        e.discard();
    }

    /** Touche H : forme suivante (delta = +1) ou precedente (-1). */
    public static void changeForm(ServerPlayerEntity p, int delta) {
        SusanooEntity e = get(p);
        if (e == null) return;
        int old = e.getForm();
        int nf = Math.max(1, Math.min(SusanooEntity.MAX_FORM, old + delta));
        if (nf == old) return;
        float ratio = e.getShield() / SusanooEntity.MAX_SHIELD[old];
        e.setForm(nf);
        e.setShield(Math.max(1f, ratio * SusanooEntity.MAX_SHIELD[nf]));
        e.setAttackTicks(0);
        applyModifier(p, nf);
        playAt(p, nf > old ? SoundEvents.ENTITY_WITHER_SPAWN : SoundEvents.BLOCK_BEACON_DEACTIVATE, 0.6f, nf > old ? 1.4f : 1.0f);
        p.sendMessage(Text.translatable("message.susanoo.form." + nf), true);
        syncFlight(p, e);
        if (nf > old) {
            transformBurst(p, e, nf);
        }
    }

    /** Explosion d'aura a chaque montee de forme ; foudre decorative (sans degats) a partir de l'armure. */
    private static void transformBurst(ServerPlayerEntity p, SusanooEntity e, int form) {
        ServerWorld w = p.getServerWorld();
        SusanooColor col = SusanooColor.byId(e.getColorId());
        Vec3d c = p.getPos().add(0, 1.2, 0);
        double spread = 0.6 + form * 0.7;
        w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y, c.z, 30 + form * 20, spread, spread, spread, 0.12);
        w.spawnParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 20 + form * 15, spread, spread, spread, 0.08);
        dust(w, col, c, 40 + form * 30, spread);
        if (form >= 3) {
            LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w);
            if (bolt != null) {
                bolt.setPosition(p.getX(), p.getY(), p.getZ());
                bolt.setCosmetic(true);
                w.spawnEntity(bolt);
            }
        }
    }

    // ------------------------------------------------------------ physique

    private static void applyModifier(ServerPlayerEntity p, int form) {
        EntityAttributeInstance a = p.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
        if (a == null) return;
        a.removeModifier(KB_ID);
        double v = form >= 3 ? 1.0 : form == 2 ? 0.5 : 0.0;
        if (v > 0) {
            a.addTemporaryModifier(new EntityAttributeModifier(KB_ID, "susanoo", v, EntityAttributeModifier.Operation.ADDITION));
        }
    }

    private static void clearModifier(ServerPlayerEntity p) {
        EntityAttributeInstance a = p.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
        if (a != null) a.removeModifier(KB_ID);
    }

    /** Appele par ALLOW_DAMAGE. Retourne true si les degats doivent passer. */
    public static boolean absorb(ServerPlayerEntity p, DamageSource src, float amount) {
        SusanooEntity e = get(p);
        if (e == null) return true;
        if (src.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;
        if (src.isOf(DamageTypes.FALL) || src.isOf(DamageTypes.FLY_INTO_WALL)) return false;
        if (src.isOf(DamageTypes.IN_FIRE) || src.isOf(DamageTypes.ON_FIRE)
                || src.isOf(DamageTypes.LAVA) || src.isOf(DamageTypes.HOT_FLOOR)) {
            p.extinguish();
            return false;
        }
        e.lastHitTime = p.getWorld().getTime();
        float left = e.getShield() - amount;
        playAt(p, SoundEvents.ENTITY_IRON_GOLEM_DAMAGE, 1f, 0.6f);
        if (left <= 0f) {
            e.setShield(0f);
            ACTIVE.remove(p.getUuid());
            playAt(p, SoundEvents.ITEM_SHIELD_BREAK, 1.5f, 0.6f);
            p.sendMessage(Text.translatable("message.susanoo.broken"), true);
            finish(p, e);
        } else {
            e.setShield(left);
        }
        return false;
    }

    // ------------------------------------------------------------ tick

    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, SusanooEntity>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, SusanooEntity> en = it.next();
            SusanooEntity e = en.getValue();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(en.getKey());
            if (p == null || !p.isAlive() || p.isSpectator() || e.isRemoved() || e.getWorld() != p.getWorld()) {
                it.remove();
                finish(p, e);
                continue;
            }
            int form = e.getForm();
            if (!p.isCreative()) {
                p.addExhaustion(0.003f * form);
                if (p.getHungerManager().getFoodLevel() <= 0) {
                    it.remove();
                    p.sendMessage(Text.translatable("message.susanoo.exhausted"), true);
                    finish(p, e);
                    continue;
                }
            }
            long now = p.getWorld().getTime();
            syncFlight(p, e);
            double moved = Math.hypot(p.getX() - e.lastX, p.getZ() - e.lastZ);
            e.lastX = p.getX();
            e.lastZ = p.getZ();
            if (form == 4 && moved > 0.1 && now % 6 == 0) {
                trample(p, e, now);
            }
            if (AURA_DEBUFF && now % 10 == 0) {
                auraDebuff(p, form);
            }
            if (AURA_SOUNDS) {
                auraSounds(p, form, now);
            }
            float max = e.getMaxShield();
            if (now - e.lastHitTime > 100 && e.getShield() < max) {
                e.setShield(Math.min(max, e.getShield() + 0.05f * form));
            }
            int t = e.getAttackTicks();
            if (t > 0) {
                t--;
                e.setAttackTicks(t);
                int kind = e.getAttackKind();
                int len = SusanooEntity.ANIM_LEN[form][kind];
                int impact = switch (kind) {
                    case 0 -> len / 2;
                    case 1 -> len - 4;
                    case 2 -> (int) (len * 0.6);
                    default -> len / 2;
                };
                if (len - t == impact) {
                    switch (kind) {
                        case 0 -> meleeImpact(p, e);
                        case 1 -> beamImpact(p, e);
                        case 2 -> stompImpact(p, e);
                        default -> kickImpact(p, e);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ attaques (touches R et V)

    public static void attack(ServerPlayerEntity p) {
        SusanooEntity e = get(p);
        if (e == null || e.getAttackTicks() > 0) return;
        long now = p.getWorld().getTime();
        if (now < e.cooldownUntil) return;
        int f = e.getForm();
        int len = SusanooEntity.ANIM_LEN[f][0];
        e.setAttackKind(0);
        e.setAttackTicks(len);
        e.cooldownUntil = now + len + MELEE_CD[f];
        playAt(p, f >= 3 ? SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP : SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 1.2f, 1.2f - f * 0.15f);
    }

    public static void special(ServerPlayerEntity p) {
        SusanooEntity e = get(p);
        if (e == null || e.getAttackTicks() > 0) return;
        long now = p.getWorld().getTime();
        if (now < e.cooldownUntil) return;
        int f = e.getForm();
        int len = SusanooEntity.ANIM_LEN[f][1];
        e.setAttackKind(1);
        e.setAttackTicks(len);
        e.cooldownUntil = now + len + SPECIAL_CD[f];
        playAt(p, SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.5f, 0.6f);
    }

    /** Touches C (piétinement) et B (coup de pied) : jambes du Susanoo parfait uniquement. */
    public static void legAttack(ServerPlayerEntity p, int kind) {
        SusanooEntity e = get(p);
        if (e == null) return;
        if (e.getForm() != 4) {
            p.sendMessage(Text.translatable("message.susanoo.legs_only"), true);
            return;
        }
        if (e.getAttackTicks() > 0) return;
        long now = p.getWorld().getTime();
        if (now < e.cooldownUntil) return;
        int len = SusanooEntity.ANIM_LEN[4][kind];
        e.setAttackKind(kind);
        e.setAttackTicks(len);
        e.cooldownUntil = now + len + (kind == SusanooEntity.KIND_STOMP ? STOMP_CD : KICK_CD);
        playAt(p, SoundEvents.ENTITY_IRON_GOLEM_ATTACK, 1.5f, 0.5f);
    }

    // ------------------------------------------------------------ vol stationnaire (forme parfaite)

    /** En forme parfaite le joueur flotte au-dessus des jambes ; sinon on restitue ses capacites. */
    private static void syncFlight(ServerPlayerEntity p, SusanooEntity e) {
        UUID id = p.getUuid();
        if (e.getForm() == 4) {
            boolean first = FLYING.add(id);
            PlayerAbilities ab = p.getAbilities();
            if (first || !ab.allowFlying || !ab.flying) {
                ab.allowFlying = true;
                ab.flying = true;
                p.sendAbilitiesUpdate();
            }
            p.fallDistance = 0f;
        } else if (FLYING.remove(id)) {
            restoreFlight(p);
        }
    }

    private static void restoreFlight(ServerPlayerEntity p) {
        p.interactionManager.getGameMode().setAbilities(p.getAbilities());
        p.sendAbilitiesUpdate();
        if (!p.isCreative() && !p.isSpectator()) {
            p.fallDistance = 0f;
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 160, 0, false, false, false));
        }
    }

    // ------------------------------------------------------------ jambes : piétinement, coup de pied, écrasement

    /** Point du sol sous le joueur, decale de (dx, dz) ; sans sol, 12 blocs plus bas. */
    private static Vec3d groundBelow(ServerWorld w, ServerPlayerEntity p, double dx, double dz) {
        Vec3d from = p.getPos().add(dx, 0.5, dz);
        BlockHitResult h = w.raycast(new RaycastContext(from, from.add(0, -96, 0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
        return h.getType() == HitResult.Type.BLOCK ? h.getPos() : new Vec3d(from.x, p.getY() - 12, from.z);
    }

    private static Vec3d rightOf(Vec3d forward) {
        return new Vec3d(-forward.z, 0, forward.x);
    }

    /** Pas du geant : ecrase la vegetation et blesse ce qui se trouve sous le pied qui se pose. */
    private static void trample(ServerPlayerEntity p, SusanooEntity e, long now) {
        ServerWorld w = p.getServerWorld();
        Vec3d look = Vec3d.fromPolar(0f, p.getYaw());
        Vec3d right = rightOf(look);
        double side = ((now / 6) % 2 == 0 ? -1 : 1) * 1.4;
        Vec3d g = groundBelow(w, p, right.x * side, right.z * side);
        if (p.getY() - g.y > SusanooEntity.HOVER_HEIGHT + 4) return;
        SusanooColor col = SusanooColor.byId(e.getColorId());
        Box box = new Box(g, g).expand(4.5, 0, 4.5).stretch(0, 4.5, 0).stretch(0, -1, 0);
        for (LivingEntity t : w.getEntitiesByClass(LivingEntity.class, box, x -> canTarget(p, x))) {
            strike(w, p, t, TRAMPLE_DMG, 0.9f);
        }
        w.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, g.x, g.y + 0.2, g.z, 4, 1.5, 0.1, 1.5, 0.01);
        dust(w, col, g.add(0, 0.3, 0), 10, 1.8);
        w.playSound(null, g.x, g.y, g.z, SoundEvents.ENTITY_IRON_GOLEM_STEP, SoundCategory.PLAYERS, 2.5f, 0.5f);
        if (w.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
            BlockPos base = BlockPos.ofFloored(g);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = 0; dy <= 3; dy++) {
                        destroy(w, p, base.add(dx, dy, dz), 0.3f);
                    }
                }
            }
        }
    }

    /** Touche C : le pied droit s'abat, cratere + onde de choc qui projette tout autour. */
    private static void stompImpact(ServerPlayerEntity p, SusanooEntity e) {
        ServerWorld w = p.getServerWorld();
        SusanooColor col = SusanooColor.byId(e.getColorId());
        Vec3d look = Vec3d.fromPolar(0f, p.getYaw());
        Vec3d right = rightOf(look);
        Vec3d foot = groundBelow(w, p, right.x * 1.4 + look.x * 2.0, right.z * 1.4 + look.z * 2.0);
        Vec3d c = foot.add(0, 0.5, 0);
        blast(w, p, c, STOMP_CRATER, STOMP_DMG, 6f, col);
        Box wave = new Box(c, c).expand(STOMP_WAVE, 8, STOMP_WAVE);
        for (LivingEntity t : w.getEntitiesByClass(LivingEntity.class, wave, x -> canTarget(p, x))) {
            double d = Math.hypot(t.getX() - c.x, t.getZ() - c.z);
            if (d > STOMP_WAVE) continue;
            float k = (float) (1.0 - d / STOMP_WAVE);
            t.damage(w.getDamageSources().playerAttack(p), STOMP_DMG * 0.5f * k);
            Vec3d push = new Vec3d(t.getX() - c.x, 0, t.getZ() - c.z).normalize().multiply(0.6 + 1.6 * k);
            t.addVelocity(push.x, 0.5 + 0.6 * k, push.z);
            t.velocityModified = true;
            t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 2));
        }
        for (double r = 3; r <= STOMP_WAVE; r += 3) {
            int n = (int) (14 + r * 4);
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n;
                double x = c.x + Math.cos(a) * r, z = c.z + Math.sin(a) * r;
                w.spawnParticles(i % 2 == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.CLOUD, x, c.y, z, 1, 0.1, 0.1, 0.1, 0.05);
            }
        }
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 2f, 0.5f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_IRON_GOLEM_DAMAGE, SoundCategory.PLAYERS, 3f, 0.4f);
    }

    /** Touche B : grand coup de pied balayant devant le geant, projette les ennemis loin et haut. */
    private static void kickImpact(ServerPlayerEntity p, SusanooEntity e) {
        ServerWorld w = p.getServerWorld();
        SusanooColor col = SusanooColor.byId(e.getColorId());
        Vec3d look = Vec3d.fromPolar(0f, p.getYaw());
        Vec3d base = groundBelow(w, p, 0, 0);
        Vec3d origin = new Vec3d(base.x, base.y + 1.5, base.z);
        Box box = new Box(origin, origin).expand(KICK_REACH, 6, KICK_REACH);
        for (LivingEntity t : w.getEntitiesByClass(LivingEntity.class, box, x -> canTarget(p, x))) {
            Vec3d flat = new Vec3d(t.getX() - origin.x, 0, t.getZ() - origin.z);
            double fl = flat.length();
            if (fl - t.getWidth() / 2.0 > KICK_REACH) continue;
            if (fl > 1.5 && flat.normalize().dotProduct(look) < 0.25) continue;
            strike(w, p, t, KICK_DMG, 3.2f);
            t.addVelocity(look.x * 1.2, 0.9, look.z * 1.2);
            t.velocityModified = true;
        }
        breakSector(w, p, BlockPos.ofFloored(base), look, KICK_REACH * 0.6, 0.25, 6f, 4);
        for (int i = 0; i < 6; i++) {
            double d = 3 + i * 3;
            Vec3d at = base.add(look.multiply(d)).add(0, 1.5, 0);
            w.spawnParticles(ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 2, d * 0.25, 0.5, d * 0.25, 0);
            dust(w, col, at, 14, d * 0.25);
        }
        Vec3d snd = base.add(look.multiply(6));
        w.playSound(null, snd.x, snd.y, snd.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 2.5f, 0.6f);
        w.playSound(null, snd.x, snd.y, snd.z, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 2f, 0.5f);
    }

    // ------------------------------------------------------------ aura : terreur et sons

    /** L'aura ralentit et affaiblit les creatures proches (pas les joueurs). */
    private static void auraDebuff(ServerPlayerEntity p, int form) {
        double r = AURA_R[form];
        Box box = p.getBoundingBox().expand(r, r * 0.8, r);
        for (LivingEntity t : p.getServerWorld().getEntitiesByClass(LivingEntity.class, box,
                x -> canTarget(p, x) && !(x instanceof PlayerEntity))) {
            t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, form >= 3 ? 1 : 0, false, false));
            t.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 40, 0, false, false));
        }
    }

    /** Battement de coeur sourd (armure / parfait) et grondement lointain (parfait). */
    private static void auraSounds(ServerPlayerEntity p, int form, long now) {
        if (form == 3 && now % 40 == 0) {
            playAt(p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.2f, 0.7f);
        } else if (form == 4) {
            if (now % 24 == 0) playAt(p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.8f, 0.5f);
            if (now % 160 == 0) playAt(p, SoundEvents.ENTITY_WARDEN_AMBIENT, 1.0f, 0.6f);
        }
    }

    private static void meleeImpact(ServerPlayerEntity p, SusanooEntity e) {
        ServerWorld w = p.getServerWorld();
        int f = e.getForm();
        double reach = REACH[f];
        Vec3d look = Vec3d.fromPolar(0f, p.getYaw());
        Vec3d origin = p.getPos().add(0, 1.0, 0);
        Box box = new Box(origin, origin).expand(reach, reach * 0.7, reach);
        for (LivingEntity t : w.getEntitiesByClass(LivingEntity.class, box, x -> canTarget(p, x))) {
            Vec3d to = t.getPos().add(0, t.getHeight() / 2.0, 0).subtract(origin);
            Vec3d flat = new Vec3d(to.x, 0, to.z);
            double fl = flat.length();
            if (fl - t.getWidth() / 2.0 > reach) continue;
            if (fl > 0.8 && flat.normalize().dotProduct(look) < ARC[f]) continue;
            strike(w, p, t, DMG[f], KB[f]);
        }
        breakSector(w, p, look, reach, ARC[f], MELEE_BLOCK_HARDNESS[f]);
        Vec3d fx = p.getPos().add(look.multiply(Math.min(reach, 6) * 0.6)).add(0, 1.0 + f * 0.3, 0);
        w.spawnParticles(ParticleTypes.SWEEP_ATTACK, fx.x, fx.y, fx.z, 3 + f * 2, reach * 0.3, 0.4, reach * 0.3, 0);
        dust(w, SusanooColor.byId(e.getColorId()), fx, 12 + f * 10, reach * 0.35);
        playAt(p, SoundEvents.ENTITY_GENERIC_EXPLODE, 0.6f + f * 0.2f, 1.4f - f * 0.2f);
    }

    private static void beamImpact(ServerPlayerEntity p, SusanooEntity e) {
        ServerWorld w = p.getServerWorld();
        int f = e.getForm();
        SusanooColor col = SusanooColor.byId(e.getColorId());
        Vec3d start = p.getEyePos();
        Vec3d dir = p.getRotationVec(1f);
        Vec3d end = start.add(dir.multiply(BEAM_RANGE[f]));
        BlockHitResult hit = w.raycast(new RaycastContext(start, end,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
        boolean hitBlock = hit.getType() == HitResult.Type.BLOCK;
        Vec3d stop = hitBlock ? hit.getPos() : end;
        double len = start.distanceTo(stop);
        double rad = BEAM_RADIUS[f];
        Set<LivingEntity> done = new HashSet<>();
        for (double d = 1.5; d <= len; d += 0.8) {
            Vec3d c = start.add(dir.multiply(d));
            dust(w, col, c, 2 + f * 2, rad * 0.3);
            Box b = new Box(c, c).expand(rad);
            for (LivingEntity t : w.getEntitiesByClass(LivingEntity.class, b, x -> canTarget(p, x))) {
                if (done.add(t)) {
                    strike(w, p, t, BEAM_DMG[f], 1.2f);
                }
            }
        }
        if (hitBlock || f >= 3) {
            blast(w, p, stop, BLAST_RADIUS[f], BEAM_DMG[f] * 0.6f, BLAST_HARDNESS[f], col);
        }
        playAt(p, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 0.8f + 0.1f * (4 - f));
    }

    private static boolean canTarget(ServerPlayerEntity owner, LivingEntity x) {
        if (x == owner || !x.isAlive()) return false;
        return !(x instanceof TameableEntity te && te.isOwner(owner));
    }

    private static void strike(ServerWorld w, ServerPlayerEntity p, LivingEntity t, float dmg, float kb) {
        t.damage(w.getDamageSources().playerAttack(p), dmg);
        t.takeKnockback(kb, p.getX() - t.getX(), p.getZ() - t.getZ());
        t.addVelocity(0, 0.25 + 0.05 * kb, 0);
        t.velocityModified = true;
    }

    private static void blast(ServerWorld w, ServerPlayerEntity p, Vec3d c, double radius, float dmg, float hardness, SusanooColor col) {
        Box b = new Box(c, c).expand(radius);
        for (LivingEntity t : w.getEntitiesByClass(LivingEntity.class, b, x -> canTarget(p, x))) {
            double d = t.getPos().distanceTo(c);
            if (d <= radius + t.getWidth()) {
                float k = (float) (1.0 - Math.min(1.0, d / (radius + 0.01)) * 0.6);
                t.damage(w.getDamageSources().playerAttack(p), dmg * k);
                Vec3d push = t.getPos().subtract(c).normalize().multiply(0.9 + radius * 0.15);
                t.addVelocity(push.x, 0.5, push.z);
                t.velocityModified = true;
            }
        }
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, radius > 4 ? 3 : 1, radius * 0.3, radius * 0.3, radius * 0.3, 0);
        dust(w, col, c, 40 + (int) (radius * 12), radius * 0.6);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 3f, 0.7f);
        if (!w.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) return;
        BlockPos center = BlockPos.ofFloored(c);
        int r = (int) Math.ceil(radius);
        int count = 0;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    if (x * x + y * y + z * z > radius * radius) continue;
                    if (destroy(w, p, center.add(x, y, z), hardness) && ++count > 2500) return;
                }
            }
        }
    }

    private static void breakSector(ServerWorld w, ServerPlayerEntity p, Vec3d look, double reach, double arc, float hardness) {
        breakSector(w, p, p.getBlockPos(), look, reach, arc, hardness, (int) (reach * 0.7));
    }

    private static void breakSector(ServerWorld w, ServerPlayerEntity p, BlockPos base, Vec3d look, double reach, double arc,
                                    float hardness, int up) {
        if (hardness <= 0f || !w.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) return;
        int r = (int) Math.ceil(reach);
        int count = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > reach) continue;
                if (d > 1.0 && (dx * look.x + dz * look.z) / d < arc) continue;
                for (int dy = 0; dy <= up; dy++) {
                    if (destroy(w, p, base.add(dx, dy, dz), hardness) && ++count > 1500) return;
                }
            }
        }
    }

    private static boolean destroy(ServerWorld w, ServerPlayerEntity p, BlockPos pos, float hardnessLimit) {
        BlockState s = w.getBlockState(pos);
        if (s.isAir()) return false;
        float h = s.getHardness(w, pos);
        if (h < 0f || h > hardnessLimit) return false;
        if (w.getBlockEntity(pos) != null) return false;
        if (!w.canPlayerModifyAt(p, pos)) return false;
        return w.breakBlock(pos, false, p);
    }

    // ------------------------------------------------------------ utilitaires

    private static void dust(ServerWorld w, SusanooColor c, Vec3d at, int count, double spread) {
        DustParticleEffect fx = new DustParticleEffect(new Vector3f(c.glow[0] / 255f, c.glow[1] / 255f, c.glow[2] / 255f), 2.0f);
        w.spawnParticles(fx, at.x, at.y, at.z, count, spread, spread, spread, 0.02);
    }

    private static void playAt(ServerPlayerEntity p, SoundEvent s, float vol, float pitch) {
        p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), s, SoundCategory.PLAYERS, vol, pitch);
    }
}
