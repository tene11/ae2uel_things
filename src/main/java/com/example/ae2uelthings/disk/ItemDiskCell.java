package com.example.ae2uelthings.disk;

import appeng.api.AEApi;
import appeng.api.config.FuzzyMode;
import appeng.api.storage.ICellWorkbenchItem;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import com.example.ae2uelthings.ExampleMod;
import com.example.ae2uelthings.Tags;
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
import net.minecraftforge.items.ItemStackHandler;

import java.util.List;

/**
 * DISK (Deep Item Storage disK) セル本体。
 *
 * <p><b>修正メモ(重要):</b> 以前は {@code appeng.api.implementations.items.IStorageCell} を
 * 実装していたが、これがAE2本体の {@code BasicCellHandler}(IStorageCell実装アイテムを
 * 自動でセルとして認識する仕組み)にも同時に一致してしまい、
 * {@code getCellInventory()} の単発クエリで実際に
 * {@code appeng.me.storage.BasicCellInventoryHandler} が返ってくることをログで確認した。
 * これはツールチップ表示だけでなく、AE2側の他の内部処理がこの単発クエリ経路を通る場合、
 * 本来使いたい {@code DiskCellHandler}/{@code DiskCellInventoryHandler}(UUID外部化・
 * タイプ無制限)ではなく、AE2標準のBasicCellHandler側の処理(63タイプ上限等)に
 * すり替わってしまうリスクがあったということ。
 *
 * 液体版(ItemDiskFluidCell)は最初からIStorageCellを実装せず ICellWorkbenchItem のみ
 * 実装することでこの曖昧さを回避できていたので、こちらも同じ形に揃えた。
 * 容量関連のメソッド(getBytes等)はもうインターフェースの一部ではなく、
 * DiskCellHandler側から直接呼び出す独自メソッドとして残している。
 */
public class ItemDiskCell extends Item implements ICellWorkbenchItem {

    /** 「タイプ無制限」を疑似的に表現するための上限値 */
    private static final int MAX_TYPES = Integer.MAX_VALUE;
    /** タイプ1つあたりの消費byte。最小値にして容量への影響をほぼ無くす */
    private static final int BYTES_PER_TYPE = 1;

    private final DiskTier tier;

    public ItemDiskCell(DiskTier tier) {
        this.tier = tier;
        setTranslationKey(Tags.MOD_ID + ".disk_cell_" + tier.getSuffix());
        setRegistryName(tier.getItemId());
        setMaxStackSize(1);
        // 専用クリエイティブタブに変更 (以前はCreativeTabs.SEARCHで検索しないと見つからなかった)
        setCreativeTab(ModCreativeTab.INSTANCE);
    }

    public DiskTier getTier() {
        return tier;
    }

    // ------------------------------------------------------------------
    // 容量関連 (IStorageCellのインターフェースではなく、DiskCellHandlerから直接呼ばれる独自メソッド)
    // ------------------------------------------------------------------

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
        // TODO: ティアごとの待機時AE/t消費量を調整する。暫定値。
        return 1.0D + tier.ordinal();
    }

    public IStorageChannel<?> getChannel() {
        return AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
    }

    // ------------------------------------------------------------------
    // ICellWorkbenchItem
    // ------------------------------------------------------------------

    @Override
    public boolean isEditable(ItemStack itemStack) {
        // DISKセルはタイプフィルターを持たないため、Cell Workbenchでの
        // パーティション編集は現状不要。将来フィルター機能を足す場合はtrueにする。
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
                "item." + Tags.MOD_ID + ".disk_cell." + tier.getSuffix() + ".tooltip",
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
            if (handlerObj instanceof DiskCellInventoryHandler) {
                return ((DiskCellInventoryHandler) handlerObj).getUsedBytes();
            }
            ExampleMod.LOGGER.warn(
                    "[{}] getUsedBytesForTooltip: 想定外のhandlerObjが返却されました: {}",
                    Tags.MOD_ID, handlerObj == null ? "null" : handlerObj.getClass().getName());
        } catch (Exception | LinkageError e) {
            ExampleMod.LOGGER.warn("[{}] getUsedBytesForTooltip: 例外発生", Tags.MOD_ID, e);
        }
        return -1;
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World worldIn, EntityPlayer playerIn, EnumHand handIn) {
        ItemStack stack = playerIn.getHeldItem(handIn);
        if (playerIn.isSneaking() && !worldIn.isRemote) {
            if (isEmpty(stack)) {
                ItemStack base = getDowngradeBaseStack();
                ItemStack component = createComponentStack(tier.getComponentMeta());
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

    /** セルが空かどうかの判定。DiskCellHandlerが正しく使われていれば型は必ずDiskCellInventoryHandlerになる。 */
    private boolean isEmpty(ItemStack stack) {
        try {
            Object handlerObj = AEApi.instance().registries().cell()
                    .getCellInventory(stack, null, getChannel());
            if (handlerObj instanceof DiskCellInventoryHandler) {
                return ((DiskCellInventoryHandler) handlerObj).isEmpty();
            }
        } catch (Exception | LinkageError e) {
            // API不一致等、想定外の場合は安全側(=分解しない)に倒す
            return false;
        }
        return false;
    }

    /** 分解時に返す土台アイテム。ティアに関わらず常にHousing(空のDISKセル)に戻す。 */
    private ItemStack getDowngradeBaseStack() {
        return new ItemStack(ModDiskItems.DISK_HOUSING);
    }

    private static ItemStack createComponentStack(int meta) {
        Item material = Item.getByNameOrId("appliedenergistics2:material");
        if (material == null) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(material, 1, meta);
    }
}