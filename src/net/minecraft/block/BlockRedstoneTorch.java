package net.minecraft.block;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import redstone.multimeter.block.MeterableBlock;
import redstone.multimeter.block.PowerSource;

import carpet.CarpetSettings;

public class BlockRedstoneTorch extends BlockTorch implements MeterableBlock /* RSMM */, PowerSource /* RSMM */
{
    private static final Map<World, List<BlockRedstoneTorch.Toggle>> toggles = Maps.<World, List<BlockRedstoneTorch.Toggle>>newHashMap();
    private final boolean isOn;

    private boolean isBurnedOut(World worldIn, BlockPos pos, boolean turnOff)
    {
        if (!toggles.containsKey(worldIn))
        {
            toggles.put(worldIn, Lists.newArrayList());
        }

        List<BlockRedstoneTorch.Toggle> list = (List)toggles.get(worldIn);

        if (turnOff)
        {
            list.add(new BlockRedstoneTorch.Toggle(pos, worldIn.getTotalWorldTime()));
        }

        int i = 0;

        for (int j = 0; j < list.size(); ++j)
        {
            BlockRedstoneTorch.Toggle blockredstonetorch$toggle = list.get(j);

            if (blockredstonetorch$toggle.pos.equals(pos))
            {
                ++i;

                if (i >= 8)
                {
                    return true;
                }
            }
        }

        return false;
    }

    protected BlockRedstoneTorch(boolean isOn)
    {
        this.isOn = isOn;
        this.setTickRandomly(true);
        this.setCreativeTab((CreativeTabs)null);
    }

    /**
     * How many world ticks before ticking
     */
    public int tickRate(World worldIn)
    {
        return 2;
    }

    /**
     * Called after the block is set in the Chunk data, but before the Tile Entity is set
     */
    public void onBlockAdded(World worldIn, BlockPos pos, IBlockState state)
    {
        if (this.isOn)
        {
            for (EnumFacing enumfacing : EnumFacing.values())
            {
                worldIn.notifyNeighborsOfStateChange(pos.offset(enumfacing), this, false);
            }
        }
    }

