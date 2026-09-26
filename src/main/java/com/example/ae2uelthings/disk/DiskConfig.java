package com.example.ae2uelthings.disk;

import appeng.api.AEApi;
import appeng.api.config.FuzzyMode;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.util.prioritylist.FuzzyPriorityList;
import appeng.util.prioritylist.IPartitionList;
import appeng.util.prioritylist.PrecisePriorityList;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.items.IItemHandler;
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
 * <p>フィルターの判定そのものは {@link #createPartitionList} で、AE2UEL標準セル
 * (BasicCellInventoryHandler)と同じ PrecisePriorityList / FuzzyPriorityList を構築して行う。
 * そのため判定の粒度(Precise時はItem+meta+NBTの完全一致、Fuzzy時はFuzzyModeに従った
 * あいまい一致)もAE2標準セルと同じになる。</p>
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

    /**
     * 液体DISKセル用のタイプフィルターインベントリを作る。
     *
     * <p>修正メモ(液体フィルターをバケツで設定しても効かない件): 以前は液体DISKもアイテム版と
     * 同じ {@link #createInventory} を使っており、置いたバケツ等がそのまま保存されていた。
     * AE2UELの液体セルは {@code appeng.fluids.helper.FluidCellConfig} で、置かれた液体コンテナを
     * その場で「液体ダミーアイテム」(FluidDummyItem、液体アイコンで表示される)に変換して保存している。
     * これと同じ処理にして、フィルターには常に液体そのものが保存されるようにした。</p>
     *
     * <ul>
     *   <li>バケツ等の液体コンテナ・液体ダミーアイテム → 中身の液体(1000mB)の液体ダミーアイテムに変換</li>
     *   <li>液体を含まないアイテム → 受け付けない(スロットは変化しない。FluidCellConfigと同じ)</li>
     *   <li>既に保存済みのバケツ(旧形式) → 読み込み時に液体ダミーアイテムへ変換して扱う</li>
     * </ul>
     *
     * <p>変換には公開APIの {@code IAEFluidStack#asItemStackRepresentation()} を使う
     * (AE2UEL内部ではFluidDummyItemを返す実装であることをソースで確認済み)。</p>
     */
    public static ItemStackHandler createFluidInventory(ItemStack cellItem) {
        FluidConfigInventory handler = new FluidConfigInventory(cellItem);
        NBTTagCompound tag = cellItem.getTagCompound();
        if (tag != null && tag.hasKey(TAG_CONFIG)) {
            handler.deserializeNBT(tag.getCompoundTag(TAG_CONFIG));
            handler.convertLegacyEntries();
        }
        return handler;
    }

    /** 液体DISK用のフィルターインベントリ本体({@link #createFluidInventory} 参照)。 */
    private static final class FluidConfigInventory extends ItemStackHandler {

        private final ItemStack cellItem;

        FluidConfigInventory(ItemStack cellItem) {
            super(CONFIG_SLOTS);
            this.cellItem = cellItem;
        }

        @Override
        protected void onContentsChanged(int slot) {
            NBTTagCompound tag = cellItem.hasTagCompound() ? cellItem.getTagCompound() : new NBTTagCompound();
            tag.setTag(TAG_CONFIG, this.serializeNBT());
            cellItem.setTagCompound(tag);
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            ItemStack converted = toFluidFilterStack(stack);
            if (!stack.isEmpty() && converted.isEmpty()) {
                // 液体を含まないアイテムは無視する(FluidCellConfig#setStackInSlotと同じ)
                return;
            }
            super.setStackInSlot(slot, converted);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            ItemStack converted = toFluidFilterStack(stack);
            if (converted.isEmpty()) {
                return stack;
            }
            ItemStack rest = super.insertItem(slot, converted, simulate);
            return rest.isEmpty() ? ItemStack.EMPTY : stack;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return stack.isEmpty() || !toFluidFilterStack(stack).isEmpty();
        }

        /**
         * 旧形式(バケツ等をそのまま保存していたデータ)を、読み込み直後に液体ダミーアイテムへ置き換える。
         * NBTへは書き戻さない(次にフィルターを編集した時に新形式で保存される)ため、
         * onContentsChangedを経由しないよう stacks を直接書き換える。
         */
        void convertLegacyEntries() {
            for (int i = 0; i < stacks.size(); i++) {
                ItemStack original = stacks.get(i);
                if (!original.isEmpty()) {
                    stacks.set(i, toFluidFilterStack(original));
                }
            }
        }
    }

    /**
     * 液体コンテナ/液体ダミーアイテムを、中身の液体(1000mB)を表す液体ダミーアイテムに変換する。
     * 液体を含まない場合は {@link ItemStack#EMPTY}。
     */
    private static ItemStack toFluidFilterStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        IAEFluidStack fluid;
        try {
            // AE2UELの液体チャンネルは、FluidDummyItemとForgeの液体コンテナ(バケツ等)の両方を扱える
            fluid = AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class).createStack(stack);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
        if (fluid == null) {
            return ItemStack.EMPTY;
        }
        fluid.setStackSize(Fluid.BUCKET_VOLUME);
        ItemStack representation = fluid.asItemStackRepresentation();
        return representation == null ? ItemStack.EMPTY : representation;
    }

    /** configに1つでも(空でない)フィルターアイテムが設定されているか。 */
    public static boolean hasAnyFilter(IItemHandler config) {
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

    /**
     * configの内容からタイプフィルター(パーティションリスト)を構築する。
     *
     * <p>AE2UEL標準セルの BasicCellInventoryHandler のコンストラクタと同じ手順:
     * config各スロットを {@code channel.createStack(is)} でAEスタックに変換してリストに集め、
     * Fuzzy Cardがあれば {@link FuzzyPriorityList}、無ければ {@link PrecisePriorityList} で包む。
     * 液体チャンネルの createStack は、バケツ等の液体コンテナとAE2のFluidDummyItemの
     * どちらからでも液体を取り出せる(AE2UELソースで確認済み)。</p>
     *
     * <p>参考元(AE2Things)の DISKCellInventory#updateFilter と同じく、ハンドラ生成時に
     * 1回だけ呼び、投入のたびには作り直さない。</p>
     *
     * @return フィルター未設定なら空のリスト({@code isEmpty() == true})
     */
    public static <T extends IAEStack<T>> IPartitionList<T> createPartitionList(
            IItemHandler config, IStorageChannel<T> channel, boolean fuzzy, FuzzyMode fuzzyMode) {
        IItemList<T> list = channel.createList();
        if (config != null) {
            for (int i = 0; i < config.getSlots(); i++) {
                ItemStack is = config.getStackInSlot(i);
                if (!is.isEmpty()) {
                    T stack = channel.createStack(is);
                    if (stack != null) {
                        list.add(stack);
                    }
                }
            }
        }
        return fuzzy ? new FuzzyPriorityList<>(list, fuzzyMode) : new PrecisePriorityList<>(list);
    }
}
