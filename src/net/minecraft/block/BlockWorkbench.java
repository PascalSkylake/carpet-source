package net.minecraft.block;

import carpet.CarpetSettings;
import carpet.helpers.TileEntityCraftingTable;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraft.item.ItemStack;
import net.minecraft.stats.StatList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IInteractionObject;
import net.minecraft.world.World;

import javax.annotation.Nullable;

public class BlockWorkbench extends BlockContainer
{
    protected BlockWorkbench()
    {
        super(Material.WOOD);
        this.setCreativeTab(CreativeTabs.DECORATIONS);
    }

    /**
     * Called when the block is right clicked by a player.
     */
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ)
    {
        if (worldIn.isRemote)
        {
            return true;
        }
        else
        {
            // Code added for auto crafting CARPET-XCOM
            if(hasTileEntity() ) {
                TileEntity blockEntity = worldIn.getTileEntity(pos);
                if (blockEntity instanceof TileEntityCraftingTable) {
                    playerIn.displayGui((IInteractionObject) blockEntity);
                }
            }else {
                playerIn.displayGui(new BlockWorkbench.InterfaceCraftingTable(worldIn, pos));
            }
            playerIn.addStat(StatList.CRAFTING_TABLE_INTERACTION);
            return true;
        }
    }

    public static class InterfaceCraftingTable implements IInteractionObject
        {
            private final World world;
            private final BlockPos position;

            public InterfaceCraftingTable(World worldIn, BlockPos pos)
            {
                this.world = worldIn;
                this.position = pos;
            }

            /**
             * Gets the name of this thing. This method has slightly different behavior depending on the interface (for
             * <a href="https://github.com/ModCoderPack/MCPBot-Issues/issues/14">technical reasons</a> the same method
             * is used for both IWorldNameable and ICommandSender):
             *  
             * <dl>
             * <dt>{@link net.minecraft.util.INameable#getName() INameable.getName()}</dt>
             * <dd>Returns the name of this inventory. If this {@linkplain net.minecraft.inventory#hasCustomName() has a
             * custom name} then this <em>should</em> be a direct string; otherwise it <em>should</em> be a valid
             * translation string.</dd>
             * <dd>However, note that <strong>the translation string may be invalid</strong>, as is the case for {@link
             * net.minecraft.tileentity.TileEntityBanner TileEntityBanner} (always returns nonexistent translation code
             * <code>banner</code> without a custom name), {@link net.minecraft.block.BlockAnvil.Anvil BlockAnvil$Anvil}
             * (always returns <code>anvil</code>), {@link net.minecraft.block.BlockWorkbench.InterfaceCraftingTable
             * BlockWorkbench$InterfaceCraftingTable} (always returns <code>crafting_table</code>), {@link
             * net.minecraft.inventory.InventoryCraftResult InventoryCraftResult} (always returns <code>Result</code>)
             * and the {@link net.minecraft.entity.item.EntityMinecart EntityMinecart} family (uses the entity
             * definition). This is not an exaustive list.</dd>
             * <dd>In general, this method should be safe to use on tile entities that implement IInventory.</dd>
             * <dt>{@link net.minecraft.command.ICommandSender#getName() ICommandSender.getName()} and {@link
             * net.minecraft.entity.Entity#getName() Entity.getName()}</dt>
             * <dd>Returns a valid, displayable name (which may be localized). For most entities, this is the translated
             * version of its translation string (obtained via {@link net.minecraft.entity.EntityList#getEntityString
             * EntityList.getEntityString}).</dd>
             * <dd>If this entity has a custom name set, this will return that name.</dd>
             * <dd>For some entities, this will attempt to translate a nonexistent translation string; see <a
             * href="https://bugs.mojang.com/browse/MC-68446">MC-68446</a>. For {@linkplain
             * net.minecraft.entity.player.EntityPlayer#getName() players} this returns the player's name. For
             * {@linkplain net.minecraft.entity.passive.EntityOcelot ocelots} this may return the translation of
             * <code>entity.Cat.name</code> if it is tamed. For {@linkplain
             * net.minecraft.entity.item.EntityItem#getName() item entities}, this will attempt to return the name of
             * the item in that item entity. In all cases other than players, the custom name will overrule this.</dd>
             * <dd>For non-entity command senders, this will return some arbitrary name, such as "Rcon" or
             * "Server".</dd>
             * </dl>
             */
            public String getName()
            {
                return "crafting_table";
            }

            /**
             * Checks if this thing has a custom name. This method has slightly different behavior depending on the
             * interface (for <a href="https://github.com/ModCoderPack/MCPBot-Issues/issues/14">technical reasons</a>
             * the same method is used for both IWorldNameable and Entity):
             *  
             * <dl>
             * <dt>{@link net.minecraft.util.INameable#hasCustomName() INameable.hasCustomName()}</dt>
             * <dd>If true, then {@link #getName()} probably returns a preformatted name; otherwise, it probably returns
             * a translation string. However, exact behavior varies.</dd>
             * <dt>{@link net.minecraft.entity.Entity#hasCustomName() Entity.hasCustomName()}</dt>
             * <dd>If true, then {@link net.minecraft.entity.Entity#getCustomNameTag() Entity.getCustomNameTag()} will
             * return a non-empty string, which will be used by {@link #getName()}.</dd>
             * </dl>
             */
            public boolean hasCustomName()
            {
                return false;
            }

            /**
             * Returns a displayable component representing this thing's name. This method should be implemented
             * slightly differently depending on the interface (for <a href="https://github.com/ModCoderPack/MCPBot-
             * Issues/issues/14">technical reasons</a> the same method is used for both IWorldNameable and
             * ICommandSender), but unlike {@link #getName()} this method will generally behave sanely.
             *  
             * <dl>
             * <dt>{@link net.minecraft.util.INameable#getDisplayName() INameable.getDisplayName()}</dt>
             * <dd>A normal component. Might be a translation component or a text component depending on the context.
             * Usually implemented as:</dd>
             * <dd><pre><code>return this.{@link net.minecraft.util.INameable#hasCustomName() hasCustomName()} ? new
             * TextComponentString(this.{@link #getName()}) : new TextComponentTranslation(this.{@link
             * #getName()});</code></pre></dd>
             * <dt>{@link net.minecraft.command.ICommandSender#getDisplayName() ICommandSender.getDisplayName()} and
             * {@link net.minecraft.entity.Entity#getDisplayName() Entity.getDisplayName()}</dt>
             * <dd>For most entities, this returns the result of {@link #getName()}, with {@linkplain
             * net.minecraft.scoreboard.ScorePlayerTeam#formatPlayerName scoreboard formatting} and a {@linkplain
             * net.minecraft.entity.Entity#getHoverEvent special hover event}.</dd>
             * <dd>For non-entity command senders, this will return the result of {@link #getName()} in a text
             * component.</dd>
             * </dl>
             */
            public ITextComponent getDisplayName()
            {
                return new TextComponentTranslation(Blocks.CRAFTING_TABLE.getTranslationKey() + ".name", new Object[0]);
            }

            public Container createContainer(InventoryPlayer playerInventory, EntityPlayer playerIn)
            {
                return new ContainerWorkbench(playerInventory, this.world, this.position);
            }

            public String getGuiID()
            {
                return "minecraft:crafting_table";
            }
        }

    // --- code added for auto crafting -- start
    @Override
    public boolean hasTileEntity(){
        return CarpetSettings.autocrafter;
    }

    /**
     * Returns a new instance of a block's tile entity class. Called on placing the block.
     */
    @Nullable
    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileEntityCraftingTable();
    }

    /**
     * @deprecated call via {@link IBlockState#hasComparatorInputOverride()} whenever possible. Implementing/overriding
     * is fine.
     */
    @Override
    public boolean hasComparatorInputOverride(IBlockState state) {
        return true;
    }

    /**
     * @deprecated call via {@link IBlockState#getComparatorInputOverride(World,BlockPos)} whenever possible.
     * Implementing/overriding is fine.
     */
    @Override
    public int getComparatorInputOverride(IBlockState blockState, World worldIn, BlockPos pos) {
        if (!hasTileEntity()) return 0;
        TileEntity te = worldIn.getTileEntity(pos);
        if (!(te instanceof TileEntityCraftingTable)) return 0;
        int count = 0;
        for (ItemStack stack : ((TileEntityCraftingTable) te).inventory.stackList) {
            if (!stack.isEmpty()) count++;
        }
        return (count * 15) / 9;
    }

    /**
     * Called serverside after this block is replaced with another in Chunk, but before the Tile Entity is updated
     */
    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state)
    {
        // Maybe also check for some carpet rule
	if(hasTileEntity())
	{
	    TileEntity tileEntity = worldIn.getTileEntity(pos);
	    if (tileEntity instanceof TileEntityCraftingTable)
	    {
		((TileEntityCraftingTable)tileEntity).dropContent(worldIn, pos);
		// InventoryHelper.dropInventoryItems(worldIn, pos, (TileEntityCraftingTable)tileEntity);
		worldIn.updateComparatorOutputLevel(pos, this);
	    }
	}
        worldIn.removeTileEntity(pos);
        super.breakBlock(worldIn, pos, state);
    }
    // --- code added for auto crafting -- end
}