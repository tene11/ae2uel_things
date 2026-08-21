package com.example.ae2uelthings.disk;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.items.ItemStackHandler;

/**
 * DISKセルのタイプフィルター(config inventory)関連の共通処理。
 *
 * <p>参考元(io.github.lapis256.ae2_mega_things)の {@code AbstractDISKDrive#getConfigInventory}
 * は {@code appeng.items.contents.CellConfig.create(getKeyType().filter(), stack)} という
 * AE2本体(NeoForge版)側のヘルパーをそのまま使っており、スロット数などの詳細はAE2側に
 * 委譲されている。ae2uelthings(AE2 rv6/1.12.2)には同等のヘルパーが存在しないため、
 * Forge標準の {@link ItemStackHandler} をセル本体のNBT(タグ名 "Config")へ永続化する形で
 * 自前実装する。</p>
 *
 * <p>これにより、DiskCellInventoryHandler側で「フィルターに設定されたタイプのみ
 * 新規タイプとして受け付ける/拒否する(Inverter Cardで反転)」という、参考元と同等の
 * タイプフィルター機構が実際に機能するようになる。フィルター判定はItem+metadataの
 * 一致のみを見る(NBTやスタックサイズは見ない)、AE2標準セルのタイプフィルターと
 * 同じ粒度。</p>
 *
 * <p><b>要ローカル検証:</b> スロット数({@link #CONFIG_SLOTS})はAE2 rv6標準セルの
 * タイプフィルターグリッド(9列×7行=63)を参考にした値。セルワークベンチのGUIで
 * 表示崩れが無いか確認すること。</p>
 */
public final class DiskConfig {

    private static final String TAG_CONFIG = "Config";

    /** AE2 rv6標準セルのタイプフィルターグリッド(9x7)に合わせた値。 */
    public static final int CONFIG_SLOTS = 63;

    private DiskConfig() {
    }

    /**
     * DISKセル用のタイプフィルターインベントリを作る(呼び出しのたびにNBTから復元する)。
     *
     * @param cellItem 永続化先となるセル本体のItemStack
     */
    public static ItemStackHandler createInventory(ItemStack cellItem) {
        ItemStackHandler handler = new ItemStackHandler(CONFIG_SLOTS) {
            @Override
            protected void onContentsChanged(int slot) {
                NBTTagCompound tag = cellItem.hasTagCompound() ? cellItem.getTagCompound() : new NBTTagCompound();
                tag.setTag(TAG_CONFIG, this.serializeNBT());
                cellItem.setTagCompound(tag);
            }
        };
        NBTTagCompound tag = cellItem.getTagCompound();
        if (tag != null && tag.hasKey(TAG_CONFIG)) {
            handler.deserializeNBT(tag.getCompoundTag(TAG_CONFIG));
        }
        return handler;
    }

    /** configに1つでも(空でない)フィルターアイテムが設定されているか。 */
    public static boolean hasAnyFilter(net.minecraftforge.items.IItemHandler config) {
        if (config == null) {
            return false;
        }
        for (int i = 0; i < config.getSlots(); i++) {
            if (!config.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** candidateのItem+metadataが、config内のいずれかのフィルターと一致するか(NBT/個数は見ない)。 */
    public static boolean matches(net.minecraftforge.items.IItemHandler config, ItemStack candidate) {
        if (config == null || candidate == null || candidate.isEmpty()) {
            return false;
        }
        for (int i = 0; i < config.getSlots(); i++) {
            ItemStack filterStack = config.getStackInSlot(i);
            if (filterStack.isEmpty()) {
                continue;
            }
            if (filterStack.getItem() == candidate.getItem() && filterStack.getMetadata() == candidate.getMetadata()) {
                return true;
            }
        }
        return false;
    }

    /**
     * candidate(流体)が、config内のいずれかのフィルター(バケツ等の流体コンテナアイテム)と
     * 一致するか。フィルタースロットのアイテムは {@link FluidUtil#getFluidContained} で
     * 中身の流体を取り出して比較する(バケツ以外の流体コンテナアイテムでも動作する)。
     *
     * 要ローカル検証: AE2 rv6のセルワークベンチが、fluidチャンネルのgetConfigInventory()を
     * どのスロットUIで描画するか(バケツドラッグ&ドロップに対応しているか)をIDE上/実機で
     * 必ず確認すること。対応していない場合、プレイヤーがフィルターへ流体を設定する手段が
     * 無くなってしまう。
     */
    public static boolean matchesFluid(net.minecraftforge.items.IItemHandler config, FluidStack candidate) {
        if (config == null || candidate == null || candidate.getFluid() == null) {
            return false;
        }
        for (int i = 0; i < config.getSlots(); i++) {
            ItemStack filterStack = config.getStackInSlot(i);
            if (filterStack.isEmpty()) {
                continue;
            }
            FluidStack contained = FluidUtil.getFluidContained(filterStack);
            if (contained != null && contained.getFluid() == candidate.getFluid()) {
                return true;
            }
        }
        return false;
    }
}