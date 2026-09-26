package com.example.ae2uelthings.disk.storage;

import appeng.api.AEApi;
import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.config.IncludeExclude;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ICellRegistry;
import appeng.api.storage.ICellWorkbenchItem;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.util.prioritylist.FuzzyPriorityList;
import appeng.util.prioritylist.IPartitionList;
import com.example.ae2uelthings.ExampleMod;
import com.example.ae2uelthings.Tags;
import com.example.ae2uelthings.api.IDiskCellDefinition;
import com.example.ae2uelthings.disk.DiskConfig;
import com.example.ae2uelthings.disk.DiskUpgrades;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.items.IItemHandler;

import java.util.UUID;

/**
 * DISKセル1個ぶんの、AE2ネットワークから見た「取引窓口」。
 *
 * ICellInventoryHandler<T> と ICellInventory<T> を同一クラスで実装し、getCellInv()は
 * this を返す構成にしている(液体版 {@link DiskFluidCellInventoryHandler} も同じ構成)。
 * 中身はItemStackのNBTに直接書き込まず、UUID文字列(キー: "DiskUUID")だけをNBTに持たせ、
 * 実データは {@link DiskStorageManager} 側の {@link DiskCellStorage} に集約する。
 * ME Drive/端末でのGUI表示時に発生するインベントリ同期パケットへ重いNBTが
 * 乗るのを避けるのが目的 (AE2Things本家の設計を踏襲)。
 *
 * <p><b>容量モデル(参考元AE2Thingsに合わせた):</b> 1アイテム = 1byte。タイプごとの
 * byte消費(bytesPerType)は持たず、空き容量は「総byte数 − 合計個数」だけで決まる
 * (参考元 DISKCellInventory#getFreeBytes と同じ)。タイプ数は無制限。</p>
 *
 * <p><b>タイプフィルター(参考元に合わせた):</b> config/upgradesからセル生成時に1回だけ
 * パーティションリストを構築する(参考元 DISKCellInventory#updateFilter と同じ)。
 * 判定にはAE2UEL標準セル(BasicCellInventoryHandler)と同じ PrecisePriorityList /
 * FuzzyPriorityList を使うため、Fuzzy Card挿入時はあいまい一致になる。
 * フィルターは既存タイプへの追加投入も含めて毎回適用される(参考元と同じ)。
 * WHITELISTでフィルターに一致するアイテムは {@link #isPrioritized} がtrueとなり、
 * AE2標準の分割セルと同様にネットワーク投入時に優先される。</p>
 *
 * getFuzzyMode/getConfigInventory/getUpgradesInventory は
 * appeng.api.storage.ICellWorkbenchItem 経由で、getIdleDrainは
 * {@link com.example.ae2uelthings.api.IDiskCellDefinition} 経由でセルアイテム側に委譲している。
 * どちらも特定クラス(ItemDiskCell)への直接依存ではなくインターフェース経由なので、
 * 他アドオンが同じインターフェースを実装した独自アイテムを作れば、そのまま動作する。
 */
public class DiskCellInventoryHandler implements ICellInventoryHandler<IAEItemStack>, ICellInventory<IAEItemStack> {

    public static final String TAG_DISK_UUID = "DiskUUID";

    /**
     * ツールチップ表示用にセル自身のNBTへ書き込む要約値: 合計個数(液体版は合計mB)。
     * 参考元(AE2Things)の DISKCellInventory.ITEM_COUNT_TAG ("ic") に相当。
     * クライアントはサーバー側の {@link DiskStorageManager} を読めないため、この値だけで表示する。
     */
    public static final String TAG_ITEM_COUNT = "ic";

    private final ItemStack cellItem;
    private final long usableBytes;
    private final ISaveProvider container;

    /** タイプフィルター(空なら制限なし)。セル生成時に構築する。 */
    private final IPartitionList<IAEItemStack> partitionList;
    /** Inverter Cardが挿さっていればBLACKLIST、それ以外はWHITELIST */
    private final IncludeExclude partitionMode;

    /** UUID未採番(=まだ何も挿入されたことがない)の場合はnull */
    private DiskCellStorage storage;

    public DiskCellInventoryHandler(ItemStack cellItem, long usableBytes, ISaveProvider container) {
        this.cellItem = cellItem;
        this.usableBytes = usableBytes;
        this.container = container;

        IItemHandler upgrades = getUpgradesInventory();
        this.partitionMode = DiskUpgrades.hasInverterCard(upgrades) ? IncludeExclude.BLACKLIST : IncludeExclude.WHITELIST;
        this.partitionList = DiskConfig.createPartitionList(
                getConfigInventory(), getChannel(), DiskUpgrades.hasFuzzyCard(upgrades), getFuzzyMode());

        this.storage = loadExisting();
    }

