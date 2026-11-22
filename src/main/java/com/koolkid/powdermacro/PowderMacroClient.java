package com.koolkid.powdermacro;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

public class PowderMacroClient implements ClientModInitializer {

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("powdermacro", "main"));

    private static final Set<Block> HARDSTONE = Set.of(
            Blocks.STONE, Blocks.COBBLESTONE, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE
    );

    private static final int RADIUS = 4;
    private static final double REACH = 4.5;
    private static final float MAX_TURN = 12.0f;

    private final Set<BlockPos> openedChests = new HashSet<>();
    private KeyMapping toggleKey;
    private boolean enabled = false;
    private BlockPos target = null;
    private int chestCooldown = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.powdermacro.toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_G, CATEGORY)
        );
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            if (!enabled) {
                stop(mc);
            }
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("Powder Macro: " + (enabled ? "ON" : "OFF")), true);
            }
        }

        if (!enabled) {
            return;
        }
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.screen != null) {
            mc.options.keyAttack.setDown(false);
            return;
        }

        LocalPlayer player = mc.player;
        if (chestCooldown > 0) {
            chestCooldown--;
        }

        if (target == null || !isValidTarget(mc, target)) {
            target = findTarget(mc);
        }

        if (target == null) {
            mc.options.keyAttack.setDown(false);
            return;
        }

        Vec3 center = Vec3.atCenterOf(target);
        turnToward(player, center);

        boolean isChest = mc.level.getBlockState(target).is(Blocks.CHEST);
        HitResult hit = mc.hitResult;
        boolean aimed = hit instanceof BlockHitResult bhr && bhr.getBlockPos().equals(target);

        if (isChest) {
            mc.options.keyAttack.setDown(false);
            if (aimed && chestCooldown == 0) {
                mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, (BlockHitResult) hit);
                player.swing(InteractionHand.MAIN_HAND);
                openedChests.add(target);
                chestCooldown = 20;
                target = null;
            }
        } else {
            mc.options.keyAttack.setDown(aimed);
        }
    }

    private void stop(Minecraft mc) {
        mc.options.keyAttack.setDown(false);
        target = null;
        openedChests.clear();
    }

    private boolean isValidTarget(Minecraft mc, BlockPos pos) {
        Block block = mc.level.getBlockState(pos).getBlock();
        boolean chest = block == Blocks.CHEST && !openedChests.contains(pos);
        boolean stone = HARDSTONE.contains(block);
        if (!chest && !stone) {
            return false;
        }
        return Vec3.atCenterOf(pos).distanceTo(mc.player.getEyePosition()) <= REACH;
    }

    private BlockPos findTarget(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        BlockPos origin = mc.player.blockPosition();
        BlockPos bestChest = null;
        double bestChestDist = Double.MAX_VALUE;
        BlockPos bestStone = null;
        double bestStoneDist = Double.MAX_VALUE;

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    Block block = mc.level.getBlockState(pos).getBlock();
                    boolean chest = block == Blocks.CHEST && !openedChests.contains(pos);
                    boolean stone = HARDSTONE.contains(block);
                    if (!chest && !stone) {
                        continue;
                    }
                    Vec3 center = Vec3.atCenterOf(pos);
                    double dist = center.distanceTo(eye);
                    if (dist > REACH || !canSee(mc, eye, center, pos)) {
                        continue;
                    }
                    if (chest && dist < bestChestDist) {
                        bestChestDist = dist;
                        bestChest = pos;
                    } else if (stone && dist < bestStoneDist) {
                        bestStoneDist = dist;
                        bestStone = pos;
                    }
                }
            }
        }
        return bestChest != null ? bestChest : bestStone;
    }

    private boolean canSee(Minecraft mc, Vec3 eye, Vec3 center, BlockPos pos) {
        BlockHitResult result = mc.level.clip(
                new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player)
        );
        return result.getType() == HitResult.Type.BLOCK && result.getBlockPos().equals(pos);
    }

    private void turnToward(LocalPlayer player, Vec3 point) {
        Vec3 eye = player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);

        float wantYaw = (float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float wantPitch = (float) -(Mth.atan2(dy, flat) * 180.0 / Math.PI);

        float yawDiff = Mth.wrapDegrees(wantYaw - player.getYRot());
        float pitchDiff = wantPitch - player.getXRot();

        player.setYRot(player.getYRot() + Mth.clamp(yawDiff, -MAX_TURN, MAX_TURN));
        player.setXRot(Mth.clamp(player.getXRot() + Mth.clamp(pitchDiff, -MAX_TURN, MAX_TURN), -90.0f, 90.0f));
    }
}
