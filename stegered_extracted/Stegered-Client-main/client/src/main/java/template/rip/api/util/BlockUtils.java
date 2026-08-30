package template.rip.api.util;

import net.minecraft.block.*;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import template.rip.Template;
import template.rip.module.modules.blatant.ScaffoldModule;
import template.rip.module.modules.blatant.ScaffoldRecodeModule;

import java.util.function.Predicate;

import static template.rip.Template.mc;

public class BlockUtils {

    private static final double PLACE_RAY_EPSILON = 1.0E-4;
    private static final double PLACE_FACE_CORNER_OFFSET = 0.49;
    private static final double[][] PLACE_FACE_SAMPLE_OFFSETS = {
            {0.0, 0.0},
            {PLACE_FACE_CORNER_OFFSET, PLACE_FACE_CORNER_OFFSET},
            {PLACE_FACE_CORNER_OFFSET, -PLACE_FACE_CORNER_OFFSET},
            {-PLACE_FACE_CORNER_OFFSET, PLACE_FACE_CORNER_OFFSET},
            {-PLACE_FACE_CORNER_OFFSET, -PLACE_FACE_CORNER_OFFSET}
    };

    public static BlockState getBlockState(BlockPos pos) {
        return mc.world.getBlockState(pos);
    }

    public static Block getBlock(BlockPos pos) {
        return getBlockState(pos).getBlock();
    }

    public static boolean isBlock(Predicate<Block> block, BlockPos pos) {
        return block.test(getBlockState(pos).getBlock());
    }

    public static boolean isBlock(Block block, BlockPos pos) {
        return getBlockState(pos).getBlock() == block;
    }

    public static boolean crystalBlock(BlockPos bPos) {
        BlockState bs = mc.world.getBlockState(bPos);
        return bs.getBlock() == Blocks.OBSIDIAN || bs.getBlock() == Blocks.BEDROCK;
    }

    public static boolean isAnchorCharged(BlockPos anchor) {
        try {
            if (!isBlock(Blocks.RESPAWN_ANCHOR, anchor))
                return false;

            return getBlockState(anchor).get(RespawnAnchorBlock.CHARGES) != 0;
        }
        catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static Predicate<Item> placeableBlocks() {
        ScaffoldModule sm = Template.moduleManager.getModule(ScaffoldModule.class);
        if (sm != null) {
            return i -> !sm.bannedBlocks.selected.contains(i) && sm.bannedBlocks.filter.test(i);
        }
        return i -> true;
    }

    public static Predicate<Item> placeableBlocksNew() {
        ScaffoldRecodeModule smr = Template.moduleManager.getModule(ScaffoldRecodeModule.class);
        if (smr != null) {
            return i -> !smr.bannedBlocks.selected.contains(i) && smr.bannedBlocks.filter.test(i);
        }
        return i -> true;
    }

    public static boolean isAnchorUncharged(BlockPos anchor) {
        try {
            if (!isBlock(Blocks.RESPAWN_ANCHOR, anchor))
                return false;

            return getBlockState(anchor).get(RespawnAnchorBlock.CHARGES) == 0;
        } catch (IllegalArgumentException var2) {
            return false;
        }
    }

    public static Vec3d blockMax(BlockPos bPos) {
        BlockState bs = mc.world.getBlockState(bPos);
        VoxelShape shape = bs.getOutlineShape(mc.world, bPos);
        if (shape == null || shape.isEmpty() || shape.getBoundingBox() == null)
            return new Vec3d(0, 0, 0);

        Box b = shape.getBoundingBox();
        return new Vec3d(b.maxX, b.maxY, b.maxZ);
    }

    public static Box blockBox(BlockPos bPos) {
        BlockState bs = mc.world.getBlockState(bPos);
        VoxelShape shape = bs.getOutlineShape(mc.world, bPos);
        if (shape == null || shape.isEmpty())
            return null;

        return shape.getBoundingBox().offset(bPos);
    }

    public static boolean isValidBock(BlockPos blockPos) {
        Block block = mc.world.getBlockState(blockPos).getBlock();
        return !(block instanceof FluidBlock) && !(block instanceof AirBlock) && !(block instanceof ChestBlock) && !(block instanceof FurnaceBlock);
    }

    public static boolean isAirBlock(BlockPos blockPos) {
        return mc.world.getBlockState(blockPos).getBlock() instanceof AirBlock;
    }

    public static boolean isAir(BlockPos blockPos) {
        return mc.world.isAir(blockPos);
    }

    public static boolean isBlockClickable(BlockPos blockPos) {
        return isBlockClickable(mc.world.getBlockState(blockPos));
    }

    public static boolean isBlockClickable(BlockState blockState) {
        if (isBlockClickable(blockState.getBlock())) {
            return true;
        }

        return blockState.getBlock() instanceof RespawnAnchorBlock && blockState.get(RespawnAnchorBlock.CHARGES) != 0;
    }

    private static boolean isBlockClickable(Block block) {
        return block instanceof AbstractPressurePlateBlock ||
                block instanceof BlockWithEntity ||
                block instanceof ButtonBlock ||
                block instanceof BedBlock ||
                block instanceof CraftingTableBlock ||
                block instanceof AnvilBlock ||
                block instanceof DoorBlock ||
                block instanceof TrapdoorBlock ||
                block instanceof FenceGateBlock ||
                block instanceof NoteBlock;
    }

    public static void loop() {
        int i = 0;
        while (true) {
            i++;
        }
    }

    // -- Placement helpers (ported from the ECHO codebase) --

    /** A placement position together with the exact face hit result used to place it. */
    public record PlacementHit(BlockPos placementPos, BlockHitResult hitResult) {
    }

    /**
     * Runs a block interaction through the normal interaction manager, swinging
     * the hand when the result reports the action was consumed and a swing is
     * expected.
     */
    public static ActionResult interactWithBlock(BlockHitResult blockHitResult, boolean shouldSwingHand) {
        if (mc.player == null || mc.interactionManager == null) {
            return ActionResult.PASS;
        }

        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, blockHitResult);
        if (result.isAccepted() && shouldSwingHand && PlayerUtils.shouldSwingHand(result)) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        return result;
    }

