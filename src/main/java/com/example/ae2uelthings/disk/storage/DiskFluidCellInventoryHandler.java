package com.example.ae2uelthings.disk.storage;

import appeng.api.AEApi;
import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.config.IncludeExclude;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ICellWorkbenchItem;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IItemList;
import appeng.util.prioritylist.IPartitionList;
import com.example.ae2uelthings.ExampleMod;
import com.example.ae2uelthings.Tags;
import com.example.ae2uelthings.api.IDiskFluidCellDefinition;
import com.example.ae2uelthings.disk.DiskConfig;
import com.example.ae2uelthings.disk.DiskUpgrades;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.items.IItemHandler;

import java.util.UUID;

/**
 * DISK(フルイド版)セル1個ぶんの、AE2ネットワークから見た「取引窓口」。
 *
 * アイテム版 {@link DiskCellInventoryHandler} と同じく、ItemStackのNBTにはUUID文字列
 * (キー: "DiskUUID")とツールチップ用の要約値だけを持たせ、実データは
 * {@link DiskStorageManager} 側の {@link DiskFluidCellStorage} に集約する。
 * getFuzzyMode/getConfigInventory/getUpgradesInventory は ICellWorkbenchItem 経由、
 * getIdleDrain は {@link IDiskFluidCellDefinition} 経由でセルアイテム側に委譲している。
 *
 * <p><b>容量モデル:</b> AE2MEGAThingsに合わせて「1byte = 1000mB」。実データは生のmBで保持し、
 * AE2ネットワークに公開する使用byte数だけを mB/1000(切り上げ)で換算する。
 * 例: 1kティア(usableBytes=1000)の格納可能量は 1000 × 1000 = 1,000,000mB(バケツ1000個分)。
 * 参考元に合わせ、タイプごとのbyte消費(bytesPerType)は持たない。</p>
 *
 * <p><b>タイプフィルター:</b> アイテム版と同じく、セル生成時にAE2UEL標準の
 * PrecisePriorityList で構築し、既存タイプへの追加投入も含めて毎回適用する。
 * フィルタースロットのアイテムはAE2UELの液体チャンネル(createStack)で液体に変換されるため、
 * バケツ等の液体コンテナ・AE2のFluidDummyItemのどちらでも設定できる。
 * 液体にはあいまい一致の対象が無いため、Fuzzy Cardには対応しない(参考元と同じ)。</p>
 */
public class DiskFluidCellInventoryHandler implements ICellInventoryHandler<IAEFluidStack>, ICellInventory<IAEFluidStack> {

    private static final String TAG_DISK_UUID = "DiskUUID";

    /** AE2ネットワークに公開する1byteあたりの実容量(mB)。AE2MEGAThingsに合わせて1000。 */
    public static final int MB_PER_BYTE = 1000;

    private final ItemStack cellItem;
    private final long usableBytes;
    private final ISaveProvider container;

    /** タイプフィルター(空なら制限なし)。セル生成時に構築する。 */
    private final IPartitionList<IAEFluidStack> partitionList;
    /** Inverter Cardが挿さっていればBLACKLIST、それ以外はWHITELIST */
    private final IncludeExclude partitionMode;

    /** UUID未採番(=まだ何も挿入されたことがない)の場合はnull */
    private DiskFluidCellStorage storage;

    public DiskFluidCellInventoryHandler(ItemStack cellItem, long usableBytes, ISaveProvider container) {
        this.cellItem = cellItem;
        this.usableBytes = usableBytes;
        this.container = container;

        IItemHandler upgrades = getUpgradesInventory();
        this.partitionMode = DiskUpgrades.hasInverterCard(upgrades) ? IncludeExclude.BLACKLIST : IncludeExclude.WHITELIST;
        this.partitionList = DiskConfig.createPartitionList(getConfigInventory(), getChannel(), false, getFuzzyMode());

        this.storage = loadExisting();
    }

