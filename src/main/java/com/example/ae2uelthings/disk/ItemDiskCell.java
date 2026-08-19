package com.example.modid.disk;

import appeng.api.AEApi;
import appeng.api.config.FuzzyMode;
import appeng.api.implementations.items.IStorageCell;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEStack;
import com.example.modid.Tags;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
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
 * AE2 Things の「タイプ無制限」DISKセルを、AE2UELのrv6系アドオンAPI
 * ({@code IStorageCell} + AE2標準の {@code BasicCellHandler}) の枠内で
 * エミュレートするクリーンルーム実装。レシピ・独自NBTフォーマット・GUIは対象外。
 *
 * <p>{@code IStorageCell} は {@code ICellWorkbenchItem} を継承しているため、
 * Cell Workbench 関連のメソッド(isEditable / getUpgradesInventory /
 * getConfigInventory / getFuzzyMode / setFuzzyMode)も実装が必要。DISKセルは
 * タイプフィルターを持たない設計なので、ここでは最小限の実装にとどめている。</p>
 *
 * <h2>設計方針</h2>
 * <ul>
 *   <li>{@link #getTotalTypes(ItemStack)} を大きな固定値 ({@link #MAX_TYPES}) にすることで
 *       「タイプ無制限」を疑似的に実現する（真の無制限ではなく上限512タイプ）。</li>
 *   <li>{@link #getBytesPerType(ItemStack)} を最小値(1)にし、タイプ登録自体が容量を
 *       ほぼ消費しないようにする。</li>
 *   <li>{@link #getBytes(ItemStack)} はティアの実使用可能容量をそのまま返す。</li>
 * </ul>
 */
public class ItemDiskCell extends Item implements IStorageCell {

    /** 「タイプ無制限」を疑似的に表現するための上限値 */
    private static final int MAX_TYPES = 512;
    /** タイプ1つあたりの消費byte。最小値にして容量への影響をほぼ無くす */
    private static final int BYTES_PER_TYPE = 1;

    private final DiskTier tier;

    public ItemDiskCell(DiskTier tier) {
        this.tier = tier;
        setTranslationKey(Tags.MOD_ID + ".disk_cell_" + tier.getSuffix());
        setRegistryName(tier.getItemId());
        setMaxStackSize(1);
        // クリエイティブタブ未設定だとインベントリのどのタブにも表示されないため必須。
        // ひとまず検索タブに表示させておく。専用タブやAE2のタブに寄せたい場合は変更する。
        setCreativeTab(CreativeTabs.SEARCH);
    }

    public DiskTier getTier() {
        return tier;
    }

    // ------------------------------------------------------------------
    // IStorageCell
    // ------------------------------------------------------------------

    @Override
    public int getBytes(ItemStack cellItem) {
        return tier.getUsableBytes();
    }

    @Override
    public int getBytesPerType(ItemStack cellItem) {
        return BYTES_PER_TYPE;
    }

    @Override
    public int getTotalTypes(ItemStack cellItem) {
        return MAX_TYPES;
    }

    @Override
    public boolean isBlackListed(ItemStack itemStack, IAEStack iaeStack) {
        // タイプ制限なし: DISKセルは何でも受け入れる
        return false;
    }

    @Override
    public boolean storableInStorageCell() {
        // DISKセル自体は他のストレージセル内には格納不可
        return false;
    }

    @Override
    public boolean isStorageCell(ItemStack is) {
        return true;
    }

    @Override
    public double getIdleDrain() {
        // TODO: ティアごとの待機時AE/t消費量を調整する。暫定値。
        return 1.0D + tier.ordinal();
    }

    @Override
    public IStorageChannel<?> getChannel() {
        return AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
    }

    // ------------------------------------------------------------------
    // ICellWorkbenchItem (IStorageCell が継承)
    // ------------------------------------------------------------------

    @Override
    public boolean isEditable(ItemStack itemStack) {
        // DISKセルはタイプフィルターを持たないため、Cell Workbenchでの
        // パーティション編集は現状不要。将来フィルター機能を足す場合はtrueにする。
        return false;
    }

    @Override
    public IItemHandler getUpgradesInventory(ItemStack itemStack) {
        // AE2側がnullを前提にせずスロット数等へ直接アクセスするため、
        // nullではなく0スロットの空インベントリを返す必要がある。
        // アップグレードカード機能自体は今回のスコープ外。
        return new ItemStackHandler(0);
    }

    @Override
    public IItemHandler getConfigInventory(ItemStack itemStack) {
        // 同上。タイプフィルター機能は今回のスコープ外。
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
        tooltip.add(I18n.translateToLocal("item." + Tags.MOD_ID + ".disk_cell.disassemble_hint"));
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

    /**
     * セルが空(中に何もアイテムが格納されていない)かどうかを判定する。
     *
     * 要ローカル検証: {@code ICellInventoryHandler}/{@code ICellInventory} の
     * メソッド名がAE2UELの実際のAPIと一致するかは未確認。コンパイルエラーになった
     * 場合は、IDEで {@code AEApi.instance().registries().cell().getCellInventory(...)}
     * が返す型を辿り、実際に使えるメソッド(格納アイテム数や使用バイト数を取得するもの)
     * に置き換えること。API不一致時は例外を握りつぶして「空ではない」扱いにし、
     * 誤って分解してデータを消してしまう事故を避けるフェイルセーフにしている。
     */
    @SuppressWarnings("unchecked")
    private boolean isEmpty(ItemStack stack) {
        try {
            Object handlerObj = AEApi.instance().registries().cell()
                    .getCellInventory(stack, null, getChannel());
            if (handlerObj instanceof appeng.api.storage.ICellInventoryHandler) {
                appeng.api.storage.ICellInventoryHandler<?> cellHandler =
                        (appeng.api.storage.ICellInventoryHandler<?>) handlerObj;
                appeng.api.storage.ICellInventory<?> cellInv = cellHandler.getCellInv();
                if (cellInv != null) {
                    return cellInv.getStoredItemCount() == 0;
                }
            }
        } catch (Exception | LinkageError e) {
            // API不一致等、想定外の場合は安全側(=分解しない)に倒す
            return false;
        }
        return false;
    }

    /** 分解時に返す土台アイテム。ティアに関わらず常にHousing(空のDISKセル)に戻す。 */
    private ItemStack getDowngradeBaseStack() {
        return new ItemStack(com.example.ae2uelthings.disk.ModDiskItems.DISK_HOUSING);
    }

    private static ItemStack createComponentStack(int meta) {
        Item material = Item.getByNameOrId("appliedenergistics2:material");
        if (material == null) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(material, 1, meta);
    }
}