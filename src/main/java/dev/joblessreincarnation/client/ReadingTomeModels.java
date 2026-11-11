package dev.joblessreincarnation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Transformation;
import dev.joblessreincarnation.magic.SpellCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.QuadTransformers;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/** Rotate the two leaves around one spine. All quads are baked at resource reload, never per player/frame. */
public final class ReadingTomeModels {
    private static final int LAST_FRAME = 16;
    private static final float SPINE_Z = 7.4F / 16.0F;

    private static ModelResourceLocation spread(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return ModelResourceLocation.standalone(id.withPrefix("item/reading/"));
    }

    public static void register(ModelEvent.RegisterAdditional event) {
        for (var spell : SpellCatalog.values()) event.register(spread(spell.tomeItem()));
    }

    public static void bake(ModelEvent.ModifyBakingResult event) {
        var models = event.getModels();
        for (var spell : SpellCatalog.values()) {
            Item item = spell.tomeItem();
            var id = ModelResourceLocation.inventory(BuiltInRegistries.ITEM.getKey(item));
            var closed = models.get(id);
            var pages = models.get(spread(item));
            if (closed == null || pages == null) continue;
            BakedModel[] frames = new BakedModel[LAST_FRAME + 1];
            for (int i = 0; i <= LAST_FRAME; i++) {
                frames[i] = heldOnly(closed, fold(pages, 82F - 69F * i / LAST_FRAME));
            }
            models.put(id, new BakedModelWrapper<>(closed) {
                private final ItemOverrides overrides = new ItemOverrides() {
                    @Override
                    public BakedModel resolve(BakedModel original, ItemStack stack, @Nullable ClientLevel level,
                            @Nullable LivingEntity reader, int seed) {
                        if (!TomeReading.isReading(reader, stack)) {
                            return closed.getOverrides().resolve(closed, stack, level, reader, seed);
                        }
                        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
                        return frames[Math.round(LAST_FRAME * TomeReading.opening(reader, partial))];
                    }
                };

                @Override public ItemOverrides getOverrides() { return overrides; }
            });
        }
    }

    private static BakedModel heldOnly(BakedModel closed, BakedModel pages) {
        return new BakedModelWrapper<>(closed) {
            @Override
            public BakedModel applyTransform(ItemDisplayContext context, PoseStack poses, boolean left) {
                BakedModel model = switch (context) {
                    case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND, THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> pages;
                    default -> closed;
                };
                return model.applyTransform(context, poses, left);
            }
        };
    }

    private static BakedModel fold(BakedModel pages, float degrees) {
        var left = QuadTransformers.applying(hinge(degrees));
        var right = QuadTransformers.applying(hinge(-degrees));
        var builder = new SimpleBakedModel.Builder(pages.useAmbientOcclusion(), pages.usesBlockLight(), pages.isGui3d(),
                pages.getTransforms(), ItemOverrides.EMPTY).particle(pages.getParticleIcon());
        // The authored spread uses unculled item faces; every face belongs to exactly one cover.
        for (BakedQuad quad : pages.getQuads(null, null, RandomSource.create(0))) {
            int[] vertices = quad.getVertices();
            int stride = vertices.length / 4;
            float sum = 0;
            for (int v = 0; v < 4; v++) sum += Float.intBitsToFloat(vertices[v * stride]);
            // A spine-end face lies exactly on x=0.5; its outward normal identifies its half.
            boolean onLeft = sum < 2.0F || (sum == 2.0F && quad.getDirection().getStepX() > 0);
            builder.addUnculledFace((onLeft ? left : right).process(quad));
        }
        return builder.build();
    }

    private static Transformation hinge(float degrees) {
        return new Transformation(new Matrix4f().translation(.5F, 0, SPINE_Z)
                .rotateY((float) Math.toRadians(degrees)).translate(-.5F, 0, -SPINE_Z));
    }

    private ReadingTomeModels() {}
}
