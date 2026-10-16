package com.susano;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** Un Mangekyo Sharingan : sa couleur determine celle du Susanoo. Clic droit = invoquer / renvoyer. */
public class SusanooItem extends Item {
    private final SusanooColor color;

    public SusanooItem(Settings settings, SusanooColor color) {
        super(settings);
        this.color = color;
    }

    public SusanooColor getColor() {
        return color;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity sp) {
            SusanooManager.toggleWith(sp, color);
            user.getItemCooldownManager().set(this, 20);
        }
        return TypedActionResult.success(stack, world.isClient);
    }
}