    // ------------------------------------------------------------------
    // UUID <-> ItemStack NBT / DiskStorageManager 連携
    // ------------------------------------------------------------------

    private DiskCellStorage loadExisting() {
        NBTTagCompound tag = cellItem.getTagCompound();
        if (tag == null || !tag.hasKey(TAG_DISK_UUID)) {
            return null;
        }
        UUID uuid = UUID.fromString(tag.getString(TAG_DISK_UUID));
        if (!DiskStorageManager.getCached().hasDisk(uuid)) {
            // UUIDはItemStack側に記録されているのに、マネージャー側にデータが無い状態。
            // DiskStorageEventHandlerが登録されておらずrefresh()が一度も走っていない、
            // またはロード処理自体に問題がある可能性が高い。
            ExampleMod.LOGGER.warn(
                    "[{}] DISK UUID={} はセルに記録されているが、DiskStorageManagerに見つからない"
                            + " (空データとして扱う。DiskStorageEventHandlerがイベントバスに登録されているか確認すること)",
                    Tags.MOD_ID, uuid);
        }
        DiskCellStorage existing = DiskStorageManager.getCached().getOrCreateDisk(uuid);
        // 移行処理: 要約NBT導入前に作られたセルには "ic" が無いため、実データがあれば補う
        // (クライアント側のダミーマネージャーでは中身が空なので書き込まれない)。
        if (!tag.hasKey(TAG_ITEM_COUNT) && !existing.isEmpty()) {
            writeSummary(cellItem, existing.getStoredItemCount());
        }
        return existing;
    }

