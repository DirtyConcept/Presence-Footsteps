package eu.ha3.presencefootsteps.world;

import eu.ha3.presencefootsteps.compat.ContraptionCollidable;
import eu.ha3.presencefootsteps.sound.SoundEngine;
import eu.ha3.presencefootsteps.util.PlayerUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Direction.Axis;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

public class PFSolver implements Solver {
    private static final double TRAP_DOOR_OFFSET = 0.1;

    private final SoundEngine engine;

    private long lastUpdateTime;
    private final Long2ObjectOpenHashMap<Association> associationCache = new Long2ObjectOpenHashMap<>();

    public PFSolver(SoundEngine engine) {
        this.engine = engine;
    }

    private BlockState getBlockStateAt(Entity entity, BlockPos pos) {
        World world = entity.getWorld();
        BlockState state = world.getBlockState(pos);

        if (state.isAir() && (entity instanceof ContraptionCollidable collidable)) {
            state = collidable.getCollidedStateAt(pos);
        }

        return state.getAppearance(world, pos, Direction.UP, state, pos);
    }

    private Box getCollider(Entity player) {
        Box collider = player.getBoundingBox();
        // normalize to the bottom of the block
        // so we can detect carpets on top of fences
        collider = collider.offset(0, -(collider.minY - Math.floor(collider.minY)), 0);

        double expansionRatio = 0.1;

        // add buffer
        collider = collider.expand(expansionRatio);
        if (player.isSprinting()) {
            collider = collider.expand(0.3, 0.5, 0.3);
        }
        return collider;
    }

