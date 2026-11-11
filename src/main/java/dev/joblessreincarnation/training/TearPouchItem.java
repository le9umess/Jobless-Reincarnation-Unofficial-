package dev.joblessreincarnation.training;

import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class TearPouchItem extends Item {
    public TearPouchItem() { super(new Properties().stacksTo(16)); }

    public static ItemStack findBag(Player player) {
        if (player.getOffhandItem().is(ModContent.TEAR_POUCH.get())) return player.getOffhandItem();
        for (ItemStack stack : player.getInventory().items) if (stack.is(ModContent.TEAR_POUCH.get())) return stack;
        return ItemStack.EMPTY;
    }

    private static boolean ownsBag(Player player, ItemStack bag) {
        return player.getOffhandItem() == bag || player.getInventory().items.stream().anyMatch(stack -> stack == bag);
    }

    public static boolean available(Player player) {
        return !player.getCooldowns().isOnCooldown(ModContent.TEAR_POUCH.get()) && !findBag(player).isEmpty();
    }

    /** The book permits training against a dummy. Only a trained throw can obscure a living opponent. */
    public static boolean launch(ServerPlayer player, ItemStack bag, BooleanSupplier pay) {
        Item item = ModContent.TEAR_POUCH.get();
        if (player.isFakePlayer() || !player.isAlive() || player.isSpectator() || !bag.is(item)
                || !ownsBag(player, bag) || player.getCooldowns().isOnCooldown(item)) return false;
        boolean trained = player.hasData(ModAttachments.SWORD_TRAINING) && player.getData(ModAttachments.SWORD_TRAINING).mastered(SchoolForm.TEAR_POUCH);
        var thrown = new TearPouchProjectile(player, trained);
        thrown.shootFromRotation(player, player.getXRot(), player.getYRot(), 0, 1.05F, 0.0F);
        player.getCooldowns().addCooldown(item, 12);
        if (!player.serverLevel().addFreshEntity(thrown) || !bag.is(item) || !ownsBag(player, bag) || !pay.getAsBoolean()) {
            thrown.discard();
            player.getCooldowns().removeCooldown(item);
            return false;
        }
        if (!player.getAbilities().instabuild) bag.shrink(1);
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, .5F, 1.0F);
        player.awardStat(Stats.ITEM_USED.get(item));
        return true;
    }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // Throwing from a sword grip is the trained move and uses its native stamina check.
        if (hand == InteractionHand.OFF_HAND && SchoolForm.isSword(player.getMainHandItem())) return InteractionResultHolder.pass(stack);
        if (player instanceof ServerPlayer server && !launch(server, stack, () -> true)) return InteractionResultHolder.fail(stack);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
