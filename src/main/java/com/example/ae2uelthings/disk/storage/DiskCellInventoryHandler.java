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
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.items.IItemHandler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
                        "[{}] DISKセルのDiskUUIDタグが不正です (\"{}\")。"
                                + "データ保護のため、このセルをロックします(投入・分解不可)。",
                        Tags.MOD_ID, rawUuid);
            }
            return null;
        }

        // 修正メモ(読み込み失敗DISKの保護): DiskStorageManagerが読み込みに失敗して生NBTのまま
        // 保持しているDISKは、以前は空のセルに見えていたため、スニーク右クリックで分解できてしまい
        // セル側のUUID(=データへの唯一の参照)が失われていた。中身ありとして扱いロックする。
        if (manager.isUnreadableDisk(rawUuid)) {
            locked = true;
            if (DiskStorageManager.shouldReport("unreadable:" + uuid)) {
                ExampleMod.LOGGER.error(
                        "[{}] DISK UUID={} のデータは読み込みに失敗しているため、このセルをロックします"
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
        DiskCellStorage existing = manager.getDisk(uuid);
        if (existing == null) {
            if (manager.isLoaded()) {
                missing = true;
                if (DiskStorageManager.shouldReport("missing:" + uuid)) {
                    ExampleMod.LOGGER.warn(
                            "[{}] DISK UUID={} のデータがこのワールドに見つかりません。"
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
            ExampleMod.LOGGER.debug("[{}] DISK UUID={} は空のため、次の保存時にセルからUUIDを外します",
                    Tags.MOD_ID, uuid);
        }
        // 移行処理: 要約NBT("ic")が無い旧セルは、実データがあれば補う
        // (クライアント側のダミーマネージャーでは中身が空なので書き込まれない)。
        // 読み込み時にエントリが削除されて実データと食い違っている場合も補正する
        // (AE2UELが読み込み直後に "ic" を再計算して書き直すのと同じ)。
        if (!existing.isEmpty() && tag.getLong(TAG_ITEM_COUNT) != existing.getStoredItemCount()) {
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
        // データが見つからないセル等、UUIDが既にある場合はそれを引き継ぐ(参照を上書きしない)
        UUID uuid = cellUuid != null ? cellUuid : UUID.randomUUID();
        tag.setString(TAG_DISK_UUID, uuid.toString());
        cellUuid = uuid;
        missing = false;
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
     *
     * <p><b>軽量化:</b> この判定はアイテム投入のたびに(SIMULATE/MODULATEの両方で)呼ばれるため、
     * 「セルとして登録されているか」の結果を Item + メタデータ単位でキャッシュする
     * ({@link #isCellItem})。セルでない普通のアイテム(投入の大半)は、ItemStackのコピー作成も
     * CellRegistryの走査も行わずに即座にfalseを返す。セルだった場合だけ、従来どおり
     * コピーを作って中身の有無を確認する。</p>
     */
    private static boolean isCellNestingPrevented(IAEItemStack input) {
        // 修正メモ(投入のたびのコスト削減): 以前は毎回 createItemStack() でコピーを作ってから
        // isCellHandled() を呼んでいたが、Flareで計測したところ投入処理のコストの大半を
        // 占めていた(GT環境のように投入回数が多いと効いてくる)。セルかどうかは
        // Item + メタデータで決まるため、その結果をキャッシュして先に判定する。
        if (!isCellItem(input)) {
            return false;
        }

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
        return hasUsedBytes(stack, cellRegistry, AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class))
                || hasUsedBytes(stack, cellRegistry, AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class));
    }

    /** Item → (メタデータ → 1:セル / 0:セルではない) のキャッシュ。{@link #isCellItem} 専用。 */
    private static final Map<Item, Int2ByteOpenHashMap> CELL_ITEM_CACHE = new ConcurrentHashMap<>();

    /**
     * このアイテムがCellRegistryにセルとして登録されているか(Item + メタデータ単位でキャッシュ)。
     *
     * <p>キャッシュが無い組み合わせのときだけ、コピーしたItemStackで
     * {@link ICellRegistry#isCellHandled} を呼んで結果を記録する(共有ItemStackは渡さない)。
     * セルハンドラはmodの初期化時に登録され、ワールド内でアイテムが投入される頃には
     * 増減しないため、結果は起動中ずっと有効。</p>
     *
     * <p>前提: セルかどうかはItemとメタデータで決まり、NBTには左右されない。AE2UEL標準セル・
     * クリエイティブセル・このmodのDISKはいずれもItemの種類だけで判定している。
     * NBTによってセル扱いが変わる独自ハンドラを持つアドオンがあると、その判定はすり抜ける
     * (その場合もDISKの動作自体は壊れず、ネスト防止が効かないだけ)。</p>
     */
    private static boolean isCellItem(IAEItemStack input) {
        Item item = input.getItem();
        if (item == null) {
            return false;
        }
        int meta = input.getItemDamage();

        Int2ByteOpenHashMap byMeta = CELL_ITEM_CACHE.computeIfAbsent(item, k -> {
            Int2ByteOpenHashMap m = new Int2ByteOpenHashMap();
            m.defaultReturnValue((byte) -1);
            return m;
        });
        byte cached;
        synchronized (byMeta) {
            cached = byMeta.get(meta);
        }
        if (cached >= 0) {
            return cached == 1;
        }

        ItemStack copy = input.createItemStack();
        boolean isCell = copy != null && !copy.isEmpty()
                && AEApi.instance().registries().cell().isCellHandled(copy);
        synchronized (byMeta) {
            byMeta.put(meta, (byte) (isCell ? 1 : 0));
        }
        return isCell;
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

        // ロック中(不正UUID・読み込み失敗)のセルには何も入れない。入れると新しいUUIDが
        // 採番され、元のデータへの参照が上書きされてしまうため。
        if (locked) {
            return input;
        }

        // 参考元と同じく、フィルターは既存タイプへの追加投入も含めて毎回適用する
        if (!passesFilter(input)) {
            return input;
        }

        // 参考元と同じく、空き容量(総byte数 − 合計個数)の範囲で受け入れる。
        // 満杯のDISKでは下のネスト判定を行う必要が無いため、先に空き容量を見る。
        long toAccept = Math.min(input.getStackSize(), getFreeBytes());
        if (toAccept <= 0) return input;

        if (isCellNestingPrevented(input)) {
            // 参考元(AE2 MEGA Things)と同じ仕様: 中身が空でないストレージセル
            // (通常のAE2セル・別のDISK等)はDISKの中には格納できない(容量バイパス対策)。
            return input;
        }

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
        return !locked
                && partitionMode == IncludeExclude.WHITELIST
                && !partitionList.isEmpty()
                && partitionList.isListed(input);
    }

    /** AE2UEL標準セルと同じく、フィルターを通らないものは受け入れ候補から外す。 */
    @Override
    public boolean canAccept(IAEItemStack input) {
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
        return !locked && getFreeBytes() > 0;
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
        // ロック中は満杯(赤)表示にして、異常があることがドライブ上で分かるようにする
        if (locked) return 3;
        if (getUsedBytes() == 0) return 4;
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
            // 空になったらマネージャー側の参照とItemStack側のUUIDを両方消し、
            // 空レコードがマネージャー内に溜まり続けるのを防ぐ
            DiskStorageManager.getCached().removeDisk(storage.getUUID());
            NBTTagCompound tag = cellItem.getTagCompound();
            if (tag != null) {
                tag.removeTag(TAG_DISK_UUID);
            }
            clearSummary(cellItem);
            storage = null;
            cellUuid = null;
        } else {
            DiskStorageManager.getCached().updateDisk(storage);
            writeSummary(cellItem, storage.getStoredItemCount());
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
