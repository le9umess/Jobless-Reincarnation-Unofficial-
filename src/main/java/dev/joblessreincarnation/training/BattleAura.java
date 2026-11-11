package dev.joblessreincarnation.training;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.ManaDepletion;
import dev.joblessreincarnation.registry.ModAttachments;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;

@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class BattleAura {
    // Epic Fight can stop an ongoing skill before LivingDamageEvent.Pre. Remember the posture
    // for this damage transaction only. Cancelled hits never reach Pre, so do not retain them.
    private static final Set<DamageContainer> EXPOSED_HITS = Collections.newSetFromMap(new WeakHashMap<>());

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onIncoming(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && physical(event.getSource())
                && gathering(player, player.serverLevel().getGameTime())) {
            EXPOSED_HITS.add(event.getContainer());
        }
    }

    private static boolean physical(DamageSource source) {
        if (source.getDirectEntity() instanceof AbstractArrow) {
            return source.is(DamageTypes.ARROW) || source.is(DamageTypes.TRIDENT);
        }
        return source.getDirectEntity() instanceof LivingEntity && source.getDirectEntity() == source.getEntity()
                && (source.is(DamageTypes.PLAYER_ATTACK) || source.is(DamageTypes.MOB_ATTACK)
                || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO) || source.is(DamageTypes.STING));
    }

    private static boolean equipped(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && !player.getAbilities().instabuild
                && SchoolForm.isSword(player.getMainHandItem()) && player.hasData(ModAttachments.SWORD_TRAINING)
                && player.getData(ModAttachments.SWORD_TRAINING).auraAwakened() && !ManaDepletion.isDepleted(player);
    }

    /** Read the actual server-side posture; an awakened sword fighter concentrating on a technique has no body guard. */
    public static boolean hasBodyGuard(ServerPlayer player) {
        return equipped(player) && !gathering(player, player.level().getGameTime());
    }

    /** Identifies a particular accepted hold so a late cancel cannot end a newer technique. */
    public static final class Gathering {
        public static final Gathering NONE = new Gathering(null, ItemStack.EMPTY, -1, 0);
        private final ServerLevel level;
        private final ItemStack weapon;
        private final int slot;
        private final long deadline;

        private Gathering(@Nullable ServerLevel level, ItemStack weapon, int slot, long deadline) {
            this.level = level;
            this.weapon = weapon;
            this.slot = slot;
            this.deadline = deadline;
        }

        private boolean valid(ServerPlayer player, long now) {
            return player.serverLevel() == level && now < deadline && player.getInventory().selected == slot
                    && player.getMainHandItem() == weapon && equipped(player);
        }
    }

    /** Call only after the server accepts the learned, equipped technique. This is not a client request. */
    public static @Nullable Gathering beginGathering(ServerPlayer player, int maximumTicks) {
        long now = player.serverLevel().getGameTime();
        if (!player.serverLevel().getServer().isSameThread() || maximumTicks < 1 || maximumTicks > 200
                || !equipped(player) || gathering(player, now)) return null;
        var hold = new Gathering(player.serverLevel(), player.getMainHandItem(), player.getInventory().selected, now + maximumTicks);
        player.setData(ModAttachments.AURA_GATHERING, hold);
        player.setData(ModAttachments.BATTLE_AURA_VISUAL, new AuraVisual(AuraVisual.Phase.GATHER, now));
        return hold;
    }

    public static boolean holdsGathering(ServerPlayer player, @Nullable Gathering expected) {
        return expected != null && player.hasData(ModAttachments.AURA_GATHERING)
                && player.getData(ModAttachments.AURA_GATHERING) == expected && expected.valid(player, player.serverLevel().getGameTime());
    }

    /** Both normal release and interruption close the same hold; attack resolution and skill costs belong to the caller. */
    public static boolean endGathering(ServerPlayer player, @Nullable Gathering expected) {
        if (!player.serverLevel().getServer().isSameThread() || expected == null || !player.hasData(ModAttachments.AURA_GATHERING)
                || player.getData(ModAttachments.AURA_GATHERING) != expected) return false;
        boolean stillValid = expected.valid(player, player.serverLevel().getGameTime());
        player.removeData(ModAttachments.AURA_GATHERING);
        refreshVisual(player);
        return stillValid;
    }

    private static boolean gathering(ServerPlayer player, long now) {
        if (!player.hasData(ModAttachments.AURA_GATHERING)) return false;
        if (player.getData(ModAttachments.AURA_GATHERING).valid(player, now)) return true;
        player.removeData(ModAttachments.AURA_GATHERING);
        return false;
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clearHold(player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void death(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clearHold(player);
    }

    @SubscribeEvent
    public static void changeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clearHold(player);
    }

    private static void clearHold(ServerPlayer player) {
        if (player.hasData(ModAttachments.AURA_GATHERING)) {
            player.removeData(ModAttachments.AURA_GATHERING);
            player.setData(ModAttachments.BATTLE_AURA_VISUAL, AuraVisual.OFF);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void protect(LivingDamageEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !physical(event.getSource()) || !equipped(player)) return;
        long now = player.serverLevel().getGameTime();
        if (EXPOSED_HITS.remove(event.getContainer()) || gathering(player, now)) return;

        // 1.21.1 fires Pre after shields, armor and resistance, immediately before yellow-heart absorption.
        // Leave yellow hearts to vanilla. Hardness only applies to the hit that would reach red hearts.
        float damage = event.getNewDamage();
        float exposed = damage - player.getAbsorptionAmount();
        if (!Float.isFinite(exposed) || exposed <= 0) return;
        float prevented = (float) Math.min(MagicConfig.AURA_HARDNESS.get(),
                exposed * MagicConfig.AURA_MAX_REDUCTION_RATIO.get());
        float remaining = damage - prevented;
        if (remaining >= damage) return;
        event.setNewDamage(remaining);
        player.setData(ModAttachments.BATTLE_AURA_VISUAL, new AuraVisual(AuraVisual.Phase.GUARD, now));
    }

    @SubscribeEvent
    public static void practiceHit(LivingDamageEvent.Post event) {
        DamageSource source = event.getSource();
        if (!(event.getEntity() instanceof Enemy) || !(event.getNewDamage() > 0)
                || !(source.getDirectEntity() instanceof ServerPlayer player) || source.getEntity() != player
                || !source.is(DamageTypes.PLAYER_ATTACK) || !SchoolForm.isSword(player.getMainHandItem())
                || !SwordPractice.validActor(player, player.serverLevel()) || player.getAbilities().instabuild
                || !player.hasData(ModAttachments.SWORD_TRAINING)) return;
        if (player.getData(ModAttachments.SWORD_TRAINING).awakenAura()) {
            player.displayClientMessage(Component.translatable("message.jobless_reincarnation.sword_practice.aura_awakened"), true);
        }
    }

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) refreshVisual(player);
    }

    private static void refreshVisual(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        if (gathering(player, now)) return;
        AuraVisual visual = AuraVisual.of(player);
        AuraVisual next = AuraVisual.OFF;
        if (equipped(player)) {
            if (visual.phase() == AuraVisual.Phase.GUARD && visual.flash(now, 0) > 0) return;
            next = AuraVisual.READY;
        }
        if (!visual.equals(next)) player.setData(ModAttachments.BATTLE_AURA_VISUAL, next);
    }

    private BattleAura() {}
}
