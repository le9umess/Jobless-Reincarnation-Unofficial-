package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Server-authoritative use lifecycle, shared by real item input and development fixtures. */
public final class SpellCasting {
    public static boolean canBegin(ServerPlayer player) {
        if (!dev.joblessreincarnation.encounter.PaulDuel.canCast(player) || EarthWallDismantling.active(player)) return false;
        if (!player.isAlive() || player.isRemoved() || player.isSpectator() || player.containerMenu != player.inventoryMenu
                || !player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())) return false;
        SpellCatalog spell = SpellCatalog.normalizeSelection(player);
        if (!spell.knows(player)) {
            player.displayClientMessage(Component.translatable("message.jobless_reincarnation.learn_selected", spell.title()), true);
            return false;
        }
        int cost = spell.manaCost(0);
        ManaData mana = ManaGrowth.prepare(player);
        if ((!mana.canCast(cost, player.getAbilities().instabuild) && !ManaDepletion.canOvercast(player, spell))
                || player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())) {
            if (mana.cooldown() == 0 && !player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())) {
                player.displayClientMessage(Component.translatable("message.jobless_reincarnation.not_enough_mana", cost), true);
            }
            return false;
        }
        return true;
    }

    /** Called by the actual NeoForge item-use Start event before vanilla assigns its use stack. */
    public static boolean begin(ServerPlayer player, ItemStack stack) {
        if (stack != player.getMainHandItem() || !canBegin(player)) return false;
        player.getData(ModAttachments.CASTING).begin(SpellCatalog.selected(player), player, stack);
        player.syncData(ModAttachments.CASTING);
        return true;
    }
    public static void predictBegin(Player player, ItemStack stack) {
        if (player.level().isClientSide && player.isUsingItem() && player.getUseItem() == stack) {
            player.getData(ModAttachments.CASTING).begin(SpellCatalog.selected(player), player, stack);
        }
    }
    public static boolean validActive(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator() && player.isUsingItem()
                && player.containerMenu == player.inventoryMenu
                && player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && player.getUseItem().is(ModContent.TRAINING_STAFF.get())
                && player.getData(ModAttachments.CASTING).matches(player, player.getUseItem());
    }
    public static void clearActive(Player player) {
        if (player.hasData(ModAttachments.CASTING) && player.getData(ModAttachments.CASTING).clearActive()
                && player instanceof ServerPlayer serverPlayer) serverPlayer.syncData(ModAttachments.CASTING);
    }
    /** Cancellation deliberately never calls releaseUsingItem(), which would resolve the spell. */
    public static void cancel(Player player) {
        if (player instanceof ServerPlayer serverPlayer) EarthWallDismantling.cancel(serverPlayer);
        clearActive(player);
        if (player.isUsingItem() && player.getUseItem().is(ModContent.TRAINING_STAFF.get())) player.stopUsingItem();
    }
    public static boolean release(ServerPlayer player, ItemStack stack, int usedTicks) {
        CastingData data = player.getData(ModAttachments.CASTING);
        SpellCatalog active = SpellCatalog.active(player);
        boolean overcasting = data.overcasting();
        int formStage = data.activeFormStage();
        boolean valid = dev.joblessreincarnation.encounter.PaulDuel.canCast(player) && validActive(player) && data.matches(player, stack) && !player.isShiftKeyDown();
        // Consume the lock before executing anything that could re-enter the item callback.
        clearActive(player);
        if (!valid || active == null || !active.knows(player)
                || usedTicks < (overcasting ? ManaDepletion.confirmTicks(player, active) : active.chantTicks(player))) {
            dev.joblessreincarnation.compat.epic.EpicCastController.finish(player, false);
            return false;
        }
        // An ordinary chant cannot silently become an overdraft. If enough mana returned during a
        // confirmed overdraft chant, pay the normal base cost instead, without exhaustion.
        SpellPayment mode = overcasting && !player.getAbilities().instabuild
                && player.getData(ModAttachments.MANA).mana() < active.manaCost(0)
                ? SpellPayment.OVERCAST : SpellPayment.NORMAL;
        boolean cast = !(overcasting && player.getAbilities().instabuild)
                && active.cast(player, overcasting ? 0 : active.charge(player, usedTicks), mode, formStage);
        dev.joblessreincarnation.compat.epic.EpicCastController.finish(player, cast);
        return cast;
    }
    /** A journal may be read empty-handed. Opening cancels only this mod's magic use. */
    public static void openJournal(Player player) {
        cancel(player);
        if (player.isUsingItem() && isMagicUse(player.getUseItem())) player.stopUsingItem();
    }
    public static boolean isMagicUse(ItemStack stack) {
        return stack.is(ModContent.TRAINING_STAFF.get()) || isStudyTome(stack);
    }
    public static boolean isStudyTome(ItemStack stack) {
        return stack.is(ModContent.WATER_TOME.get()) || stack.is(ModContent.WATER_SHIELD_TOME.get())
                || stack.is(ModContent.EARTH_TOME.get()) || stack.is(ModContent.AIR_TOME.get())
                || stack.is(ModContent.HEALING_TOME.get()) || stack.is(ModContent.FIRE_TOME.get())
                || stack.is(ModContent.MIRE_TOME.get()) || stack.is(ModContent.STONE_CANNON_TOME.get());
    }
    private static boolean canSelect(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator()
                && player.containerMenu == player.inventoryMenu && player.getMainHandItem().is(ModContent.TRAINING_STAFF.get());
    }

    public static boolean select(ServerPlayer player, ResourceLocation id) {
        SpellCatalog target = SpellCatalog.byId(id);
        if (!canSelect(player) || target == null || !target.knows(player)) return false;
        if (SpellCatalog.normalizeSelection(player) == target) return true;
        cancel(player);
        player.getData(ModAttachments.CASTING).select(target.id());
        player.syncData(ModAttachments.CASTING);
        player.displayClientMessage(Component.translatable("message.jobless_reincarnation.selected_spell", target.title()), true);
        return true;
    }

    public static boolean cycle(ServerPlayer player) {
        if (!canSelect(player)) return false;
        SpellCatalog.normalizeSelection(player);
        SpellCatalog next = player.getData(ModAttachments.CASTING).previousOrNext(player);
        if (next != null) return select(player, next.id());
        player.displayClientMessage(Component.translatable("message.jobless_reincarnation.no_other_spell"), true);
        return false;
    }

    public static boolean recall(ServerPlayer player, int slot, ResourceLocation expected, int revision) {
        if (!canSelect(player)) return false;
        CastingData data = player.getData(ModAttachments.CASTING);
        SpellCatalog current = SpellCatalog.normalizeSelection(player);
        if (revision != data.revision()) return false;
        SpellCatalog target = slot == -1 ? data.previousOrNext(player) : data.spellAt(player, slot);
        return target != null && target != current && target.id().equals(expected) && select(player, target.id());
    }

    public static boolean moveSlot(ServerPlayer player, ResourceLocation id, int slot, int revision) {
        if (!player.isAlive() || player.isRemoved() || player.isSpectator() || player.containerMenu != player.inventoryMenu) return false;
        SpellCatalog spell = SpellCatalog.byId(id);
        if (spell == null || !spell.knows(player)) return false;
        CastingData data = player.getData(ModAttachments.CASTING);
        SpellCatalog.normalizeSelection(player);
        if (revision != data.revision() || !data.moveSlot(spell, slot)) return false;
        player.syncData(ModAttachments.CASTING);
        return true;
    }
    private SpellCasting() {}
}