    private boolean checkCollision(World world, BlockState state, BlockPos pos, Box collider) {
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) {
            shape = state.getOutlineShape(world, pos);
        }
        return shape.isEmpty() || shape.getBoundingBox().offset(pos).intersects(collider);
    }

    @Override
    public Association findAssociation(AssociationPool associations, LivingEntity ply, BlockPos pos, String strategy) {
        if (!MESSY_FOLIAGE_STRATEGY.equals(strategy)) {
            return Association.NOT_EMITTER;
        }
        pos = pos.up();
        BlockState above = getBlockStateAt(ply, pos);

        SoundsKey foliage = engine.getIsolator().blocks().getAssociation(above, Substrates.FOLIAGE);

        // we discard the normal block association, and mark the foliage as detected
        if (foliage.isEmitter() && engine.getIsolator().blocks().getAssociation(above, Substrates.MESSY) == SoundsKey.MESSY_GROUND) {
            return Association.of(above, pos, ply, SoundsKey.NON_EMITTER, SoundsKey.NON_EMITTER, foliage);
        }

        return Association.NOT_EMITTER;
    }

    @Override
    public Association findAssociation(AssociationPool associations, LivingEntity ply, double verticalOffsetAsMinus, boolean isRightFoot) {

        double rot = Math.toRadians(MathHelper.wrapDegrees(ply.getYaw()));

        Vec3d pos = ply.getPos();

        float feetDistanceToCenter = 0.2f * (isRightFoot ? -1 : 1)
                * PlayerUtil.getScale(ply) // scale foot offset by the player's scale
        ;

        BlockPos footPos = BlockPos.ofFloored(
            pos.x + Math.cos(rot) * feetDistanceToCenter,
            ply.getBoundingBox().getMin(Axis.Y) - TRAP_DOOR_OFFSET - verticalOffsetAsMinus,
            pos.z + Math.sin(rot) * feetDistanceToCenter
        );

        if (!(ply instanceof OtherClientPlayerEntity)) {
            Vec3d vel = ply.getVelocity();

            if (vel.lengthSquared() != 0 && Math.abs(vel.y) < 0.004) {
                return Association.NOT_EMITTER; // Don't play sounds on every tiny bounce
            }
        }

        long time = ply.getWorld().getTime();
        if (time != lastUpdateTime) {
            lastUpdateTime = time;
            associationCache.clear();
        }

        Association cached = associationCache.get(footPos.asLong());
        if (cached != null) {
            return cached;
        }

        Box collider = getCollider(ply);

        BlockPos.Mutable mutableFootPos = footPos.mutableCopy();

        if (feetDistanceToCenter > 1) {
            for (BlockPos underfootPos : BlockPos.iterateOutwards(footPos, (int)feetDistanceToCenter, 2, (int)feetDistanceToCenter)) {
                mutableFootPos.set(underfootPos);
                Association assos = findAssociation(associations, ply, collider, underfootPos, mutableFootPos);
                if (assos.isResult()) {
                    associationCache.put(footPos.asLong(), assos);
                    return assos;
                }
            }
        }

        Association assos = findAssociation(associations, ply, collider, footPos, mutableFootPos);
        associationCache.put(footPos.asLong(), assos);
        return assos;
    }

    private Association findAssociation(AssociationPool associations, LivingEntity player, Box collider, BlockPos originalFootPos, BlockPos.Mutable pos) {
        Association association;

        // If it didn't work, the player has walked over the air on the border of a block.
        // ------ ------ --> z
        // | o | < player is here
        // wool | air |
        // ------ ------
        // |
        // V z
        if ((association = findAssociation(associations, player, pos, collider)).isResult()) {
            return association;
        }

        pos.set(originalFootPos);
        // Create a trigo. mark contained inside the block the player is over
        double xdang = (player.getX() - pos.getX()) * 2 - 1;
        double zdang = (player.getZ() - pos.getZ()) * 2 - 1;
        // -1 0 1
        // ------- -1
        // | o |
        // | + | 0 --> x
        // | |
        // ------- 1
        // |
        // V z

        // If the player is at the edge of that
        if (Math.max(Math.abs(xdang), Math.abs(zdang)) <= 0.2f) {
            return association;
        }
        // Find the maximum absolute value of X or Z
        boolean isXdangMax = Math.abs(xdang) > Math.abs(zdang);
        // --------------------- ^ maxofZ-
        // | . . |
        // | . . |
        // | o . . |
        // | . . |
        // | . |
        // < maxofX- maxofX+ >
        // Take the maximum border to produce the sound
            // If we are in the positive border, add 1, else subtract 1
        if ((association = findAssociation(associations, player, isXdangMax
                ? pos.move(Direction.EAST, xdang > 0 ? 1 : -1)
                : pos.move(Direction.SOUTH, zdang > 0 ? 1 : -1), collider)).isResult()) {
            return association;
        }

        // If that didn't work, then maybe the footstep hit in the
        // direction of walking
        // Try with the other closest block
        pos.set(originalFootPos);
        // Take the maximum direction and try with the orthogonal direction of it
        return findAssociation(associations, player, isXdangMax
                ? pos.move(Direction.SOUTH, zdang > 0 ? 1 : -1)
                : pos.move(Direction.EAST, xdang > 0 ? 1 : -1), collider);
    }

    private Association findAssociation(AssociationPool associations, LivingEntity entity, BlockPos.Mutable pos, Box collider) {
        associations.reset();
        BlockState target = getBlockStateAt(entity, pos);

        // Try to see if the block above is a carpet...
        pos.move(Direction.UP);
        final boolean hasRain = entity.getWorld().hasRain(pos);
        BlockState carpet = getBlockStateAt(entity, pos);
        VoxelShape shape = carpet.getOutlineShape(entity.getWorld(), pos);
        boolean isValidCarpet = !shape.isEmpty() && shape.getMax(Axis.Y) < 0.3F;
        SoundsKey association = SoundsKey.UNASSIGNED;
        SoundsKey foliage = SoundsKey.UNASSIGNED;
        SoundsKey wetAssociation = SoundsKey.UNASSIGNED;

        if (isValidCarpet && (association = associations.get(pos, carpet, Substrates.CARPET)).isEmitter()) {
            target = carpet;
            // reference frame moved up by 1
        } else {
            pos.move(Direction.DOWN);
            // This condition implies that if the carpet is NOT_EMITTER, solving will
            // CONTINUE with the actual block surface the player is walking on
            if (target.isAir()) {
                pos.move(Direction.DOWN);
                BlockState fence = getBlockStateAt(entity, pos);

                if ((association = associations.get(pos, fence, Substrates.FENCE)).isResult()) {
                    carpet = target;
                    target = fence;
                    // reference frame moved down by 1
                } else {
                    pos.move(Direction.UP);
                }
            }

            if (!association.isResult()) {
                association = associations.get(pos, target, Substrates.DEFAULT);
            }

            if (engine.getConfig().foliageSoundsVolume.get() > 0) {
                if (entity.getEquippedStack(EquipmentSlot.FEET).isEmpty() || entity.isSprinting()) {
                    if (association.isEmitter() && carpet.getCollisionShape(entity.getWorld(), pos).isEmpty()) {
                        // This condition implies that foliage over a NOT_EMITTER block CANNOT PLAY
                        // This block must not be executed if the association is a carpet
                        pos.move(Direction.UP);
                        foliage = associations.get(pos, carpet, Substrates.FOLIAGE);
                        pos.move(Direction.DOWN);
                    }
                }
            }
        }

        // Check collision against small blocks
        if (association.isResult() && !checkCollision(entity.getWorld(), target, pos, collider)) {
            association = SoundsKey.NON_EMITTER;
        }

        if (association.isEmitter() && (hasRain
                || (!associations.wasLastMatchGolem() && (
                   target.getFluidState().isIn(FluidTags.WATER)
                || carpet.getFluidState().isIn(FluidTags.WATER)
        )))) {
            // Only if the block is open to the sky during rain
            // or the block is submerged
            // or the block is waterlogged
            // then append the wet effect to footsteps
            wetAssociation = associations.get(pos, target, Substrates.WET);
        }

        return Association.of(target, pos, entity, association, wetAssociation, foliage);
    }
}
