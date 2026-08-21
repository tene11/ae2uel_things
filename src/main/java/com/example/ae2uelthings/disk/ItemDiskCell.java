package com.example.ae2uelthings.disk;

import appeng.api.AEApi;
import appeng.api.config.FuzzyMode;
import appeng.api.storage.ICellWorkbenchItem;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import com.example.ae2uelthings.ExampleMod;
import com.example.ae2uelthings.Tags;
import com.example.ae2uelthings.api.IDiskCellDefinition;
import com.example.ae2uelthings.api.DiskCapacityFormat;
import com.example.ae2uelthings.disk.storage.DiskCellInventoryHandler;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.IItemHandler;

import java.util.List;


public class ItemDiskCell extends Item implements ICellWorkbenchItem, IDiskCellDefinition {

    private static final int MAX_TYPES = Integer.MAX_VALUE;

    private static final int BYTES_PER_TYPE = 1;

    private final DiskTier tier;

    public ItemDiskCell(DiskTier tier) {
        this.tier = tier;
        setTranslationKey(Tags.MOD_ID + ".disk_cell_" + tier.getSuffix());
        setRegistryName(tier.getItemId());
        setMaxStackSize(1);

        setCreativeTab(ModCreativeTab.INSTANCE);
    }

    public DiskTier getTier() {
        return tier;
    }


    @Override
    public int getBytes(ItemStack cellItem) {
        return tier.getUsableBytes();
    }

    @Override
    public int getBytesPerType(ItemStack cellItem) {
        return BYTES_PER_TYPE;
    }

    public int getTotalTypes(ItemStack cellItem) {
        return MAX_TYPES;
    }

    @Override
    public double getIdleDrain(ItemStack cellItem) {

        return 1.0D + tier.ordinal();
    }

    @Override
    public IStorageChannel<?> getChannel() {
        return AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
    }



    @Override
    public boolean isEditable(ItemStack itemStack) {
        // タイプフィルター(config)をセルワークベンチで編集できるようにする
        return true;
    }

    @Override
    public IItemHandler getUpgradesInventory(ItemStack itemStack) {
        // Fuzzy Card + Inverter Card の2枠(参考元のitem版DISKと同じ構成、DiskUpgrades参照)
        return DiskUpgrades.createInventory(itemStack, true);
    }

    @Override
    public IItemHandler getConfigInventory(ItemStack itemStack) {
        // タイプフィルター本体(参考元のCellConfigに相当、DiskConfig参照)
        return DiskConfig.createInventory(itemStack);
    }

    @Override
    public FuzzyMode getFuzzyMode(ItemStack itemStack) {
        NBTTagCompound tag = itemStack.getTagCompound();
        if (tag != null && tag.hasKey("FuzzyMode")) {
            try {
                return FuzzyMode.valueOf(tag.getString("FuzzyMode"));
            } catch (IllegalArgumentException ignored) {
                // fall through to default
            }
        }
        return FuzzyMode.IGNORE_ALL;
    }

    @Override
    public void setFuzzyMode(ItemStack itemStack, FuzzyMode fuzzyMode) {
        if (!itemStack.hasTagCompound()) {
            itemStack.setTagCompound(new NBTTagCompound());
        }
        itemStack.getTagCompound().setString("FuzzyMode", fuzzyMode.name());
    }

    // ------------------------------------------------------------------
    // Item overrides
    // ------------------------------------------------------------------

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, World world, List<String> tooltip, ITooltipFlag flag) {
        super.addInformation(stack, world, tooltip, flag);
        tooltip.add(I18n.translateToLocalFormatted(
                "item." + Tags.MOD_ID + ".disk_cell." + tier.getSuffix() + ".tooltip",
                DiskCapacityFormat.format(tier.getUsableBytes())));

        long usedBytes = getUsedBytesForTooltip(stack);
        if (usedBytes >= 0) {
            tooltip.add(I18n.translateToLocalFormatted(
                    "item." + Tags.MOD_ID + ".disk_cell.used_bytes",
                    usedBytes, tier.getUsableBytes()));
        }

        DiskUpgrades.appendTooltip(tooltip, getUpgradesInventory(stack), true);
    }

    private long getUsedBytesForTooltip(ItemStack stack) {
        try {
            Object handlerObj = AEApi.instance().registries().cell()
                    .getCellInventory(stack, null, getChannel());
            if (handlerObj instanceof DiskCellInventoryHandler) {
                return ((DiskCellInventoryHandler) handlerObj).getUsedBytes();
            }
            ExampleMod.LOGGER.warn(
                    "[{}] getUsedBytesForTooltip: : {}",
                    Tags.MOD_ID, handlerObj == null ? "null" : handlerObj.getClass().getName());
        } catch (Exception | LinkageError e) {
            ExampleMod.LOGGER.warn("[{}] getUsedBytesForTooltip: ", Tags.MOD_ID, e);
        }
        return -1;
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World worldIn, EntityPlayer playerIn, EnumHand handIn) {
        ItemStack stack = playerIn.getHeldItem(handIn);
        if (playerIn.isSneaking() && !worldIn.isRemote) {
            if (isEmpty(stack)) {
                ItemStack base = getDowngradeBaseStack();
                ItemStack component = createComponentStack(tier);
                if (!base.isEmpty()) {
                    stack.shrink(1);
                    playerIn.entityDropItem(base, 0.25F);
                    if (!component.isEmpty()) {
                        playerIn.entityDropItem(component, 0.25F);
                    }
                    return new ActionResult<>(EnumActionResult.SUCCESS, stack);
                }
            }
        }
        return super.onItemRightClick(worldIn, playerIn, handIn);
    }

    private boolean isEmpty(ItemStack stack) {
        try {
            Object handlerObj = AEApi.instance().registries().cell()
                    .getCellInventory(stack, null, getChannel());
            if (handlerObj instanceof DiskCellInventoryHandler) {
                return ((DiskCellInventoryHandler) handlerObj).isEmpty();
            }
        } catch (Exception | LinkageError e) {

            return false;
        }
        return false;
    }


    private ItemStack getDowngradeBaseStack() {
        return new ItemStack(ModDiskItems.DISK_HOUSING);
    }

    /**
     * 分解時に返すコンポーネントを作る。tier.hasComponent()がfalse(拡張ティアで
     * NAE2側の素材が未確定、またはMAXティアでそもそも対応レシピが無い場合)は
     * ItemStack.EMPTYを返す(以前はここでmeta=-1のまま appliedenergistics2:material の
     * 不正なItemStackを生成してしまっていたバグを修正)。
     */
    private static ItemStack createComponentStack(DiskTier tier) {
        if (!tier.hasComponent()) {
            return ItemStack.EMPTY;
        }
        Item material = Item.getByNameOrId(tier.getComponentItemId());
        if (material == null) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(material, 1, tier.getComponentMeta());
    }
}