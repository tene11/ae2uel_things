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
import com.example.ae2uelthings.ExampleMod;
import com.example.ae2uelthings.Tags;
import com.example.ae2uelthings.api.IDiskFluidCellDefinition;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.items.IItemHandler;
import com.example.ae2uelthings.disk.DiskConfig;
import com.example.ae2uelthings.disk.DiskUpgrades;



import java.util.UUID;

/**
 * DISK(フルイド版)セル1個ぶんの、AE2ネットワークから見た「取引窓口」。
 *
 * 修正メモ1: 旧実装は DiskFluidCellStorage.load(cellItem)/saveTo(cellItem) で
 * cellItemのNBTに直接読み書きしていた(=アイテム版で最初に問題視したNBT爆弾と
 * 同じパターン)。アイテム版のDiskCellInventoryHandlerと同じUUID外部化方式に揃え、
 * ItemStackのNBTにはUUID文字列(キー: "DiskUUID")だけを持たせる形に変更した。
 * ICellInventoryHandler<T>/ICellInventory<T>のメソッド一覧・実装方針(getCellInv()はthisを返す等)
 * は旧実装(実際にコンパイル・動作確認済み)のものをそのまま踏襲している。
 *
 * 修正メモ2(他アドオン向け拡張性): getFuzzyMode/getConfigInventory/getUpgradesInventoryは
 * appeng.api.storage.ICellWorkbenchItem 経由で、getIdleDrainは
 * {@link com.example.ae2uelthings.api.IDiskFluidCellDefinition} 経由でセルアイテム側に委譲する形に
 * した。特定クラス(ItemDiskFluidCell)への直接依存ではないため、他アドオンが同じ
 * インターフェースを実装した独自アイテムを作れば、そのまま動作する。
 *
 * 修正メモ3(容量モデル変更): 以前は「1mB=1byte」のシンプルな1:1モデルだったが、
 * AE2MEGAThingsが採用している「1byte=1000mB」に合わせて変更した。
 * 実データ({@link DiskFluidCellStorage}側)は変わらず生のmBを保持したままで、
 * AE2ネットワークに公開する「使用byte数」の計算(このクラス内)だけがmB/1000を返す形になる。
 * 例: 1kティア(usableBytes=1000)の実際の格納可能量は 1000 × 1000 = 1,000,000mB(バケツ1000個分)。
 */
public class DiskFluidCellInventoryHandler implements ICellInventoryHandler<IAEFluidStack>, ICellInventory<IAEFluidStack> {

    private static final String TAG_DISK_UUID = "DiskUUID";

    /** AE2ネットワークに公開する1byteあたりの実容量(mB)。AE2MEGAThingsに合わせて1000。 */
    public static final int MB_PER_BYTE = 1000;

    private final ItemStack cellItem;
    private final long usableBytes;
    private final int bytesPerType;
    private final ISaveProvider container;

    /** UUID未採番(=まだ何も挿入されたことがない)の場合はnull */
    private DiskFluidCellStorage storage;

