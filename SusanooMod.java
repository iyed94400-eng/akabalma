package com.susano;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

import java.util.EnumMap;
import java.util.Map;

public class SusanooMod implements ModInitializer {
    public static final String MOD_ID = "susanoo";
    /** Paquet client -> serveur : un entier d'action. */
    public static final Identifier ACTION = new Identifier(MOD_ID, "action");
    public static final int ACT_TOGGLE = 0, ACT_FORM_UP = 1, ACT_FORM_DOWN = 2, ACT_ATTACK = 3, ACT_SPECIAL = 4,
            ACT_STOMP = 5, ACT_KICK = 6;

    public static EntityType<SusanooEntity> SUSANOO;
    public static final Map<SusanooColor, Item> ITEMS = new EnumMap<>(SusanooColor.class);

    @Override
    public void onInitialize() {
        SUSANOO = Registry.register(Registries.ENTITY_TYPE, new Identifier(MOD_ID, "susanoo"),
                EntityType.Builder.<SusanooEntity>create(SusanooEntity::new, SpawnGroup.MISC)
                        .setDimensions(0.6f, 1.8f)
                        .maxTrackingRange(12)
                        .trackingTickInterval(1)
                        .makeFireImmune()
                        .build("susanoo"));

        for (SusanooColor c : SusanooColor.values()) {
            Item item = Registry.register(Registries.ITEM, new Identifier(MOD_ID, c.itemName),
                    new SusanooItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), c));
            ITEMS.put(c, item);
        }
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> ITEMS.values().forEach(entries::add));

        ServerPlayNetworking.registerGlobalReceiver(ACTION, (server, player, handler, buf, sender) -> {
            int action = buf.readVarInt();
            server.execute(() -> handle(player, action));
        });
        ServerTickEvents.END_SERVER_TICK.register(SusanooManager::tick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayerEntity p) || SusanooManager.absorb(p, source, amount));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SusanooManager.dismiss(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> SusanooManager.clear(server));
    }

    private static void handle(ServerPlayerEntity p, int action) {
        switch (action) {
            case ACT_TOGGLE -> SusanooManager.toggleKey(p);
            case ACT_FORM_UP -> SusanooManager.changeForm(p, 1);
            case ACT_FORM_DOWN -> SusanooManager.changeForm(p, -1);
            case ACT_ATTACK -> SusanooManager.attack(p);
            case ACT_SPECIAL -> SusanooManager.special(p);
            case ACT_STOMP -> SusanooManager.legAttack(p, SusanooEntity.KIND_STOMP);
            case ACT_KICK -> SusanooManager.legAttack(p, SusanooEntity.KIND_KICK);
            default -> { }
        }
    }
}
