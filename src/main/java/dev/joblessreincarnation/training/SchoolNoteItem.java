package dev.joblessreincarnation.training;

import dev.joblessreincarnation.registry.ModAttachments;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.Level;

/** A reusable, locally translated notebook. The reader opens a personal practice after Paul's recognition. */
public final class SchoolNoteItem extends Item {
    private final SchoolForm form;

    public SchoolNoteItem(SchoolForm form) {
        super(new Item.Properties().stacksTo(1).component(DataComponents.WRITTEN_BOOK_CONTENT, pages(form)));
        this.form = form;
    }

    private static WrittenBookContent pages(SchoolForm form) {
        String key = "book.jobless_reincarnation." + form.id;
        return new WrittenBookContent(Filterable.passThrough(form == SchoolForm.LIGHT_BLADE ? "Light blade field notes" : "Buena sword notes"),
                form == SchoolForm.LIGHT_BLADE ? "Buena sword student" : "Paul Greyrat", 0,
                List.of(Filterable.passThrough(Component.translatable(key + ".first")),
                        Filterable.passThrough(Component.translatable(key + ".second"))), true);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer reader && !reader.isFakePlayer() && !reader.isSpectator()) {
            if (form == SchoolForm.ARM_STRIKE && hand == InteractionHand.MAIN_HAND && !reader.isShiftKeyDown()
                    && reader.getOffhandItem().is(net.minecraft.world.item.Items.PAPER) && LightBladeTraining.copyStudyPage(reader)) {
                return InteractionResultHolder.consume(stack);
            }
            if (reader.isShiftKeyDown() && dev.joblessreincarnation.compat.epic.SchoolSkills.toggleFromNote(reader, form)) {
                return InteractionResultHolder.consume(stack);
            }
            if (!reader.hasData(ModAttachments.SWORD_TRAINING) || !reader.getData(ModAttachments.SWORD_TRAINING).recognized()) {
                reader.displayClientMessage(Component.translatable("message.jobless_reincarnation.school.find_paul"), true);
            } else if (form == SchoolForm.LIGHT_BLADE && !reader.getData(ModAttachments.SWORD_TRAINING).hasLightInsight()) {
                reader.displayClientMessage(Component.translatable("message.jobless_reincarnation.light.missing_insight"), true);
            } else if (reader.getData(ModAttachments.SWORD_TRAINING).openPractice(form)) {
                reader.displayClientMessage(Component.translatable("message.jobless_reincarnation.school.opened", stack.getHoverName()), true);
            }
            reader.connection.send(new ClientboundOpenBookPacket(hand));
            reader.awardStat(Stats.ITEM_USED.get(this));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
