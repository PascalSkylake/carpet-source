package net.minecraft.world;

import javax.annotation.Nullable;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import carpet.carpetclient.CarpetClientChunkLogger;

public interface IBlockAccess
{
    @Nullable
    TileEntity getTileEntity(BlockPos pos);

    //CM chunk load reason
    default IBlockState getBlockState(BlockPos pos, String reason)
    {
        String prevReason = CarpetClientChunkLogger.reason;
        CarpetClientChunkLogger.reason = reason;
        IBlockState prev = getBlockState(pos);
        CarpetClientChunkLogger.reason = prevReason;
        return prev;
    }

    IBlockState getBlockState(BlockPos pos);

    /**
     * Checks to see if an air block exists at the provided location. Note that this only checks to see if the blocks
     * material is set to air, meaning it is possible for non-vanilla blocks to still pass this check.
     */
    boolean isAirBlock(BlockPos pos);

    int getStrongPower(BlockPos pos, EnumFacing direction);
}