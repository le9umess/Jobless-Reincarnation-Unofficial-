package dev.joblessreincarnation.npc;

import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class NpcRegistration {
    private static final SimpleCommandExceptionType NOT_TUTOR = new SimpleCommandExceptionType(
            Component.translatable("message.jobless_reincarnation.tutor.anchor.not_tutor"));
    private static final SimpleCommandExceptionType INVALID_SITE = new SimpleCommandExceptionType(
            Component.translatable("message.jobless_reincarnation.tutor.anchor.invalid_site"));
    private static final SimpleCommandExceptionType NOT_FAMILY = new SimpleCommandExceptionType(
            Component.translatable("message.jobless_reincarnation.family.anchor.not_family"));
    private static final SimpleCommandExceptionType FAMILY_INVALID_SITE = new SimpleCommandExceptionType(
            Component.translatable("message.jobless_reincarnation.family.anchor.invalid_site"));
    private static final SimpleCommandExceptionType NOT_SYLPHIETTE = new SimpleCommandExceptionType(
            Component.translatable("message.jobless_reincarnation.sylphiette.anchor.not_sylphiette"));
    private static final SimpleCommandExceptionType SYLPHIETTE_INVALID_SITE = new SimpleCommandExceptionType(
            Component.translatable("message.jobless_reincarnation.sylphiette.anchor.invalid_site"));

    @EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Attributes {
        @SubscribeEvent
        public static void register(EntityAttributeCreationEvent event) {
            event.put(ModContent.ROXY_TUTOR.get(), RoxyTutorEntity.attributes().build());
            event.put(ModContent.PAUL_GREYRAT.get(), FamilyResidentEntity.attributes().build());
            event.put(ModContent.ZENITH_GREYRAT.get(), FamilyResidentEntity.attributes().build());
            event.put(ModContent.LILIA_GREYRAT.get(), FamilyResidentEntity.attributes().build());
            event.put(ModContent.SYLPHIETTE.get(), SylphietteEntity.attributes().build());
        }
        private Attributes() {}
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("jr_tutor").requires(source -> source.hasPermission(2))
                .then(Commands.literal("anchor").then(Commands.argument("target", EntityArgument.entity())
                        .executes(context -> {
                            Entity target = EntityArgument.getEntity(context, "target");
                            if (!(target instanceof RoxyTutorEntity tutor)) throw NOT_TUTOR.create();
                            if (!tutor.reanchor()) throw INVALID_SITE.create();
                            var anchor = tutor.getTutorAnchor();
                            if (anchor == null) throw INVALID_SITE.create();
                            context.getSource().sendSuccess(() -> Component.translatable(
                                    "message.jobless_reincarnation.tutor.anchor.set", tutor.getDisplayName(),
                                    anchor.pos().getX(), anchor.pos().getY(), anchor.pos().getZ()), true);
                            return 1;
                        }))));
        // Re-anchors one family member where it now stands, e.g. after an administrator moved it into an older home.
        event.getDispatcher().register(Commands.literal("jr_family").requires(source -> source.hasPermission(2))
                .then(Commands.literal("anchor").then(Commands.argument("target", EntityArgument.entity())
                        .executes(context -> {
                            Entity target = EntityArgument.getEntity(context, "target");
                            if (!(target instanceof FamilyResidentEntity resident)) throw NOT_FAMILY.create();
                            if (!resident.reanchor()) throw FAMILY_INVALID_SITE.create();
                            var anchor = resident.getHomeAnchor();
                            if (anchor == null) throw FAMILY_INVALID_SITE.create();
                            context.getSource().sendSuccess(() -> Component.translatable(
                                    "message.jobless_reincarnation.family.anchor.set", resident.getDisplayName(),
                                    anchor.pos().getX(), anchor.pos().getY(), anchor.pos().getZ()), true);
                            return 1;
                        }))));
        // Re-anchors one Sylphiette where she now stands, e.g. after an administrator moved her to another tree.
        event.getDispatcher().register(Commands.literal("jr_sylphiette").requires(source -> source.hasPermission(2))
                .then(Commands.literal("anchor").then(Commands.argument("target", EntityArgument.entity())
                        .executes(context -> {
                            Entity target = EntityArgument.getEntity(context, "target");
                            if (!(target instanceof SylphietteEntity sylphiette)) throw NOT_SYLPHIETTE.create();
                            if (!sylphiette.reanchor()) throw SYLPHIETTE_INVALID_SITE.create();
                            var anchor = sylphiette.getHomeAnchor();
                            if (anchor == null) throw SYLPHIETTE_INVALID_SITE.create();
                            context.getSource().sendSuccess(() -> Component.translatable(
                                    "message.jobless_reincarnation.sylphiette.anchor.set", sylphiette.getDisplayName(),
                                    anchor.pos().getX(), anchor.pos().getY(), anchor.pos().getZ()), true);
                            return 1;
                        }))));
    }

    private NpcRegistration() {}
}