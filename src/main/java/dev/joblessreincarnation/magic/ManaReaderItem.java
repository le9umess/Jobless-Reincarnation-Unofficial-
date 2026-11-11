package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.npc.SylphietteEntity;
import dev.joblessreincarnation.registry.ModAttachments;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** The reader takes one private server-side measurement. It never creates a mana attachment on its target. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class ManaReaderItem extends Item {
    private static final String MESSAGE = "message.jobless_reincarnation.mana_reader.";
    private static final int COOLDOWN_TICKS = 20;

    public ManaReaderItem(Properties properties) { super(properties); }

    public static boolean inMainHand(Player player) {
        return player.getMainHandItem().getItem() instanceof ManaReaderItem;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND || player.isSpectator() || !player.isAlive()) {
            return InteractionResultHolder.pass(stack);
        }
        if (player instanceof ServerPlayer serverPlayer) measure(serverPlayer, null);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void useAt(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!inMainHand(event.getEntity())) return;
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        event.setCanceled(true);
        if (event.getHand() == InteractionHand.MAIN_HAND && event.getEntity() instanceof ServerPlayer player) {
            measure(player, event.getTarget());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interact(PlayerInteractEvent.EntityInteract event) {
        if (!inMainHand(event.getEntity())) return;
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        event.setCanceled(true);
        if (event.getHand() == InteractionHand.MAIN_HAND && event.getEntity() instanceof ServerPlayer player) {
            measure(player, event.getTarget());
        }
    }

    private static void measure(ServerPlayer player, @Nullable Entity requested) {
        Item reader = player.getMainHandItem().getItem();
        if (!(reader instanceof ManaReaderItem) || !player.isAlive() || player.isRemoved() || player.isSpectator()
                || player.getCooldowns().isOnCooldown(reader)) return;
        Entity target = player;
        if (!player.isShiftKeyDown()) {
            Entity hit = firstTarget(player);
            if (requested != null && requested != hit) {
                target = null;
            } else if (hit != null) {
                target = hit;
            }
        }
        player.displayClientMessage(reading(player, target), true);
        player.getCooldowns().addCooldown(reader, COOLDOWN_TICKS);
    }

    @Nullable
    private static Entity firstTarget(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 ray = player.getLookAngle().scale(Math.min(4.0, player.entityInteractionRange()));
        Vec3 end = eye.add(ray);
        var level = player.serverLevel();
        var unloaded = BlockGetter.traverseBlocks(eye, end, level,
                (world, pos) -> world.hasChunkAt(pos) ? null : pos.immutable(), world -> null);
        if (unloaded != null) return null;
        var block = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        Vec3 limit = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        double distance = eye.distanceToSqr(limit);
        if (distance < 1.0E-8) return null;
        var hit = ProjectileUtil.getEntityHitResult(player, eye, limit, player.getBoundingBox().expandTowards(ray).inflate(1.0),
                entity -> entity.isPickable() && !entity.isSpectator(), distance);
        if (hit == null || block.getType() != HitResult.Type.MISS && eye.distanceToSqr(hit.getLocation()) >= distance) return null;
        return hit.getEntity();
    }

    private static Component reading(ServerPlayer user, @Nullable Entity target) {
        if (target == null || !target.isAlive() || target.isRemoved() || (target != user && target.isInvisibleTo(user))) {
            return Component.translatable(MESSAGE + "unreadable");
        }
        if (target instanceof ServerPlayer owner && owner.hasData(ModAttachments.MANA)) {
            ManaData mana = owner.getData(ModAttachments.MANA);
            return owner == user
                    ? Component.translatable(MESSAGE + "self", mana.mana(), mana.maximum())
                    : Component.translatable(MESSAGE + "player", owner.getDisplayName(), mana.mana(), mana.maximum());
        }
        int capacity = npcCapacity(target);
        return capacity < 0 ? Component.translatable(MESSAGE + "unreadable")
                : Component.translatable(MESSAGE + "npc", target.getDisplayName(), capacity);
    }

    /** First-pass game balance. NPCs have capacity readings only until they actually spend mana. */
    private static int npcCapacity(Entity target) {
        if (target instanceof RoxyTutorEntity) return 180;
        if (target instanceof SylphietteEntity) return 60;
        if (target instanceof FamilyResidentEntity resident) {
            return switch (resident.member()) {
                case PAUL -> 30;
                case ZENITH -> 120;
                case LILIA -> 40;
            };
        }
        return -1;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.jobless_reincarnation.mana_reader.use").withStyle(ChatFormatting.GRAY));
    }
}
