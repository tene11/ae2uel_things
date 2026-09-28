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

    /**
     * 最後のpersist()以降に中身の変更が無ければtrue(参考元 DISKCellInventory#isPersisted と同じ)。
     * AE2UELのME Driveはスロットへアクセスするたびに persist() を呼ぶため、変更が無い場合は
     * 何もしないようにして無駄なNBT書き込みを避ける。また、データを読み込めなかったDISK
     * (中身が空に見える)のUUIDが、触っただけでセルから消されてしまうのも防ぐ。
     */
    private boolean persisted = true;

    /**
     * セルのDiskUUIDタグが不正、またはそのUUIDのデータが読み込みに失敗している場合はtrue。
     * 中身があるものとして扱い、投入・分解を拒否してUUIDタグも書き換えない(データ保護のため)。
     */
    private boolean locked;

    /** セルのDiskUUIDタグから読んだUUID(タグが無い・不正ならnull)。最初の投入時はこれを引き継ぐ。 */
    private UUID cellUuid;

    /**
     * UUIDはあるが、マネージャーにそのデータが無いセルか(別ワールドへ持ち込んだ・.datを巻き戻した等)。
     * UUIDは外さず、分解もさせない。投入は同じUUIDのまま行える(1回入れて空にすれば通常の空セルに戻る)。
     */
    private boolean missing;

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
        DiskStorageManager manager = DiskStorageManager.getCached();

        // 修正メモ(データファイル破損時の保護): DISKデータファイル全体の読み込みに失敗している間は、
        // UUIDの有無にかかわらず全セルをロックする(投入しても保存されず、空に見えるセルを
        // 分解・後片付けするとUUIDが失われるため)。詳細は DiskStorageManager#refresh 参照。
        if (manager.isLoadFailed()) {
            locked = true;
            return null;
        }

        NBTTagCompound tag = cellItem.getTagCompound();
        if (tag == null || !tag.hasKey(TAG_DISK_UUID)) {
            return null;
        }
        String rawUuid = tag.getString(TAG_DISK_UUID);

        // 修正メモ(不正UUIDでのクラッシュ対策): 以前は UUID.fromString() の例外を捕まえておらず、
        // NBTの破損や手動編集でDiskUUIDが不正な文字列になったセルがME Driveに入ると
        // IllegalArgumentException でサーバーごと落ちていた。セルをロックし(投入・分解不可、
        // UUIDタグは書き換えない)、ログに残すだけにする。
        UUID uuid;
        try {
            uuid = UUID.fromString(rawUuid);
        } catch (IllegalArgumentException e) {
            locked = true;
            if (DiskStorageManager.shouldReport("invalid:" + rawUuid)) {
                ExampleMod.LOGGER.error(
                        "[{}] 液体DISKセルのDiskUUIDタグが不正です (\"{}\")。"
                                + "データ保護のため、このセルをロックします(投入・分解不可)。",
                        Tags.MOD_ID, rawUuid);
            }
            return null;
        }

        // 修正メモ(読み込み失敗DISKの保護): DiskStorageManagerが読み込みに失敗して生NBTのまま
        // 保持しているDISKは、以前は空のセルに見えていたため、スニーク右クリックで分解できてしまい
        // セル側のUUID(=データへの唯一の参照)が失われていた。中身ありとして扱いロックする。
        if (manager.isUnreadableFluidDisk(rawUuid)) {
            locked = true;
            if (DiskStorageManager.shouldReport("unreadable:" + uuid)) {
                ExampleMod.LOGGER.error(
                        "[{}] 液体DISK UUID={} のデータは読み込みに失敗しているため、このセルをロックします"
                                + "(投入・分解不可)。ワールド読み込み時のログを確認してください。",
                        Tags.MOD_ID, uuid);
            }
            return null;
        }

        cellUuid = uuid;

        // 修正メモ(データが見つからないセルの保護): 以前は getOrCreate で空のデータを作り、
        // 「空のDISK」として次のpersist()でセルからUUIDを外していた。そのため、.datの読み込み失敗・
        // 巻き戻し・別ワールドへの持ち込みなど、データが一時的に見えないだけの場合でも
        // セル側のUUID(=データへの唯一の参照)が失われ、復旧できなくなっていた。
        // データが無い場合はUUIDを保持したまま「データ無し」状態にし、分解を禁止する。
        DiskFluidCellStorage existing = manager.getFluidDisk(uuid);
        if (existing == null) {
            if (manager.isLoaded()) {
                missing = true;
                if (DiskStorageManager.shouldReport("missing:" + uuid)) {
                    ExampleMod.LOGGER.warn(
                            "[{}] 液体DISK UUID={} のデータがこのワールドに見つかりません。"
                                    + "UUIDは保持し、分解を禁止します(投入は同じUUIDのまま可能)。",
                            Tags.MOD_ID, uuid);
                }
            }
            return null;
        }
        // ここに来るのは、マネージャーに空のまま残っているDISK(=このセッション中に空になり、
        // まだ後片付けされていないもの)だけ。これはUUIDを外してよい。
        if (existing.isEmpty() && manager.isLoaded()) {
            // 修正メモ(空のUUID残留対策): 空になった後、persist()が呼ばれる前にハンドラが
            // 作り直されると、UUIDだけがセルに残る(マネージャーは空のDISKを保存しないため、
            // 次回起動時は「見つからない」状態になる)。以前はここで毎回WARNを出すだけで、
            // persisted=trueの初期値のためUUIDが二度と消えなかった。読み込み失敗分は上で
            // 除外済みなので、未保存扱いにして次のpersist()で空のセルとして後片付けさせる。
            // (ワールド読み込み前やクライアント側のダミーマネージャーでは何もしない)
            persisted = false;
            ExampleMod.LOGGER.debug("[{}] 液体DISK UUID={} は空のため、次の保存時にセルからUUIDを外します",
                    Tags.MOD_ID, uuid);
        }
        // 移行処理: 要約NBT("ic")が無い旧セルは、実データがあれば補う
        // (クライアント側のダミーマネージャーでは中身が空なので書き込まれない)。
        // 読み込み時にエントリが削除されて実データと食い違っている場合も補正する
        // (AE2UELが読み込み直後に "ic" を再計算して書き直すのと同じ)。
        if (!existing.isEmpty() && tag.getLong(DiskCellInventoryHandler.TAG_ITEM_COUNT) != existing.getStoredItemCount()) {
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
        // データが見つからないセル等、UUIDが既にある場合はそれを引き継ぐ(参照を上書きしない)
        UUID uuid = cellUuid != null ? cellUuid : UUID.randomUUID();
        tag.setString(TAG_DISK_UUID, uuid.toString());
        cellUuid = uuid;
        missing = false;
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
        persisted = false;
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
        if (locked) {
            return 0;
        }
        return Math.max(0, getTotalMb() - getStoredMb());
    }

    // ------------------------------------------------------------------
    // ICellInventoryHandler<IAEFluidStack>
    // ------------------------------------------------------------------

    @Override
    public IAEFluidStack injectItems(IAEFluidStack input, Actionable mode, IActionSource src) {
        if (input == null || input.getStackSize() <= 0) return input;

        // ロック中(不正UUID・読み込み失敗)のセルには何も入れない。入れると新しいUUIDが
        // 採番され、元のデータへの参照が上書きされてしまうため。
        if (locked) {
            return input;
        }

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
        return !locked
                && partitionMode == IncludeExclude.WHITELIST
                && !partitionList.isEmpty()
                && partitionList.isListed(input);
    }

    /** AE2UEL標準セルと同じく、フィルターを通らないものは受け入れ候補から外す。 */
    @Override
    public boolean canAccept(IAEFluidStack input) {
        return !locked && passesFilter(input);
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
        if (locked) {
            return 0;
        }
        return Math.max(0, usableBytes - getUsedBytes());
    }

    /**
     * AE2ネットワークに公開する「使用byte数」= 合計mBのbyte換算(タイプごとの消費なし)。
     * 実データは生のmBで持っているため、ここで /1000(切り上げ)して byte換算する。
     * 切り上げにしているのは、端数mBがあるのに使用量が0byteと過小報告されて
     * 容量オーバーの温床になるのを防ぐため。
     */
    @Override
    public long getUsedBytes() {
        long mb = getStoredMb();
        if (mb <= 0) return 0;
        return (mb + MB_PER_BYTE - 1) / MB_PER_BYTE;
    }

    @Override
    public long getTotalItemTypes() {
        return Integer.MAX_VALUE;
    }

    /**
     * 修正メモ(単位の不一致): 以前はここでbyte換算した値を返していたが、AE2UEL標準の液体セル
     * (AbstractCellInventory)と同じく、「個数」系のメソッドは液体の実量(mB)、
     * 「byte」系のメソッドはbyteで返すように揃えた。
     */
    @Override
    public long getStoredItemCount() {
        return getStoredMb();
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

    /** 残りの格納可能量(mB)。 */
    @Override
    public long getRemainingItemCount() {
        return getFreeMb();
    }

    /**
     * 使用中の最後の1byteのうち、まだ使っていないmB(AE2UEL標準セルと同じ意味)。
     * getFreeBytes() × 1000 + この値 = getRemainingItemCount() になる。
     */
    @Override
    public int getUnusedItemCount() {
        if (locked) {
            return 0;
        }
        long unused = getUsedBytes() * MB_PER_BYTE - getStoredMb();
        return (int) Math.max(0, Math.min(unused, MB_PER_BYTE - 1));
    }

    /** 4=空、1=空きあり、3=満杯(タイプ数上限が無いため「タイプ満杯」の2は使わない)。 */
    @Override
    public int getStatusForCell() {
        // ロック中は満杯(赤)表示にして、異常があることがドライブ上で分かるようにする
        if (locked) return 3;
        if (getStoredMb() == 0) return 4;
        if (canHoldNewItem()) return 1;
        return 3;
    }

    @Override
    public void persist() {
        if (persisted || storage == null) {
            return;
        }
        persisted = true;
        if (storage.isEmpty()) {
            // 空になったらマネージャー側の参照とItemStack側のUUIDを両方消す
            DiskStorageManager.getCached().removeFluidDisk(storage.getUUID());
            NBTTagCompound tag = cellItem.getTagCompound();
            if (tag != null) {
                tag.removeTag(TAG_DISK_UUID);
            }
            DiskCellInventoryHandler.clearSummary(cellItem);
            storage = null;
            cellUuid = null;
        } else {
            DiskStorageManager.getCached().updateFluidDisk(storage);
            // ツールチップ用の要約値。合計量は生のmBのまま保存する(表示側でbyte換算)
            DiskCellInventoryHandler.writeSummary(cellItem, storage.getStoredItemCount());
        }
    }

    /** ロック中・データ無しのセルは中身ありとして扱う(分解させないため)。 */
    public boolean isEmpty() {
        return !locked && !missing && (storage == null || storage.isEmpty());
    }

    /** UUIDはあるが、このワールドにそのデータが無いセルか(分解禁止)。 */
    public boolean isMissing() {
        return missing;
    }

    /** DiskUUIDタグが不正、またはデータの読み込みに失敗していてロックされているか。 */
    public boolean isLocked() {
        return locked;
    }
}
