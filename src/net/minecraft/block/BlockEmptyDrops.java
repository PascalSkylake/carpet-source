package net.minecraft.block;

import java.util.Random;

import carpet.CarpetSettings;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;

public class BlockEmptyDrops extends Block
{
    public BlockEmptyDrops(Material materialIn)
    {
        super(materialIn);
    }

    /**
     * Returns the quantity of items to drop on block destruction.
     */
    public int quantityDropped(Random random)
    {
        return 0;
    }

    /**
     * Get the Item that this Block should drop when harvested.
     */
    public Item getItemDropped(IBlockState state, Random rand, int fortune)
    {
        // Add back bedrock drop if bedrock is broken. CARPET-XCOM
        if(!CarpetSettings.bedrockDropsAsItem){
            return Items.AIR;
        }else{
            return Item.getItemFromBlock(Blocks.BEDROCK);
        }
    }
}