    // ------------------------------------------------------------------
    // UUID <-> ItemStack NBT / DiskStorageManager 連携
    // ------------------------------------------------------------------

    private DiskFluidCellStorage loadExisting() {
        NBTTagCompound tag = cellItem.getTagCompound();
        if (tag == null || !tag.hasKey(TAG_DISK_UUID)) {
            return null;
        }
        UUID uuid = UUID.fromString(tag.getString(TAG_DISK_UUID));
        if (!DiskStorageManager.getCached().hasFluidDisk(uuid)) {
            ExampleMod.LOGGER.warn(
                    "[{}] 液体DISK UUID={} はセルに記録されているが、DiskStorageManagerに見つからない"
                            + " (空データとして扱う。DiskStorageEventHandlerがイベントバスに登録されているか確認すること)",
                    Tags.MOD_ID, uuid);
        }
        DiskFluidCellStorage existing = DiskStorageManager.getCached().getOrCreateFluidDisk(uuid);
        // 移行処理: 要約NBT("ic"=合計mB)が無い旧セルは、実データがあれば補う
        if (!tag.hasKey(DiskCellInventoryHandler.TAG_ITEM_COUNT) && !existing.isEmpty()) {
            DiskCellInventoryHandler.writeSummary(cellItem, existing.getStoredItemCount());
        }
        return existing;
    }

