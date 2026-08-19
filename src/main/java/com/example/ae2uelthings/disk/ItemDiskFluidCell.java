package com.example.ae2uelthings.disk;

import appeng.api.AEApi;
import appeng.api.config.FuzzyMode;
import appeng.api.storage.ICellWorkbenchItem;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IFluidStorageChannel;
import com.example.ae2uelthings.Tags;
import com.example.ae2uelthings.disk.storage.DiskFluidCellInventoryHandler;
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
import net.minecraftforge.items.ItemStackHandler;

import java.util.List;

/**
 * DISKセルの液体版。ItemDiskCellと同じ設計方針(ICellWorkbenchItemのみ実装し、
 * 独自DiskFluidCellHandler経由でBasicCellHandlerの63タイプ/63フルード上限を回避)。
 *
 * 容量モデルは「1mB=1byte」のシンプル版を採用(AE2標準の1byte=8000mBは不採用)。
 * そのためtier.getUsableBytes()の数値がそのままmB上限になる
 * (例: 1kティア = 1000mB = バケツ1個分。要望に応じて後から倍率を導入可能)。
 */
public class ItemDiskFluidCell extends Item implements ICellWorkbenchItem {

    private static final int MAX_TYPES = Integer.MAX_VALUE;
    private static final int BYTES_PER_TYPE = 1;

    private final DiskTier tier;

    public ItemDiskFluidCell(DiskTier tier) {
        this.tier = tier;
        setTranslationKey(Tags.MOD_ID + ".disk_cell_fluid_" + tier.getSuffix());
        setRegistryName("disk_cell_fluid_" + tier.getSuffix());
        setMaxStackSize(1);
        setCreativeTab(ModCreativeTab.INSTANCE); // 専用クリエイティブタブに変更
    }

    public DiskTier getTier() {
        return tier;
    }

    public int getBytes(ItemStack cellItem) {
        return tier.getUsableBytes();
    }

    public int getBytesPerType(ItemStack cellItem) {
        return BYTES_PER_TYPE;
    }

    public int getTotalTypes(ItemStack cellItem) {
        return MAX_TYPES;
    }

    public double getIdleDrain() {
        return 1.0D + tier.ordinal();
    }

    public IStorageChannel<?> getChannel() {
        return AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class);
    }

    // ------------------------------------------------------------------
    // ICellWorkbenchItem
    // ------------------------------------------------------------------

    @Override
    public boolean isEditable(ItemStack itemStack) {
        return false;
    }

    @Override
    public IItemHandler getUpgradesInventory(ItemStack itemStack) {
        return new ItemStackHandler(0);
    }

    @Override
    public IItemHandler getConfigInventory(ItemStack itemStack) {
        return new ItemStackHandler(0);
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
                "item." + Tags.MOD_ID + ".disk_cell_fluid." + tier.getSuffix() + ".tooltip",
                tier.getUsableBytes()));

        long usedBytes = getUsedBytesForTooltip(stack);
        if (usedBytes >= 0) {
            tooltip.add(I18n.translateToLocalFormatted(
                    "item." + Tags.MOD_ID + ".disk_cell.used_bytes",
                    usedBytes, tier.getUsableBytes()));
        }
    }

    private long getUsedBytesForTooltip(ItemStack stack) {
        try {
            Object handlerObj = AEApi.instance().registries().cell()
                    .getCellInventory(stack, null, getChannel());
            if (handlerObj instanceof DiskFluidCellInventoryHandler) {
                return ((DiskFluidCellInventoryHandler) handlerObj).getUsedBytes();
            }
        } catch (Exception | LinkageError e) {
            // 取得できない場合は表示をスキップ
        }
        return -1;
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World worldIn, EntityPlayer playerIn, EnumHand handIn) {
        ItemStack stack = playerIn.getHeldItem(handIn);
        if (playerIn.isSneaking() && !worldIn.isRemote) {
            if (isEmpty(stack)) {
                ItemStack base = getDowngradeBaseStack();
                if (!base.isEmpty()) {
                    stack.shrink(1);
                    playerIn.entityDropItem(base, 0.25F);
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
            if (handlerObj instanceof DiskFluidCellInventoryHandler) {
                return ((DiskFluidCellInventoryHandler) handlerObj).isEmpty();
            }
        } catch (Exception | LinkageError e) {
            return false;
        }
        return false;
    }

    /**
     * 分解時に返す土台アイテム。
     * TODO 要ローカル検証: 現状はアイテム版と同じDISK_HOUSINGに戻すだけの簡易実装。
     * コンポーネント(appliedenergistics2:material等)を伴う分解にしたい場合は、
     * 液体用コンポーネントのmetaを別途調べて追加すること。
     */
    private ItemStack getDowngradeBaseStack() {
        return new ItemStack(ModDiskItems.DISK_HOUSING);
    }
}