    /**
     * Called serverside after this block is replaced with another in Chunk, but before the Tile Entity is updated
     */
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state)
    {
        if (this.isOn)
        {
            for (EnumFacing enumfacing : EnumFacing.values())
            {
                worldIn.notifyNeighborsOfStateChange(pos.offset(enumfacing), this, false);
            }
        }
    }

    /**
     * @deprecated call via {@link IBlockState#getWeakPower(IBlockAccess,BlockPos,EnumFacing)} whenever possible.
     * Implementing/overriding is fine.
     */
    public int getWeakPower(IBlockState blockState, IBlockAccess blockAccess, BlockPos pos, EnumFacing side)
    {
        return this.isOn && blockState.getValue(FACING) != side ? 15 : 0;
    }

    // RSMM - capture return value
    private boolean shouldBeOff(World worldIn, BlockPos pos, IBlockState state)
    {
        EnumFacing enumfacing = ((EnumFacing)state.getValue(FACING)).getOpposite();
        boolean powered = worldIn.isSidePowered(pos.offset(enumfacing), enumfacing);
        logPowered(worldIn, pos, powered); // RSMM
        return powered;
    }

    /**
     * Called randomly when setTickRandomly is set to true (used by e.g. crops to grow, etc.)
     */
    public void randomTick(World worldIn, BlockPos pos, IBlockState state, Random random)
    {
    }

    public void updateTick(World worldIn, BlockPos pos, IBlockState state, Random rand)
    {
        boolean flag = this.shouldBeOff(worldIn, pos, state);
        List<BlockRedstoneTorch.Toggle> list = (List)toggles.get(worldIn);

        while (list != null && !list.isEmpty() && worldIn.getTotalWorldTime() - (list.get(0)).time > 60L)
        {
            list.remove(0);
        }

        if (this.isOn)
        {
            if (flag)
            {
                worldIn.setBlockState(pos, Blocks.UNLIT_REDSTONE_TORCH.getDefaultState().withProperty(FACING, state.getValue(FACING)), 3);

                if (this.isBurnedOut(worldIn, pos, true))
                {
                    worldIn.playSound((EntityPlayer)null, pos, SoundEvents.BLOCK_REDSTONE_TORCH_BURNOUT, SoundCategory.BLOCKS, 0.5F, 2.6F + (worldIn.rand.nextFloat() - worldIn.rand.nextFloat()) * 0.8F);

                    for (int i = 0; i < 5; ++i)
                    {
                        double d0 = (double)pos.getX() + rand.nextDouble() * 0.6D + 0.2D;
                        double d1 = (double)pos.getY() + rand.nextDouble() * 0.6D + 0.2D;
                        double d2 = (double)pos.getZ() + rand.nextDouble() * 0.6D + 0.2D;
                        worldIn.spawnParticle(EnumParticleTypes.SMOKE_NORMAL, d0, d1, d2, 0.0D, 0.0D, 0.0D);
                    }

                    worldIn.scheduleUpdate(pos, worldIn.getBlockState(pos).getBlock(), 160);
                }
            }
        }
        else if (!flag && !this.isBurnedOut(worldIn, pos, false))
        {
            worldIn.setBlockState(pos, Blocks.REDSTONE_TORCH.getDefaultState().withProperty(FACING, state.getValue(FACING)), 3);
        }
    }

    /**
     * Called when a neighboring block was changed and marks that this state should perform any checks during a neighbor
     * change. Cases may include when redstone power is updated, cactus blocks popping off due to a neighboring solid
     * block, etc.
     */
    public void neighborChanged(IBlockState state, World worldIn, BlockPos pos, Block blockIn, BlockPos fromPos)
    {
        if (!this.onNeighborChangeInternal(worldIn, pos, state))
        {
            if ( (this.isOn == this.shouldBeOff(worldIn, pos, state)) && !(CarpetSettings.inconsistentRedstoneTorchesFix && worldIn.isBlockTickPending(pos, this)))
            {
                worldIn.scheduleUpdate(pos, this, this.tickRate(worldIn));
            }
        }
    }

    /**
     * @deprecated call via {@link IBlockState#getStrongPower(IBlockAccess,BlockPos,EnumFacing)} whenever possible.
     * Implementing/overriding is fine.
     */
    public int getStrongPower(IBlockState blockState, IBlockAccess blockAccess, BlockPos pos, EnumFacing side)
    {
        return side == EnumFacing.DOWN ? blockState.getWeakPower(blockAccess, pos, side) : 0;
    }

    /**
     * Get the Item that this Block should drop when harvested.
     */
    public Item getItemDropped(IBlockState state, Random rand, int fortune)
    {
        return Item.getItemFromBlock(Blocks.REDSTONE_TORCH);
    }

    /**
     * Can this block provide power. Only wire currently seems to have this change based on its state.
     * @deprecated call via {@link IBlockState#canProvidePower()} whenever possible. Implementing/overriding is fine.
     */
    public boolean canProvidePower(IBlockState state)
    {
        return true;
    }

    public ItemStack getItem(World worldIn, BlockPos pos, IBlockState state)
    {
        return new ItemStack(Blocks.REDSTONE_TORCH);
    }

    public boolean isAssociatedBlock(Block other)
    {
        return other == Blocks.UNLIT_REDSTONE_TORCH || other == Blocks.REDSTONE_TORCH;
    }

    // RSMM
    @Override
    public boolean logPoweredOnBlockUpdate() {
        return false;
    }

    // RSMM
    @Override
    public boolean isPowered(World world, BlockPos pos, IBlockState state) {
        return shouldBeOff(world, pos, state);
    }

    // RSMM
    @Override
    public boolean isActive(World world, BlockPos pos, IBlockState state) {
        return isOn;
    }

    // RSMM
    @Override
    public int getPowerLevel(World world, BlockPos pos, IBlockState state) {
        return isOn ? MAX_POWER : MIN_POWER;
    }

    static class Toggle
        {
            BlockPos pos;
            long time;

            public Toggle(BlockPos pos, long time)
            {
                this.pos = pos;
                this.time = time;
            }
        }
}