    private DiskFluidCellStorage getOrCreateStorage() {
        if (storage != null) {
            return storage;
        }
        NBTTagCompound tag = cellItem.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            cellItem.setTagCompound(tag);
        }
        UUID uuid = UUID.randomUUID();
        tag.setString(TAG_DISK_UUID, uuid.toString());
        storage = DiskStorageManager.getCached().getOrCreateFluidDisk(uuid);
        return storage;
    }

    private void markDirty() {
        // 修正メモ(保存漏れ対策): AE2UELのME Drive(TileDrive#saveChanges)はチャンクをdirtyに
        // するだけでpersist()を呼ばず、persist()はドライブのスロットに次にアクセスした時
        // (AppEngCellInventory)まで遅延される。バニラはワールド保存時にWorldSavedData
        // (DiskStorageManager)をチャンクより先に書き出すため、persist()でしかmarkDirty()
        // しないと、直前の変更がマネージャーの保存対象にならず取りこぼす恐れがあった。
        // 実データ(DiskFluidCellStorage)はマネージャー内の同じインスタンスを直接書き換えているので、
        // 変更の都度マネージャーをdirtyにしておけば、次の保存で確実に書き出される。
        DiskStorageManager.getCached().markDirty();
        if (container != null) {
            container.saveChanges(this);
        } else {
            persist();
        }
    }

    // ------------------------------------------------------------------
    // タイプフィルター
    // ------------------------------------------------------------------

    /** タイプフィルターを通過するか(アイテム版と同じ判定)。フィルター未設定なら常にtrue。 */
    private boolean passesFilter(IAEFluidStack input) {
        if (partitionList.isEmpty()) {
            return true;
        }
        boolean listed = partitionList.isListed(input);
        return partitionMode == IncludeExclude.WHITELIST ? listed : !listed;
    }

    // ------------------------------------------------------------------
    // mB <-> byte 換算ヘルパー
    // ------------------------------------------------------------------

    /** 実際に格納されている生のmB合計 (DiskFluidCellStorage側は換算前の実数をそのまま持つ) */
    private long getStoredMb() {
        return storage == null ? 0 : storage.getStoredItemCount();
    }

    /** このセルが実際に格納できる上限mB (byte容量 × 1000) */
    private long getTotalMb() {
        return usableBytes * (long) MB_PER_BYTE;
    }

    /** 残りmB容量(参考元と同じく、タイプごとの消費は無い) */
    private long getFreeMb() {
        return Math.max(0, getTotalMb() - getStoredMb());
    }

    // ------------------------------------------------------------------
    // ICellInventoryHandler<IAEFluidStack>
    // ------------------------------------------------------------------

    @Override
    public IAEFluidStack injectItems(IAEFluidStack input, Actionable mode, IActionSource src) {
        if (input == null || input.getStackSize() <= 0) return input;

        // 参考元と同じく、フィルターは既存タイプへの追加投入も含めて毎回適用する
        if (!passesFilter(input)) {
            return input;
        }

        long toAccept = Math.min(input.getStackSize(), getFreeMb());
        if (toAccept <= 0) return input;

        if (mode == Actionable.MODULATE) {
            // 個数・タイプ数のキャッシュを正しく保つため、必ずストレージ側のinsert()経由で増やす
            getOrCreateStorage().insert(input, toAccept);
            markDirty();
        }
        if (toAccept >= input.getStackSize()) return null;
        IAEFluidStack remainder = input.copy();
        remainder.decStackSize(toAccept);
        return remainder;
    }

    @Override
    public IAEFluidStack extractItems(IAEFluidStack request, Actionable mode, IActionSource src) {
        if (request == null || storage == null) return null;
        IAEFluidStack existing = storage.getFluids().findPrecise(request);
        if (existing == null) return null;

        long size = Math.min(request.getStackSize(), existing.getStackSize());
        if (size <= 0) return null;

        IAEFluidStack result = existing.copy();
        result.setStackSize(size);

        if (mode == Actionable.MODULATE) {
            // キャッシュを保つため、ストレージ側のextract()経由で減らす
            storage.extract(existing, size);
            // アイテム版と同じく、0個のエントリはここでは残し、次回の走査時に
            // AE2UELのItemList側(MeaningfulItemIterator)が自動で取り除く。
            markDirty();
        }
        return result;
    }

    @Override
    public IItemList<IAEFluidStack> getAvailableItems(IItemList<IAEFluidStack> out) {
        if (storage != null) {
            for (IAEFluidStack stack : storage.getFluids()) {
                if (stack.getStackSize() > 0) {
                    out.add(stack);
                }
            }
        }
        return out;
    }

    @Override
    public IStorageChannel<IAEFluidStack> getChannel() {
        return AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class);
    }

    @Override
    public AccessRestriction getAccess() {
        return AccessRestriction.READ_WRITE;
    }

    /** アイテム版と同じく、WHITELISTかつフィルターに一致する液体はネットワーク投入時に優先させる。 */
    @Override
    public boolean isPrioritized(IAEFluidStack input) {
        return partitionMode == IncludeExclude.WHITELIST
                && !partitionList.isEmpty()
                && partitionList.isListed(input);
    }

    /** AE2UEL標準セルと同じく、フィルターを通らないものは受け入れ候補から外す。 */
    @Override
    public boolean canAccept(IAEFluidStack input) {
        return passesFilter(input);
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public int getSlot() {
        return 0;
    }

    /**
     * 以前は {@code pass == 1} のみを返していたが、AE2UELのソースで確認したところ、
     * ME Drive(DriveWatcher)/ME Chest はこのハンドラを MEInventoryHandler で包んでおり、
     * そちらの validForPass() は常に true を返す(内側へ委譲しない)ため、実害は無かった。
     * AE2標準セル(MEPassThrough)と挙動を揃え、包まれずに直接使われた場合にも
     * pass 2 (新規タイプの投入)で候補から外れないよう、両方のpassで有効とする。
     */
    @Override
    public boolean validForPass(int pass) {
        return true;
    }

    @Override
    public ICellInventory<IAEFluidStack> getCellInv() {
        return this;
    }

    // ------------------------------------------------------------------
    // ICellInventory<IAEFluidStack>
    // ------------------------------------------------------------------

    @Override
    public boolean isPreformatted() {
        return !partitionList.isEmpty();
    }

    @Override
    public boolean isFuzzy() {
        // 液体版DISKはFuzzy Card非対応(参考元と同じ)
        return false;
    }

    @Override
    public IncludeExclude getIncludeExcludeMode() {
        return partitionMode;
    }

    @Override
    public ItemStack getItemStack() {
        return cellItem;
    }

    @Override
    public double getIdleDrain() {
        return ((IDiskFluidCellDefinition) cellItem.getItem()).getIdleDrain(cellItem);
    }

    @Override
    public FuzzyMode getFuzzyMode() {
        return ((ICellWorkbenchItem) cellItem.getItem()).getFuzzyMode(cellItem);
    }

    @Override
    public IItemHandler getConfigInventory() {
        return ((ICellWorkbenchItem) cellItem.getItem()).getConfigInventory(cellItem);
    }

    @Override
    public IItemHandler getUpgradesInventory() {
        return ((ICellWorkbenchItem) cellItem.getItem()).getUpgradesInventory(cellItem);
    }

    /** 参考元と同じく、タイプごとのbyte消費は無い。 */
    @Override
    public int getBytesPerType() {
        return 0;
    }

    /** byte換算は切り上げのため、mB単位で空きがあるかで判定する(端数mBでも受け入れ可能なため)。 */
    @Override
    public boolean canHoldNewItem() {
        return getFreeMb() > 0;
    }

    @Override
    public long getTotalBytes() {
        return usableBytes;
    }

    @Override
    public long getFreeBytes() {
        return Math.max(0, usableBytes - getUsedBytes());
    }

    /** 使用byte数 = 合計mBのbyte換算(タイプごとの消費なし)。 */
    @Override
    public long getUsedBytes() {
        return getStoredItemCount();
    }

    @Override
    public long getTotalItemTypes() {
        return Integer.MAX_VALUE;
    }

    /**
     * AE2ネットワークに公開する「使用byte数」。実データは生のmBで持っているため、
     * ここで /1000(切り上げ)して byte換算する。切り上げにしているのは、
     * 端数mBがあるのに使用量が0byteと過小報告されて容量オーバーの温床になるのを防ぐため。
     */
    @Override
    public long getStoredItemCount() {
        long mb = getStoredMb();
        if (mb <= 0) return 0;
        return (mb + MB_PER_BYTE - 1) / MB_PER_BYTE;
    }

    @Override
    public long getStoredItemTypes() {
        return storage == null ? 0 : storage.getStoredItemTypes();
    }

    /** 新しいタイプは最低1mBを使うため、空きmBがそのまま追加可能なタイプ数の上限になる。 */
    @Override
    public long getRemainingItemTypes() {
        return getFreeMb();
    }

    @Override
    public long getRemainingItemCount() {
        return getFreeBytes();
    }

    @Override
    public int getUnusedItemCount() {
        return 0;
    }

    /** 4=空、1=空きあり、3=満杯(タイプ数上限が無いため「タイプ満杯」の2は使わない)。 */
    @Override
    public int getStatusForCell() {
        if (getStoredMb() == 0) return 4;
        if (canHoldNewItem()) return 1;
        return 3;
    }

    @Override
    public void persist() {
        if (storage == null) {
            return;
        }
        if (storage.isEmpty()) {
            // 空になったらマネージャー側の参照とItemStack側のUUIDを両方消す
            DiskStorageManager.getCached().removeFluidDisk(storage.getUUID());
            NBTTagCompound tag = cellItem.getTagCompound();
            if (tag != null) {
                tag.removeTag(TAG_DISK_UUID);
            }
            DiskCellInventoryHandler.clearSummary(cellItem);
            storage = null;
        } else {
            DiskStorageManager.getCached().updateFluidDisk(storage);
            // ツールチップ用の要約値。合計量は生のmBのまま保存する(表示側でbyte換算)
            DiskCellInventoryHandler.writeSummary(cellItem, storage.getStoredItemCount());
        }
    }

    public boolean isEmpty() {
        return storage == null || storage.isEmpty();
    }
}
