package eu.ha3.presencefootsteps.world;

import java.util.Objects;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.math.BlockPos;

public record Association (
        BlockState state,
        BlockPos pos,
        LivingEntity source,

        String acousticNames,
        String wetAcousticNames,
        String foliageAcousticNames
) {
    public static final Association NOT_EMITTER = new Association(Blocks.AIR.getDefaultState(), BlockPos.ORIGIN, null, Emitter.NOT_EMITTER, Emitter.NOT_EMITTER, Emitter.NOT_EMITTER);

    public static Association of(BlockState state, BlockPos pos, LivingEntity source, String dry, String wet, String foliage) {
        if (Emitter.isResult(dry) || Emitter.isResult(wet) || Emitter.isResult(foliage)) {
            return new Association(state, pos.toImmutable(), source, dry, wet, foliage);
        }
        return NOT_EMITTER;
    }

    public boolean isNull() {
        return this == NOT_EMITTER;
    }

    public boolean isNotEmitter() {
        return isNull() || (
               Emitter.isNonEmitter(acousticNames)
            && Emitter.isNonEmitter(wetAcousticNames)
            && Emitter.isNonEmitter(foliageAcousticNames)
        );
    }

    public boolean hasAssociation() {
        return !isNotEmitter();
    }

    public BlockSoundGroup soundGroup() {
        return state.getSoundGroup();
    }

    public boolean dataEquals(Association other) {
        return hasAssociation() && Objects.equals(acousticNames, other.acousticNames);
    }
}