    /** ツールチップ用の要約値(合計個数、液体版は合計mB)をセルのNBTへ書き込む。液体版・recoverコマンドからも使う。 */
    public static void writeSummary(ItemStack cell, long count) {
        NBTTagCompound tag = cell.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            cell.setTagCompound(tag);
        }
        tag.setLong(TAG_ITEM_COUNT, count);
    }

    /**
     * 要約値をセルのNBTから取り除く(中身が空になった時用)。UUIDを消した後に呼ぶこと。
     * 結果としてNBTが空になった場合はタグ自体を外し、新品のセルと同じ状態に戻す。
     */
    public static void clearSummary(ItemStack cell) {
        NBTTagCompound tag = cell.getTagCompound();
        if (tag == null) {
            return;
        }
        tag.removeTag(TAG_ITEM_COUNT);
        if (tag.getKeySet().isEmpty()) {
            cell.setTagCompound(null);
        }
    }

    /** 初回挿入時にだけUUIDを新規採番する。空のまま触っただけではUUIDを発行しない。 */
    private DiskCellStorage getOrCreateStorage() {
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
        storage = DiskStorageManager.getCached().getOrCreateDisk(uuid);
        return storage;
    }

    private void markDirty() {
        // 修正メモ(保存漏れ対策): AE2UELのME Drive(TileDrive#saveChanges)はチャンクをdirtyに
        // するだけでpersist()を呼ばず、persist()はドライブのスロットに次にアクセスした時
        // (AppEngCellInventory)まで遅延される。バニラはワールド保存時にWorldSavedData
        // (DiskStorageManager)をチャンクより先に書き出すため、persist()でしかmarkDirty()
        // しないと、直前の変更がマネージャーの保存対象にならず取りこぼす恐れがあった。
        // 実データ(DiskCellStorage)はマネージャー内の同じインスタンスを直接書き換えているので、
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

    /**
     * タイプフィルターを通過するか(AE2UEL MEInventoryHandler#passesBlackOrWhitelist と同じ判定)。
     * フィルター未設定なら常にtrue。WHITELISTは一致するもののみ、BLACKLISTは一致しないもののみ許可。
     */
    private boolean passesFilter(IAEItemStack input) {
        if (partitionList.isEmpty()) {
            return true;
        }
        boolean listed = partitionList.isListed(input);
        return partitionMode == IncludeExclude.WHITELIST ? listed : !listed;
    }

    // ------------------------------------------------------------------
    // 二重格納(セルネスト)防止
    // ------------------------------------------------------------------

    /**
     * 二重格納(セルネスト)防止。
     *
     * <p>参考元(io.github.lapis256.ae2_mega_things)は MixinDISKCellInventory で
     * AE2Things本家の DISKCellInventory#insert に割り込み、挿入対象が「中身の入った
     * ストレージセル」(通常のAE2セル、または別のDISK/MEGA DISK)である場合は常に
     * 拒否している(Utils.isCellNestingPrevented)。空でないストレージセルをそのまま
     * 別のセル/DISKへ格納できてしまうと、内部の中身がAE2ネットワークの集計から
     * 隠れたまま持ち出せてしまう(＝実質的な複製・容量バイパス)ため。</p>
     *
     * <p>ae2uelthings(AE2 rv6 / 1.12.2)には mixin基盤も、IDISKCellItem/IBasicCellItem
     * のような型別ディスパッチも無いため、代わりにAE2の公開API
     * ({@link ICellRegistry#isCellHandled}/{@link ICellRegistry#getCellInventory})を使い、
     * 「Item/Fluidいずれかのチャンネルで登録済みのセルとして認識され、かつ使用byte数が
     * 1以上」であれば拒否する形で同等の効果を再現している。通常のAE2ストレージセルは
     * もちろん、ae2uelthings自身のDISKや他アドオンのセルにも汎用的に効く
     * (ただしItem/Fluid以外の独自チャンネルしか持たないセルには対応しない)。</p>
     */
    private static boolean isCellNestingPrevented(IAEItemStack input) {
        // 修正メモ(共有ItemStack破壊対策): 以前は input.getDefinition() をそのまま渡していた。
        // getDefinition()はAE2が内部で共有しているItemStack(AESharedItemStackのキー)で、
        // 読み取り専用で扱う必要がある。AE2標準セルのハンドラは初期化時に
        // Platform.openNbtData()で空NBTを付与することがあり、共有Stackを直接渡すと
        // キー(ハッシュ)が書き換わってAE2内部の登録が壊れる恐れがあった。
        // AE2本体の同種チェックと同じく、createItemStack()で作ったコピーを使う。
        ItemStack stack = input.createItemStack();
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        ICellRegistry cellRegistry = AEApi.instance().registries().cell();
        if (!cellRegistry.isCellHandled(stack)) {
            return false;
        }

        return hasUsedBytes(stack, cellRegistry, AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class))
                || hasUsedBytes(stack, cellRegistry, AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class));
    }

    /** 指定チャンネルでこのセルの中身を取得し、使用byte数が1以上あるかを見る。対応チャンネルでなければfalse。 */
    private static <T extends IAEStack<T>> boolean hasUsedBytes(ItemStack stack, ICellRegistry cellRegistry, IStorageChannel<T> channel) {
        if (channel == null) {
            return false;
        }
        ICellInventoryHandler<T> handler;
        try {
            handler = cellRegistry.getCellInventory(stack, null, channel);
        } catch (Exception | LinkageError e) {
            // 他アドオンのセル実装が想定外の例外を投げても、DISK自体の動作は止めない
            ExampleMod.LOGGER.warn("[{}] isCellNestingPrevented: ", Tags.MOD_ID, e);
            return false;
        }
        if (handler == null) {
            return false;
        }
        ICellInventory<T> inv = handler.getCellInv();
        return inv != null && inv.getUsedBytes() > 0;
    }

    // ------------------------------------------------------------------
    // ICellInventoryHandler<IAEItemStack>
    // ------------------------------------------------------------------

    @Override
    public IAEItemStack injectItems(IAEItemStack input, Actionable mode, IActionSource src) {
        if (input == null || input.getStackSize() <= 0) return input;

        // 参考元と同じく、フィルターは既存タイプへの追加投入も含めて毎回適用する
        if (!passesFilter(input)) {
            return input;
        }

        if (isCellNestingPrevented(input)) {
            // 参考元(AE2 MEGA Things)と同じ仕様: 中身が空でないストレージセル
            // (通常のAE2セル・別のDISK等)はDISKの中には格納できない(容量バイパス対策)。
            return input;
        }

        // 参考元と同じく、空き容量(総byte数 − 合計個数)の範囲で受け入れる
        long toAccept = Math.min(input.getStackSize(), getFreeBytes());
        if (toAccept <= 0) return input;

        if (mode == Actionable.MODULATE) {
            // 個数・タイプ数のキャッシュを正しく保つため、必ずストレージ側のinsert()経由で増やす
            getOrCreateStorage().insert(input, toAccept);
            markDirty();
        }
        if (toAccept >= input.getStackSize()) return null;
        IAEItemStack remainder = input.copy();
        remainder.decStackSize(toAccept);
        return remainder;
    }

    @Override
    public IAEItemStack extractItems(IAEItemStack request, Actionable mode, IActionSource src) {
        if (request == null || storage == null) return null;
        IAEItemStack existing = storage.getItems().findPrecise(request);
        if (existing == null) return null;

        long size = Math.min(request.getStackSize(), existing.getStackSize());
        if (size <= 0) return null;

        IAEItemStack result = existing.copy();
        result.setStackSize(size);

        if (mode == Actionable.MODULATE) {
            // キャッシュを保つため、ストレージ側のextract()経由で減らす
            storage.extract(existing, size);
            // IItemList<IAEItemStack> には remove(T) が無いため、0個になったエントリは
            // ここでは削除せずリストに残す。AE2UELのItemListはイテレータ(MeaningfulItemIterator)
            // が走査時に isMeaningful()==false (=0個)のエントリを自動で取り除くため、
            // 次に getAvailableItems 等でリストを走査した時点で消え、肥大化はしない
            // (AE2UELソースで確認済み)。
            markDirty();
        }
        return result;
    }

    @Override
    public IItemList<IAEItemStack> getAvailableItems(IItemList<IAEItemStack> out) {
        if (storage != null) {
            for (IAEItemStack stack : storage.getItems()) {
                if (stack.getStackSize() > 0) {
                    out.add(stack);
                }
            }
        }
        return out;
    }

    @Override
    public IStorageChannel<IAEItemStack> getChannel() {
        return AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
    }

    @Override
    public AccessRestriction getAccess() {
        return AccessRestriction.READ_WRITE;
    }

    /**
     * 修正メモ(分割セルの優先): 以前は常にfalseで、フィルターを設定したDISKが優先されなかった。
     * ME Drive/Chest が包む MEInventoryHandler#isPrioritized は、外側のリストが空のため
     * このメソッドの戻り値をそのまま使う。AE2UEL標準セル(MEInventoryHandler)と同じく、
     * WHITELISTかつフィルターに一致する場合にtrueを返し、ネットワーク投入時に優先させる。
     */
    @Override
    public boolean isPrioritized(IAEItemStack input) {
        return partitionMode == IncludeExclude.WHITELIST
                && !partitionList.isEmpty()
                && partitionList.isListed(input);
    }

    /** AE2UEL標準セルと同じく、フィルターを通らないものは受け入れ候補から外す。 */
    @Override
    public boolean canAccept(IAEItemStack input) {
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
    public ICellInventory<IAEItemStack> getCellInv() {
        return this;
    }

    // ------------------------------------------------------------------
    // ICellInventory<IAEItemStack>
    // ------------------------------------------------------------------

    @Override
    public boolean isPreformatted() {
        return !partitionList.isEmpty();
    }

    @Override
    public boolean isFuzzy() {
        // AE2UEL標準セル(BasicCellInventoryHandler#isFuzzy)と同じ判定
        return partitionList instanceof FuzzyPriorityList;
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
        return ((IDiskCellDefinition) cellItem.getItem()).getIdleDrain(cellItem);
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

    @Override
    public boolean canHoldNewItem() {
        return getFreeBytes() > 0;
    }

    @Override
    public long getTotalBytes() {
        return usableBytes;
    }

    @Override
    public long getFreeBytes() {
        return Math.max(0, usableBytes - getUsedBytes());
    }

    /** 参考元と同じく、使用byte数 = 合計個数(1アイテム = 1byte、タイプごとの消費なし)。 */
    @Override
    public long getUsedBytes() {
        return getStoredItemCount();
    }

    @Override
    public long getTotalItemTypes() {
        return Integer.MAX_VALUE;
    }

    @Override
    public long getStoredItemCount() {
        return storage == null ? 0 : storage.getStoredItemCount();
    }

    @Override
    public long getStoredItemTypes() {
        return storage == null ? 0 : storage.getStoredItemTypes();
    }

    /** 新しいタイプは最低1個=1byteを使うため、空きbyte数がそのまま追加可能なタイプ数の上限になる。 */
    @Override
    public long getRemainingItemTypes() {
        return getFreeBytes();
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
        if (getUsedBytes() == 0) return 4;
        if (canHoldNewItem()) return 1;
        return 3;
    }

    @Override
    public void persist() {
        if (storage == null) {
            return;
        }
        if (storage.isEmpty()) {
            // 空になったらマネージャー側の参照とItemStack側のUUIDを両方消し、
            // 空レコードがマネージャー内に溜まり続けるのを防ぐ
            DiskStorageManager.getCached().removeDisk(storage.getUUID());
            NBTTagCompound tag = cellItem.getTagCompound();
            if (tag != null) {
                tag.removeTag(TAG_DISK_UUID);
            }
            clearSummary(cellItem);
            storage = null;
        } else {
            DiskStorageManager.getCached().updateDisk(storage);
            writeSummary(cellItem, storage.getStoredItemCount());
        }
    }

    public boolean isEmpty() {
        return storage == null || storage.isEmpty();
    }
}
