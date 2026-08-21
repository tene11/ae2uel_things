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
import com.example.ae2uelthings.ExampleMod;
import com.example.ae2uelthings.Tags;
import com.example.ae2uelthings.api.IDiskCellDefinition;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.items.IItemHandler;
import com.example.ae2uelthings.disk.DiskConfig;
import com.example.ae2uelthings.disk.DiskUpgrades;

import java.util.UUID;

/**
 * DISKセル1個ぶんの、AE2ネットワークから見た「取引窓口」。
 *
 * DiskFluidCellInventoryHandler(フルイド版、実際にコンパイル済みの実装)と同じく、
 * ICellInventoryHandler<T> と ICellInventory<T> を同一クラスで実装し、getCellInv()は
 * this を返す構成にしている。フルイド版と違うのは中身の永続化先で、こちらは
 * ItemStackのNBTに直接書き込む代わりに、UUID文字列(キー: "DiskUUID")だけをNBTに持たせ、
 * 実データは {@link DiskStorageManager} 側の {@link DiskCellStorage} に集約する。
 * ME Drive/端末でのGUI表示時に発生するインベントリ同期パケットへ重いNBTが
 * 乗るのを避けるのが目的 (本スレッドで確認したAE2Things本家の設計を踏襲)。
 *
 * getFuzzyMode/getConfigInventory/getUpgradesInventory は、フルイド版のように
 * 固定値を返すのではなく、appeng.api.storage.ICellWorkbenchItem 経由でセルアイテム側の
 * 既存実装に委譲している(ItemDiskCellはFuzzyModeをNBTに永続化する処理を既に持っている
 * ため、ここで別々の実装を持つと二重管理になる)。getIdleDrainは
 * {@link com.example.ae2uelthings.api.IDiskCellDefinition} 経由で委譲している。
 * どちらも特定クラス(ItemDiskCell)への直接依存ではなくインターフェース経由なので、
 * 他アドオンが同じインターフェースを実装した独自アイテムを作れば、そのまま動作する。
 */
public class DiskCellInventoryHandler implements ICellInventoryHandler<IAEItemStack>, ICellInventory<IAEItemStack> {

    private static final String TAG_DISK_UUID = "DiskUUID";

    private final ItemStack cellItem;
    private final long usableBytes;
    private final int bytesPerType;
    private final ISaveProvider container;

    /** UUID未採番(=まだ何も挿入されたことがない)の場合はnull */
    private DiskCellStorage storage;