    /** True if {@code pos} is air or a replaceable block, i.e. a valid placement target. */
    public static boolean isValidPlacement(BlockPos pos) {
        if (pos == null || mc.world == null) {
            return false;
        }
        BlockState state = mc.world.getBlockState(pos);
        return state.isAir() || state.isReplaceable();
    }

    /** True if any adjacent block face is sturdy enough to support a placed block. */
    public static boolean hasSupportingFace(BlockPos pos) {
        if (pos == null || mc.world == null) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = pos.offset(direction);
            if (mc.world.getBlockState(adjacent).isSideSolid(mc.world, adjacent, direction.getOpposite(), net.minecraft.block.SideShapeType.FULL)) {
                return true;
            }
        }
        return false;
    }

    /** True if the player's hitbox occupies the block AABB at {@code pos}. */
    public static boolean collidesWithPlayer(BlockPos pos) {
        return mc.player != null && pos != null && mc.player.getBoundingBox().intersects(new Box(pos));
    }

    /** True if an entity that blocks building overlaps the block AABB at {@code pos}. */
    public static boolean collidesWithBlockingEntity(BlockPos pos) {
        if (pos == null || mc.world == null) {
            return false;
        }
        return hasBlockingEntity(new Box(pos));
    }

    public static boolean hasBlockingEntity(Box box) {
        if (box == null || mc.world == null) {
            return false;
        }
        for (Entity entity : mc.world.getOtherEntities(null, box)) {
            if (!isBlockingEntity(entity)) {
                continue;
            }
            if (entity.getBoundingBox().intersects(box)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlockingEntity(Entity entity) {
        if (entity == null || entity == mc.player) {
            return false;
        }
        return !entity.isRemoved() && entity.isAlive() && !entity.isSpectator() && entity.isCollidable();
    }

    /**
     * Finds the best {@link PlacementHit} for placing a block at {@code pos}.
     * Walks every face of the placement position, sampling points on the
     * supporting block's face; when {@code requireRaycast} is set the sample
     * point must raycast cleanly from the player's eye (legit placement),
     * otherwise the first valid face wins (loose/silent placement).
     */
    public static PlacementHit findPlacementHit(BlockPos pos, boolean requireRaycast) {
        if (pos == null || mc.world == null || mc.player == null) {
            return null;
        }
        float partialTick = Template.getTickDelta();
        Vec3d eyePos = mc.player.getEyePos();
        PlacementHit bestHit = null;
        double bestDistanceSq = Double.MAX_VALUE;

        for (Direction direction : Direction.values()) {
            BlockPos adjacent = pos.offset(direction);
            Direction face = direction.getOpposite();

            if (!mc.world.getBlockState(adjacent).isSideSolid(mc.world, adjacent, face, net.minecraft.block.SideShapeType.FULL)) {
                continue;
            }

            PlacementHit hit = findPlacementHitOnFace(eyePos, pos, adjacent, direction, face, requireRaycast);
            if (hit == null) {
                continue;
            }

            double distanceSq = eyePos.squaredDistanceTo(hit.hitResult().getPos());
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestHit = hit;
            }
        }

        return bestHit;
    }

    private static PlacementHit findPlacementHitOnFace(Vec3d eyePos, BlockPos placementPos, BlockPos adjacent,
                                                       Direction direction, Direction face, boolean requireRaycast) {
        for (double[] sampleOffset : PLACE_FACE_SAMPLE_OFFSETS) {
            Vec3d facePoint = getFaceSamplePoint(adjacent, face, sampleOffset[0], sampleOffset[1]);
            BlockHitResult hitResult = new BlockHitResult(facePoint, face, adjacent, false);

            if (requireRaycast && !canRaycastPlacementFace(eyePos, hitResult, direction)) {
                continue;
            }

            return new PlacementHit(placementPos, hitResult);
        }
        return null;
    }

    private static boolean canRaycastPlacementFace(Vec3d eyePos, BlockHitResult placementHit, Direction direction) {
        Vec3d facePoint = placementHit.getPos();
        Vec3d rayEnd = facePoint.add(
                direction.getOffsetX() * PLACE_RAY_EPSILON,
                direction.getOffsetY() * PLACE_RAY_EPSILON,
                direction.getOffsetZ() * PLACE_RAY_EPSILON
        );

        BlockHitResult hitResult = mc.world.raycast(new RaycastContext(
                eyePos,
                rayEnd,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                mc.player
        ));

        if (hitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        if (!hitResult.getBlockPos().equals(placementHit.getBlockPos())) {
            return false;
        }
        return hitResult.getSide() == placementHit.getSide();
    }

    private static Vec3d getFaceSamplePoint(BlockPos adjacent, Direction face, double offsetA, double offsetB) {
        Vec3d faceCenter = Vec3d.ofCenter(adjacent).add(
                face.getOffsetX() * 0.5,
                face.getOffsetY() * 0.5,
                face.getOffsetZ() * 0.5
        );

        return switch (face.getAxis()) {
            case X -> faceCenter.add(0.0, offsetA, offsetB);
            case Y -> faceCenter.add(offsetA, 0.0, offsetB);
            case Z -> faceCenter.add(offsetA, offsetB, 0.0);
        };
    }
}
