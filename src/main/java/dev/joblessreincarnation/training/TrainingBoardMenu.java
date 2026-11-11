package dev.joblessreincarnation.training;

import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Only five bounded values are sent to the screen; the server owns all progress and rewards. */
public final class TrainingBoardMenu extends AbstractContainerMenu {
    public static final int CLAIM_BUTTON = 0;
    private static final int DATA_COUNT = 5;
    private final Player owner;
    private final BlockPos boardPos;
    private final Level boardLevel;
    private final ContainerData data;

    public TrainingBoardMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(id, inventory, buffer.readBlockPos(), new SimpleContainerData(DATA_COUNT));
    }

    public TrainingBoardMenu(int id, Inventory inventory, BlockPos pos) {
        this(id, inventory, pos, new ContainerData() {
            @Override
            public int get(int index) {
                Player player = inventory.player;
                TrainingProgress progress = player.getData(ModAttachments.TRAINING);
                return switch (index) {
                    case 0 -> WaterMagic.isLearned(player) ? 1 : 0;
                    case 1 -> Math.min(TrainingTrials.CAST_GOAL, WaterMagic.practiceCount(player));
                    case 2 -> Math.min(TrainingTrials.HIT_GOAL, progress.targetHits());
                    case 3 -> progress.chargedTargetHit() ? 1 : 0;
                    case 4 -> progress.claimed() ? 1 : 0;
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                // Server values are read directly from the owner's persistent attachments.
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        });
    }

    private TrainingBoardMenu(int id, Inventory inventory, BlockPos pos, ContainerData data) {
        super(ModContent.TRAINING_BOARD_MENU.get(), id);
        owner = inventory.player;
        boardPos = pos.immutable();
        boardLevel = owner.level();
        this.data = data;
        checkContainerDataCount(data, DATA_COUNT);
        addDataSlots(data);
    }

    public boolean learned() { return data.get(0) != 0; }
    public int casts() { return data.get(1); }
    public int hits() { return data.get(2); }
    public boolean chargedHit() { return data.get(3) != 0; }
    public boolean claimed() { return data.get(4) != 0; }

    public boolean completed() {
        return learned() && casts() >= TrainingTrials.CAST_GOAL
                && hits() >= TrainingTrials.HIT_GOAL && chargedHit();
    }

    @Override
    public boolean stillValid(Player player) {
        return player == owner && player.isAlive() && !player.isSpectator()
                && player.level() == boardLevel
                && player.level().getBlockState(boardPos).is(ModContent.TRAINING_BOARD.get())
                && player.distanceToSqr(boardPos.getX() + 0.5, boardPos.getY() + 0.5, boardPos.getZ() + 0.5) <= 64.0;
    }

    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (button != CLAIM_BUTTON || !(player instanceof ServerPlayer serverPlayer)
                || player.containerMenu != this || !stillValid(player)) return false;
        boolean claimed = TrainingTrials.claim(serverPlayer);
        if (claimed) broadcastChanges();
        return claimed;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