    public DiskCellInventoryHandler(ItemStack cellItem, long usableBytes, int bytesPerType, ISaveProvider container) {
        this.cellItem = cellItem;
        this.usableBytes = usableBytes;
        this.bytesPerType = bytesPerType;
        this.container = container;
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
        return DiskStorageManager.getCached().getOrCreateDisk(uuid);
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
        if (container != null) {
            container.saveChanges(this);
        } else {
            persist();
        }
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
        ItemStack stack = input.getDefinition();
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

        if (isCellNestingPrevented(input)) {
            // 参考元(AE2 MEGA Things)と同じ仕様: 中身が空でないストレージセル
            // (通常のAE2セル・別のDISK等)はDISKの中には格納できない(容量バイパス対策)。
            return input;
        }

        IItemList<IAEItemStack> items = storage != null ? storage.getItems() : null;
        IAEItemStack existing = items != null ? items.findPrecise(input) : null;

        // フィルター判定は「本当に初めて見るタイプ」(existing == null)のときだけ行う。
        // 既存タイプ(0個に減っているだけの残留エントリを含む)への追加投入は、
        // 後からフィルター設定を変更しても既存分には遡及しない(AE2標準セルと同じ仕様)。
        if (existing == null && !isAcceptedByFilter(input.getDefinition())) {
            return input;
        }

        // バグ修正(フルイド版と同種): existing != null なら常に「既存タイプへの追加」として
        // 扱っていたが、extractItems()は0個になったエントリを削除しないため、
        // 「過去に全量抽出して0個のまま残っているタイプ」への再投入もfindPreciseで
        // 「既存」扱いになり、bytesPerTypeの予約が漏れていた(getStoredItemTypes()は
        // 0個エントリを除外するため、再投入した瞬間にタイプ数が+1され、空き容量ギリギリまで
        // 投入すると使用量がusableBytesをbytesPerType分超えてしまう)。
        // アイテム版はBYTES_PER_TYPE=1のため実害は僅少だが、フルイド版と計算ロジックを
        // 揃えるため同様に修正する。
        boolean addsNewType = existing == null || existing.getStackSize() <= 0;

        // 要検証: 以前は usableBytes - getStoredItemCount() だけで、既存タイプ分の
        // bytesPerTypeオーバーヘッドがfreeBytesに反映されていなかった(getUsedBytes()と不整合)。
        // getFreeBytes()経由に統一し、両者が常に同じ計算を使うようにした。
        long freeBytes = getFreeBytes();
        long acceptable = addsNewType ? freeBytes - bytesPerType : freeBytes;
        long toAccept = Math.min(input.getStackSize(), Math.max(0, acceptable));
        if (toAccept <= 0) return input;

        if (mode == Actionable.MODULATE) {
            if (existing != null) {
                existing.incStackSize(toAccept);
            } else {
                IAEItemStack toStore = input.copy();
                toStore.setStackSize(toAccept);
                getOrCreateStorage().getItems().add(toStore);
            }
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
            existing.decStackSize(size);
            // 要検証: IItemList<IAEItemStack> に remove(T) が無いため、0個のエントリは
            // そのままリストに残す。getAvailableItems/isEmpty/getStoredItemTypes側で
            // stackSize<=0のエントリを無視することで実害を防いでいる。
            // 同じタイプを再度insertした際はfindPreciseでこの0エントリがそのまま
            // 再利用されるため、通常のinsert/extract往復では肥大化しない。
            // 完全に別タイプへ入れ替わり続けるような使い方だとリストが少しずつ
            // 増える可能性があるため、IItemListに別名の削除メソッドが無いか
            // (IntelliJで storage.getItems(). まで打ってCtrl+Spaceで補完候補を確認)
            // 余裕があるときに確認すること。
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

    @Override
    public boolean isPrioritized(IAEItemStack input) {
        return false;
    }

    @Override
    public boolean canAccept(IAEItemStack input) {
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
    public ICellInventory<IAEItemStack> getCellInv() {
        return this;
    }

    // ------------------------------------------------------------------
    // ICellInventory<IAEItemStack>
    // ------------------------------------------------------------------

    @Override
    public boolean isPreformatted() {
        // configに1つでもフィルターアイテムが設定されていればtrue(参考元と同じ仕様)。
        // 何も設定されていなければ従来通り制限なし(false)。
        return DiskConfig.hasAnyFilter(getConfigInventory());
    }

    /**
     * 新規タイプの受け入れをタイプフィルターで判定する。
     * 未設定(isPreformatted()==false)なら常にtrue(従来通り無制限)。
     * WHITELIST: フィルターに一致するタイプのみ許可。
     * BLACKLIST(Inverter Card挿入時): フィルターに一致しないタイプのみ許可。
     * 既に格納済みのタイプへの追加投入(=このメソッドを通らない側)は、後からフィルターを
     * 変更しても引き続き受け付ける(AE2標準セルと同じ「後付け変更は既存分に遡及しない」挙動)。
     */
    private boolean isAcceptedByFilter(ItemStack candidate) {
        if (!isPreformatted()) {
            return true;
        }
        boolean matches = DiskConfig.matches(getConfigInventory(), candidate);
        return getIncludeExcludeMode() == IncludeExclude.WHITELIST ? matches : !matches;
    }

    @Override
    public boolean isFuzzy() {
        // Fuzzy Cardが挿さっていればtrue(参考元のitem版DISKと同じ仕様)
        return DiskUpgrades.hasFuzzyCard(getUpgradesInventory());
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
     * 修正メモ: 以前は getStoredItemCount() のみ(=個数の合計)を返しており、
     * 新規タイプ登録時に injectItems() 内で一時的にだけ差し引いていた
     * bytesPerType分のコストが、ここには一切反映されていなかった。
     * このため「タイプ数コストがワールド再読み込み後に消える」ように見える不整合が生じていた
     * (実際にはリロード有無に関わらず、同一タイプの追加投入でコストを取り戻せてしまうバグだった)。
     * getStoredItemTypes() * bytesPerType を加えることで、injectItems()の新規タイプ受け入れ判定
     * (freeBytes - bytesPerType)と整合する「実際に消費しているbyte数」を返すようにした。
     */
    @Override
    public long getUsedBytes() {
        return getStoredItemCount() + getStoredItemTypes() * (long) bytesPerType;
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
            // 空になったらマネージャー側の参照とItemStack側のUUIDを両方消し、
            // 空レコードがマネージャー内に溜まり続けるのを防ぐ
            DiskStorageManager.getCached().removeDisk(storage.getUUID());
            NBTTagCompound tag = cellItem.getTagCompound();
            if (tag != null) {
                tag.removeTag(TAG_DISK_UUID);
            }
            storage = null;
        } else {
            DiskStorageManager.getCached().updateDisk(storage);
        }
    }

    public boolean isEmpty() {
        return storage == null || storage.isEmpty();
    }
}