    public DiskFluidCellInventoryHandler(ItemStack cellItem, long usableBytes, int bytesPerType, ISaveProvider container) {
        this.cellItem = cellItem;
        this.usableBytes = usableBytes;
        this.bytesPerType = bytesPerType;
        this.container = container;
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
        return DiskStorageManager.getCached().getOrCreateFluidDisk(uuid);
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
        if (container != null) {
            container.saveChanges(this);
        } else {
            persist();
        }
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

    /**
     * 修正メモ: 以前はここが getStoredMb() のみで、格納済みタイプ数分の
     * bytesPerTypeオーバーヘッド(mB換算)が一切反映されていなかった。injectItems()の
     * 新規タイプ受け入れ判定では一時的に差し引いていたが、その場限りで永続化されておらず、
     * 既存タイプへの追加投入で実質的にそのコストを取り戻せてしまうバグだった(アイテム版と同種)。
     * getStoredItemTypes() * bytesPerType * MB_PER_BYTE を加えることで、実際に消費している
     * mB数を正しく返すようにした。
     */
    private long getUsedMb() {
        return getStoredMb() + getStoredItemTypes() * (long) bytesPerType * MB_PER_BYTE;
    }

    /** 残りmB容量 */
    private long getFreeMb() {
        return Math.max(0, getTotalMb() - getUsedMb());
    }

    // ------------------------------------------------------------------
    // ICellInventoryHandler<IAEFluidStack>
    // ------------------------------------------------------------------

    @Override
    public IAEFluidStack injectItems(IAEFluidStack input, Actionable mode, IActionSource src) {
        if (input == null || input.getStackSize() <= 0) return input;

        IItemList<IAEFluidStack> fluidsList = storage != null ? storage.getFluids() : null;
        IAEFluidStack existing = fluidsList != null ? fluidsList.findPrecise(input) : null;

        // フィルター判定は「本当に初めて見るタイプ」(existing == null)のときだけ行う。
        // 既存タイプ(0個に減っているだけの残留エントリを含む)への追加投入は、
        // 後からフィルター設定を変更しても既存分には遡及しない(AE2標準セルと同じ仕様)。
        if (existing == null && !isAcceptedByFilter(input.getFluidStack())) {
            return input;
        }

        // バグ修正: 以前は existing != null なら常に「既存タイプへの追加」として扱い、
        // bytesPerType分の予約なしでfreeMbいっぱいまで受け入れていた。
        // しかしextractItems()は0個になったエントリをリストから削除しない(意図的な仕様)ため、
        // 「過去に全量抽出して0個のまま残っているタイプ」への再投入もfindPreciseに
        // 引っかかり「既存」扱いになってしまい、bytesPerTypeの予約が漏れていた。
        // このエントリは実際にはgetStoredItemTypes()でカウントされていない(0個は除外)ため、
        // 再投入した瞬間にタイプ数が+1され、空き容量ギリギリまで投入すると
        // 使用量がusableBytesをbytesPerType分(mB換算で最大1000mB=バケツ1個分)超えてしまっていた。
        // existing.getStackSize() <= 0 の場合も「新規タイプ」と同じ扱いにして修正する。
        boolean addsNewType = existing == null || existing.getStackSize() <= 0;

        long freeMb = getFreeMb();
        long acceptableMb = addsNewType ? freeMb - (long) bytesPerType * MB_PER_BYTE : freeMb;
        long toAccept = Math.min(input.getStackSize(), Math.max(0, acceptableMb));
        if (toAccept <= 0) return input;

        if (mode == Actionable.MODULATE) {
            if (existing != null) {
                existing.incStackSize(toAccept);
            } else {
                IAEFluidStack toStore = input.copy();
                toStore.setStackSize(toAccept);
                getOrCreateStorage().getFluids().add(toStore);
            }
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
            existing.decStackSize(size);
            // アイテム版と同じ理由でIItemList<IAEFluidStack>にもremove(T)は無い想定のため、
            // 0個のエントリはそのまま残す(getAvailableItems/isEmpty/getStoredItemTypes側でフィルタ済み)。
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

    @Override
    public boolean isPrioritized(IAEFluidStack input) {
        return false;
    }

    @Override
    public boolean canAccept(IAEFluidStack input) {
        return true;
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public int getSlot() {
        return 0;
    }

    @Override
    public boolean validForPass(int pass) {
        return pass == 1;
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
        // configに1つでもフィルターアイテム(流体コンテナ)が設定されていればtrue
        return DiskConfig.hasAnyFilter(getConfigInventory());
    }

    /**
     * 新規タイプの受け入れをタイプフィルターで判定する(item版DiskCellInventoryHandlerと同じ考え方)。
     * 未設定(isPreformatted()==false)なら常にtrue(従来通り無制限)。
     */
    private boolean isAcceptedByFilter(net.minecraftforge.fluids.FluidStack candidate) {
        if (!isPreformatted()) {
            return true;
        }
        boolean matches = DiskConfig.matchesFluid(getConfigInventory(), candidate);
        return getIncludeExcludeMode() == IncludeExclude.WHITELIST ? matches : !matches;
    }

    @Override
    public boolean isFuzzy() {
        // fluid版DISKはFuzzy Card非対応(参考元と同じ。フルイドにはダメージ値のような
        // "あいまい一致"対象が無いため、そもそもFuzzyスロット自体を持たない)
        return false;
    }

    @Override
    public IncludeExclude getIncludeExcludeMode() {
        // Inverter Cardが挿さっていればBLACKLISTへ反転(参考元と同じ仕様)。
        // タイプフィルター(DiskConfig)を実装したことで、isAcceptedByFilter()経由で
        // 実際にWHITELIST/BLACKLISTの挙動が反映されるようになっている。
        return DiskUpgrades.hasInverterCard(getUpgradesInventory()) ? IncludeExclude.BLACKLIST : IncludeExclude.WHITELIST;
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

    @Override
    public int getBytesPerType() {
        return bytesPerType;
    }

    @Override
    public boolean canHoldNewItem() {
        return getFreeBytes() > bytesPerType;
    }

    @Override
    public long getTotalBytes() {
        return usableBytes;
    }

    @Override
    public long getFreeBytes() {
        return Math.max(0, usableBytes - getUsedBytes());
    }

    /**
     * 修正メモ: 以前は getStoredItemCount() のみ(mB→byte換算後の量)を返しており、
     * getStoredMb()と同じ理由でタイプ数コストが反映されていなかった。
     * getStoredItemTypes() * bytesPerType (byte単位、mB換算不要)を加える。
     */
    @Override
    public long getUsedBytes() {
        return getStoredItemCount() + getStoredItemTypes() * (long) bytesPerType;
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

    @Override
    public long getRemainingItemTypes() {
        return getFreeBytes() / Math.max(1, bytesPerType);
    }

    @Override
    public long getRemainingItemCount() {
        return getFreeBytes();
    }

    @Override
    public int getUnusedItemCount() {
        return 0;
    }

    @Override
    public int getStatusForCell() {
        if (getUsedBytes() == 0) return 4;
        if (canHoldNewItem()) return 1;
        if (getRemainingItemCount() > 0) return 2;
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
            storage = null;
        } else {
            DiskStorageManager.getCached().updateFluidDisk(storage);
        }
    }

    public boolean isEmpty() {
        return storage == null || storage.isEmpty();
    }
}