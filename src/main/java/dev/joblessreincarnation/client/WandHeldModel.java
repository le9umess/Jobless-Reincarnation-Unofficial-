package dev.joblessreincarnation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.joblessreincarnation.compat.epic.EpicCastAnimations;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import org.jetbrains.annotations.Nullable;
import yesman.epicfight.api.client.animation.Layer;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;

/** Rest the wrist diagonally down and outward so a short wand has a silhouette in F5. The spell clips own their aim. */
public final class WandHeldModel {
    private static final ModelResourceLocation IDLE_VIEW =
            ModelResourceLocation.standalone(ModContent.TRAINING_STAFF.getId().withPrefix("item/").withSuffix("_relaxed"));

    public static void register(ModelEvent.RegisterAdditional event) {
        event.register(IDLE_VIEW);
    }

    public static void bake(ModelEvent.ModifyBakingResult event) {
        var id = ModelResourceLocation.inventory(ModContent.TRAINING_STAFF.getId());
        BakedModel wand = event.getModels().get(id);
        BakedModel relaxed = event.getModels().get(IDLE_VIEW);
        if (wand == null || relaxed == null) return;
        var idle = new BakedModelWrapper<>(wand) {
            @Override
            public BakedModel applyTransform(ItemDisplayContext context, PoseStack poses, boolean left) {
                if (context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND) {
                    return relaxed.applyTransform(context, poses, left);
                }
                return wand.applyTransform(context, poses, left);
            }
        };
        event.getModels().put(id, new BakedModelWrapper<>(wand) {
            private final ItemOverrides overrides = new ItemOverrides() {
                @Override
                public BakedModel resolve(BakedModel original, ItemStack stack, @Nullable ClientLevel level,
                        @Nullable LivingEntity entity, int seed) {
                    BakedModel resolved = wand.getOverrides().resolve(wand, stack, level, entity, seed);
                    return resolved == wand && entity instanceof Player player && heldAtRest(player, stack) ? idle : resolved;
                }
            };

            @Override public ItemOverrides getOverrides() { return overrides; }
        });
    }

    private static boolean heldAtRest(Player player, ItemStack stack) {
        if (player.getMainHandItem() != stack && player.getOffhandItem() != stack) return false;
        if ((player.isUsingItem() && player.getUseItem() == stack) || player.getData(ModAttachments.EPIC_WATER_PHASE) != 0) return false;
        var patch = EpicFightCapabilities.getPlayerPatch(player);
        if (patch == null) return true;
        var layer = patch.getClientAnimator().getCompositeLayer(Layer.Priority.HIGH);
        return layer.isOff() || !EpicCastAnimations.isRelease(layer.animationPlayer.getRealAnimation());
    }

    private WandHeldModel() {}
}
