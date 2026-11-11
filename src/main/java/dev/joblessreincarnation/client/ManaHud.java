package dev.joblessreincarnation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.ManaDepletion;
import dev.joblessreincarnation.magic.HealingTarget;
import net.minecraft.world.entity.LivingEntity;
import java.util.Locale;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.magic.SpellTraining;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class ManaHud {
    private static final ResourceLocation PLATE = sprite("plate"), SOCKET = sprite("spell_socket");
    private static final int BRASS = 0xFFA3845A, BRASS_DIM = 0xFF7C6341, BRONZE = 0xFF2C2218, TROUGH = 0xFF0B111D;
    private static final int TITLE = 0xFFF2E7CD, BODY = 0xFFC9C0A9, NOTE = 0xFFBCB49F;

    private static ResourceLocation sprite(String name) {
        return ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "hud/" + name);
    }

    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || player.isDeadOrDying() || minecraft.screen instanceof MagicJournalScreen || minecraft.options.hideGui || player.isSpectator()
                || minecraft.getDebugOverlay().showDebugScreen() || !player.hasData(ModAttachments.MANA)) return;
        ManaData mana = player.getData(ModAttachments.MANA);
        boolean waterBook = player.getMainHandItem().is(ModContent.WATER_TOME.get());
        boolean shieldBook = player.getMainHandItem().is(ModContent.WATER_SHIELD_TOME.get());
        boolean earthBook = player.getMainHandItem().is(ModContent.EARTH_TOME.get());
        boolean airBook = player.getMainHandItem().is(ModContent.AIR_TOME.get());
        boolean healingBook = player.getMainHandItem().is(ModContent.HEALING_TOME.get());
        boolean fireBook = player.getMainHandItem().is(ModContent.FIRE_TOME.get());
        boolean mireBook = player.getMainHandItem().is(ModContent.MIRE_TOME.get());
        boolean stoneBook = player.getMainHandItem().is(ModContent.STONE_CANNON_TOME.get());
        boolean holdingTome = waterBook || shieldBook || earthBook || airBook || healingBook || fireBook || mireBook || stoneBook;
        boolean holdingStaff = player.getMainHandItem().is(ModContent.TRAINING_STAFF.get());
        boolean studying = player.isUsingItem() && (player.getUseItem().is(ModContent.WATER_TOME.get()) || player.getUseItem().is(ModContent.WATER_SHIELD_TOME.get()) || player.getUseItem().is(ModContent.EARTH_TOME.get()) || player.getUseItem().is(ModContent.AIR_TOME.get()) || player.getUseItem().is(ModContent.HEALING_TOME.get()) || player.getUseItem().is(ModContent.FIRE_TOME.get()) || player.getUseItem().is(ModContent.MIRE_TOME.get()) || player.getUseItem().is(ModContent.STONE_CANNON_TOME.get()));
        boolean chanting = player.isUsingItem() && player.getUseItem().is(ModContent.TRAINING_STAFF.get());
        SpellCatalog active = chanting ? SpellCatalog.active(player) : null;
        SpellCatalog spell = holdingTome ? (stoneBook ? SpellCatalog.STONE_CANNON : mireBook ? SpellCatalog.MIRE : fireBook ? SpellCatalog.FIRE_BALL : healingBook ? SpellCatalog.HEALING : airBook ? SpellCatalog.AIR_BURST : earthBook ? SpellCatalog.EARTH_WALL : shieldBook ? SpellCatalog.WATER_SHIELD : SpellCatalog.WATER_BOLT)
                : active == null ? SpellCatalog.selected(player) : active;
        boolean showSpell = holdingTome || holdingStaff || studying || chanting;
        Component heading = Component.empty(), detail = Component.empty(), hint = Component.empty(), placement = Component.empty();
        float progress = 0;
        int progressColor = switch (spell) {
            case WATER_BOLT, WATER_SHIELD -> 0xFF41C9EA;
            case FIRE_BALL -> 0xFFFFB44D;
            case EARTH_WALL -> 0xFFD6AD69;
            case AIR_BURST -> 0xFFB9ECE1;
            case HEALING -> 0xFFCCE9A0;
            case MIRE -> 0xFFBBA078;
            case STONE_CANNON -> 0xFFB5B3A9;
        };
        int detailColor = BODY;
        int pendingCost = 0;
        boolean overdrawing = false;
        if (showSpell) {
            int casts = spell.practiceCount(player);
            if (studying) {
                heading = Component.translatable("hud.jobless_reincarnation.studying_named", spell.title());
                detail = Component.translatable("hud.jobless_reincarnation.cancel_study");
                progress = Math.clamp(player.getTicksUsingItem() / (float)Math.max(1,player.getUseItem().getUseDuration(player)),0,1);
            } else if (chanting) {
                int used = player.getTicksUsingItem(), chant = spell.chantTicks(player);
                boolean ready = used >= chant;
                float charge = spell.charge(player, used);
                pendingCost = player.getAbilities().instabuild ? 0 : spell.manaCost(charge);
                heading = Component.translatable(!ready ? "hud.jobless_reincarnation.chanting_named"
                        : spell == SpellCatalog.EARTH_WALL ? "hud.jobless_reincarnation.wall_ready"
                        : spell == SpellCatalog.HEALING ? "hud.jobless_reincarnation.healing_ready"
                        : spell == SpellCatalog.AIR_BURST ? "hud.jobless_reincarnation.air_ready"
                        : spell == SpellCatalog.FIRE_BALL ? "hud.jobless_reincarnation.fire_ready"
                        : spell == SpellCatalog.STONE_CANNON ? "hud.jobless_reincarnation.stone_cannon_ready"
                        : spell == SpellCatalog.MIRE ? "hud.jobless_reincarnation.mire_ready"
                        : charge >= 1 ? "hud.jobless_reincarnation.fully_shaped_named" : "hud.jobless_reincarnation.shaping_named",
                        spell.title(), (int)(charge * 100));
                detail = Component.translatable(ready ? (spell == SpellCatalog.EARTH_WALL
                        ? "hud.jobless_reincarnation.wall_release" : spell == SpellCatalog.AIR_BURST
                        ? "hud.jobless_reincarnation.air_release" : spell == SpellCatalog.HEALING
                        ? "hud.jobless_reincarnation.healing_release" : spell == SpellCatalog.FIRE_BALL
                        ? "hud.jobless_reincarnation.fire_release" : spell == SpellCatalog.MIRE
                        ? "hud.jobless_reincarnation.mire_release" : spell == SpellCatalog.STONE_CANNON
                        ? "hud.jobless_reincarnation.stone_cannon_release" : "hud.jobless_reincarnation.release_cost")
                        : "hud.jobless_reincarnation.chant_cost_named", pendingCost);
                progress = ready ? (spell == SpellCatalog.WATER_BOLT ? charge : 1) : used / (float)Math.max(1,chant);
                hint = Component.translatable("hud.jobless_reincarnation.cancel_cast",minecraft.options.keyShift.getTranslatedKeyMessage());
                if (pendingCost > mana.mana()) {
                    detail = Component.translatable("hud.jobless_reincarnation.charge_unaffordable",pendingCost);
                    detailColor = 0xFFFFA18F; progressColor = 0xFFDE8878;
                }
                if (player.getData(ModAttachments.CASTING).overcasting()) {
                    // Holding on past the chant is the confirmation; letting go earlier costs nothing.
                    int confirm = ManaDepletion.confirmTicks(player, spell);
                    overdrawing = true;
                    pendingCost = spell.manaCost(0.0F);
                    detailColor = 0xFFE3E8E6; progressColor = 0xFFD8DEDC;
                    if (used >= confirm) {
                        heading = Component.translatable("hud.jobless_reincarnation.overcast_ready", spell.title());
                        detail = Component.translatable("hud.jobless_reincarnation.overcast_ready_detail");
                        progress = 1;
                    } else if (ready) {
                        heading = Component.translatable("hud.jobless_reincarnation.overcast_hold", spell.title());
                        detail = Component.translatable("hud.jobless_reincarnation.overcast_hold_detail");
                        progress = (used - chant) / (float)Math.max(1, confirm - chant);
                    } else {
                        detail = Component.translatable("hud.jobless_reincarnation.overcast_chant");
                    }
                }
                if (player.isShiftKeyDown()) {
                    detail = Component.translatable("hud.jobless_reincarnation.release_cancel");
                    detailColor = 0xFFF0CC79; pendingCost = 0;
                }
            } else if (spell.knows(player)) {
                heading = Component.translatable("hud.jobless_reincarnation.spell_stage_named",spell.title(),SpellTraining.stage(casts).title());
                detail = SpellTraining.progressText(casts);
                int next = SpellTraining.nextMilestone(casts);
                progress = next == 0 ? 1 : Math.clamp(casts/(float)next,0,1);
                if (holdingStaff) hint = Component.translatable("hud.jobless_reincarnation.staff_hint",minecraft.options.keyUse.getTranslatedKeyMessage());
            } else {
                heading = Component.translatable("hud.jobless_reincarnation.not_learned_named",spell.title());
                detail = holdingTome ? Component.translatable("hud.jobless_reincarnation.study_hint")
                        : Component.translatable("hud.jobless_reincarnation.need_tome_named",spell.title());
            }
            if (holdingStaff && DismissInputState.holding()) {
                heading = Component.translatable(DismissInputState.accepted()
                        ? (DismissInputState.ready() ? "hud.jobless_reincarnation.dismiss_ready" : "hud.jobless_reincarnation.dismiss_holding")
                        : "hud.jobless_reincarnation.dismiss_wait");
                pendingCost = DismissInputState.cost();
                detail = Component.translatable("hud.jobless_reincarnation.dismiss_release", SpellKeys.DISMISS.getTranslatedKeyMessage(), pendingCost);
                hint = Component.translatable("hud.jobless_reincarnation.dismiss_cancel", minecraft.options.keyShift.getTranslatedKeyMessage(), SpellKeys.CYCLE.getTranslatedKeyMessage());
                progress = DismissInputState.progress(); progressColor = 0xFFF5AA38;
            } else if (holdingStaff && DismissInputState.waitingForRelease()) {
                detail = Component.translatable("hud.jobless_reincarnation.dismiss_wait_release", SpellKeys.DISMISS.getTranslatedKeyMessage());
                detailColor = 0xFFF0CC79; pendingCost = 0;
            } else if (holdingStaff && StaffInput.waitingForRelease()) {
                detail = Component.translatable("hud.jobless_reincarnation.release_before_cast");
                detailColor = 0xFFF0CC79; pendingCost = 0;
            }
            if (holdingStaff && spell == SpellCatalog.HEALING && spell.knows(player)) {
                var target = HealingTarget.resolve(player);
                if (target.valid() && target.target() instanceof LivingEntity living) {
                    placement = Component.translatable("hud.jobless_reincarnation.healing_target", target.targetName(),
                            String.format(Locale.ROOT, "%.1f", living.getHealth()), String.format(Locale.ROOT, "%.1f", living.getMaxHealth()));
                } else {
                    placement = Component.translatable("hud.jobless_reincarnation.healing_target_invalid", target.targetName(), target.reason().message());
                    // Advisory only: the server re-resolves target and health at release.
                    if (chanting && !player.isShiftKeyDown()) {
                        pendingCost = 0;
                        detail = target.reason().message();
                        detailColor = 0xFFF0CC79;
                    }
                }
            }
            var plan = EarthWallPreview.currentPlan();
            if (plan != null) placement = plan.valid()
                    ? Component.translatable("hud.jobless_reincarnation.wall_preview_valid") : plan.reason();
        }
        if (holdingStaff && !chanting && placement.getString().isEmpty() && spell.knows(player)) {
            // Said where the player is looking when it matters; the crystal says the rest.
            if (mana.overcastLocked()) placement = Component.translatable("hud.jobless_reincarnation.overcast_locked");
            else if (ManaDepletion.canOvercast(player, spell)) placement = Component.translatable("hud.jobless_reincarnation.overcast_available");
        }
        Component selection = holdingStaff ? Component.translatable("hud.jobless_reincarnation.select_spell",SpellKeys.CYCLE.getTranslatedKeyMessage()) : Component.empty();
        Component dismissHint = holdingStaff && spell == SpellCatalog.EARTH_WALL && spell.knows(player) && !chanting && !DismissInputState.blocksUse()
                ? Component.translatable("hud.jobless_reincarnation.dismiss_hint", SpellKeys.DISMISS.getTranslatedKeyMessage()) : Component.empty();
        var font=minecraft.font;
        int x=10,y=10,pad=7,gemRoom=ManaGem.SIZE+3,socketRoom=23,textRoom=gemRoom+socketRoom;
        // The owner's mana stays a colour, never a number; exact readings belong to the measuring crystal.
        float fill=mana.mana()/(float)mana.maximum();
        float after=pendingCost<=0?-1:Math.max(0,mana.mana()-pendingCost)/(float)mana.maximum();
        boolean shortfall=pendingCost>mana.mana();
        float unlock=MagicConfig.OVERCAST_UNLOCK_RATIO.get().floatValue();
        boolean drained=mana.overcastLocked()||ManaDepletion.isDepleted(player);
        float pallor=drained?Math.clamp(1-fill/unlock,0,1):0;
        boolean flare=overdrawing&&OvercastCue.confirmed(player);
        RenderSystem.enableBlend();
        if(!showSpell) {
            // Only the resting plate wears the brass ring; the plate is drawn over its inner side. It stills once the crystal cracks,
            // and for the whole overdraw lock or depletion, even after the crystal's pallor has faded.
            boolean dull=fill<=ManaGem.CRACK_BELOW||drained;
            ManaRing.draw(graphics,x,y,!dull&&HudConfig.ANIMATE_CRYSTAL_RING.get(),dull);
            graphics.blitSprite(PLATE,x,y,26,26);
            ManaGem.draw(graphics,x+5,y+5,fill,after,shortfall,pallor,flare);
            RenderSystem.disableBlend();
            return;
        }
        int width=128;
        Component[] rows={hint,placement,selection,dismissHint};
        int extra=0;
        for (Component line:rows) if(!line.getString().isEmpty()) { extra++; width=Math.max(width,font.width(line)+pad*2); }
        width=Math.max(width,Math.max(font.width(heading),font.width(detail))+pad*2+textRoom);
        width=Math.min(width,Math.max(80,minecraft.getWindow().getGuiScaledWidth()-20));
        int inner=width-pad*2,top=y+6;
        graphics.blitSprite(PLATE,x,y,width,extra==0?42:41+11*extra);
        dev.joblessreincarnation.client.boss.BossBarHud.keepClear(x,y,x+width,y+(extra==0?42:41+11*extra));
        ManaGem.draw(graphics,x+pad,top+2,fill,after,shortfall,pallor,flare);
        graphics.blitSprite(SOCKET,x+pad+gemRoom,top,20,20);
        if(studying||chanting||(holdingStaff&&DismissInputState.holding())) ringLights(graphics,x+pad+gemRoom,top,progress,progressColor);
        spellIcon(graphics,spell,x+pad+gemRoom+4,top+5);
        text(graphics,heading,x+pad+textRoom,top+1,inner-textRoom,TITLE);
        text(graphics,detail,x+pad+textRoom,top+12,inner-textRoom,detailColor);
        bar(graphics,x+pad,top+23,inner,progress,progressColor);
        int row=top+33;
        for(Component line:rows) if(!line.getString().isEmpty()) {
            text(graphics,line,x+pad,row,inner,NOTE); row+=11;
        }
        RenderSystem.disableBlend();
    }
    /** Small original pixel glyphs: a water drop, a small shield, six-cell wall, three wind lines, and a four-point healing light. */
    static void spellIcon(GuiGraphics graphics,SpellCatalog spell,int x,int y) {
        if(spell==SpellCatalog.WATER_SHIELD) {
            graphics.fill(x+2,y,x+10,y+1,0xFF41C9EA);
            graphics.fill(x+1,y+1,x+11,y+5,0xFF41C9EA);
            graphics.fill(x+2,y+5,x+10,y+7,0xFF41C9EA);
            graphics.fill(x+3,y+7,x+9,y+8,0xFF41C9EA);
            graphics.fill(x+5,y+8,x+7,y+9,0xFF41C9EA);
            graphics.fill(x+3,y+2,x+9,y+5,0xFF0E5D78);
            graphics.fill(x+4,y+5,x+8,y+6,0xFF0E5D78);
            graphics.fill(x+5,y+2,x+7,y+6,0xFFE0F7FF);
        } else if(spell==SpellCatalog.FIRE_BALL) {
            graphics.fill(x+5,y,x+7,y+3,0xFFFFB44D);
            graphics.fill(x+3,y+2,x+9,y+7,0xFFEF752D);
            graphics.fill(x+2,y+5,x+10,y+9,0xFFEF752D);
            graphics.fill(x+4,y+5,x+8,y+9,0xFFFFD778);
        } else if (spell == SpellCatalog.STONE_CANNON) {
            graphics.fill(x+2,y+2,x+10,y+8,0xFF9D9D94);
            graphics.fill(x+3,y+1,x+9,y+9,0xFFB5B3A9);
            graphics.fill(x+4,y+2,x+7,y+4,0xFFE0DDD1);
            graphics.fill(x+7,y+6,x+9,y+8,0xFF78796F);
        } else if (spell == SpellCatalog.MIRE) {
            graphics.fill(x+1,y+5,x+11,y+8,0xFF81644B);
            graphics.fill(x+3,y+7,x+9,y+9,0xFFBBA078);
            graphics.fill(x+4,y+3,x+6,y+6,0xFF72B0AF);
            graphics.fill(x+7,y+1,x+9,y+4,0xFF72B0AF);
            graphics.fill(x+2,y+5,x+4,y+6,0xFFD4C1A1);
        } else if(spell==SpellCatalog.EARTH_WALL) {
            for(int row=0;row<2;row++) for(int col=0;col<3;col++)
                graphics.fill(x+col*4,y+1+row*4,x+3+col*4,y+4+row*4,0xFFD6AD69);
        } else if (spell == SpellCatalog.AIR_BURST) {
            graphics.fill(x+1,y+1,x+9,y+2,0xFFB9ECE1);
            graphics.fill(x+8,y,x+10,y+2,0xFFB9ECE1);
            graphics.fill(x,y+4,x+11,y+5,0xFFB9ECE1);
            graphics.fill(x+10,y+3,x+12,y+5,0xFFB9ECE1);
            graphics.fill(x+2,y+7,x+8,y+8,0xFFB9ECE1);
            graphics.fill(x+7,y+7,x+9,y+9,0xFFB9ECE1);
        } else if (spell == SpellCatalog.HEALING) {
            graphics.fill(x+5,y,x+7,y+9,0xFFCCE9A0);
            graphics.fill(x+1,y+3,x+11,y+6,0xFFCCE9A0);
            graphics.fill(x+4,y+2,x+8,y+7,0xFFF0F5C8);
            graphics.fill(x+5,y+3,x+7,y+6,0xFFFFFFFF);
        } else {
            graphics.fill(x+5,y,x+7,y+3,0xFF41C9EA);
            graphics.fill(x+3,y+3,x+9,y+8,0xFF41C9EA);
            graphics.fill(x+4,y+8,x+8,y+9,0xFF41C9EA);
            graphics.fill(x+4,y+4,x+5,y+6,0xFFE0F7FF);
        }
    }
    private static void text(GuiGraphics g,Component line,int x,int y,int width,int color) {
        var font=Minecraft.getInstance().font;
        String value=line.getString();
        if(font.width(value)>width) value=font.plainSubstrByWidth(value,Math.max(0,width-font.width("…")))+"…";
        g.drawString(font,value,x,y,color,false);
    }
    /** A slim bronze-framed bar with quarter notches, in the spirit of the vanilla experience bar. */
    static void bar(GuiGraphics g,int x,int y,int width,float progress,int color) {
        g.fill(x,y,x+width,y+7,BRONZE);
        g.fill(x+1,y+1,x+width-1,y+6,TROUGH);
        int span=width-2;
        tones(g,x+1,x+1+(int)(span*Math.clamp(progress,0,1)),y+1,color);
        for(int notch=1;notch<4;notch++) {
            int at=x+1+span*notch/4;
            g.fill(at,y+1,at+1,y+6,0x66000000);
        }
        g.fill(x,y+1,x+1,y+6,BRASS);
        g.fill(x+width-1,y+1,x+width,y+6,BRASS_DIM);
    }
    /** Five rows tall: a lit top row, the colour itself, and a shaded bottom row. */
    private static void tones(GuiGraphics g,int from,int to,int y,int color) {
        if(to<=from) return;
        g.fill(from,y,to,y+1,shade(color,1.3F));
        g.fill(from,y+1,to,y+4,color);
        g.fill(from,y+4,to,y+5,shade(color,0.68F));
    }
    private static int shade(int color,float scale) {
        int r=Math.min(255,(int)((color>>16&255)*scale)),g=Math.min(255,(int)((color>>8&255)*scale)),b=Math.min(255,(int)((color&255)*scale));
        return color&0xFF000000|r<<16|g<<8|b;
    }
    /** Lights the socket's ring clockwise while a chant, study or dismissal is in progress. */
    private static void ringLights(GuiGraphics g,int x,int y,float progress,int color) {
        int lit=Math.round(16*Math.clamp(progress,0,1));
        for(int i=0;i<lit;i++) {
            double angle=Math.PI*2*(i+0.5)/16-Math.PI/2;
            int px=x+10+(int)Math.floor(8.6*Math.cos(angle)),py=y+10+(int)Math.floor(8.6*Math.sin(angle));
            g.fill(px,py,px+1,py+1,color);
        }
    }
    private ManaHud() {}
}

