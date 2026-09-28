package com.example.ae2uelthings.disk.storage;

import appeng.api.AEApi;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IItemList;
import appeng.core.AEConfig;
import com.example.ae2uelthings.ExampleMod;
import com.example.ae2uelthings.Tags;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

import java.util.UUID;

/**
 * DISKセル1枚ぶんの実データ(液体版、数量は生のmB)。{@link DiskStorageManager} がUUIDをキーに保持する。
 *
 * <p>修正メモ(負荷対策): アイテム版 {@link DiskCellStorage} と同じく、合計量と格納タイプ数を
 * キャッシュしてO(1)で返すようにした。中身の増減は必ず {@link #insert} / {@link #extract} 経由で
 * 行うこと({@link #getFluids()} は読み取り専用)。</p>
 *
 * <p>読み込めないエントリの扱いは、アイテム版 {@link DiskCellStorage} と同じく
 * AE2UEL標準セルの仕様に合わせている(存在しない液体は削除、読み込み中の例外は設定次第で削除)。</p>
 */
public class DiskFluidCellStorage {

    private static final String TAG_FLUIDS = "Fluids";

    /**
     * 修正: 実際の格納mB数(long)をここに保存する。
     *
     * バグ修正メモ(21億mB問題): FluidStack#writeToNBT()が書き込む"Amount"はint型のため、
     * 以前はここに直接 stack.getStackSize() をint変換して渡していた。1byte=1000mBの
     * 容量モデルでは、4096k/16384k/maxティアの実用容量がInteger.MAX_VALUE(約21億)mBを
     * 容易に超える(16384kは容量の約13%、maxは約0.1%で到達)ため、それを超えて貯めた分が
     * ワールド保存→再読み込みのたびに無条件で切り捨てられ、データが消失していた。
     * 対策として、FluidStack本体のAmountタグ(int)には使わないダミー値(1)を入れて
     * 実際の数量には関与させず、実数量はこの独自タグにlongでそのまま保存する。
     * 読み込み時もこのタグを優先して使うため、int上限を超えても安全に保存・復元できる。
     */
    private static final String TAG_AMOUNT_MB = "AmountMb";

    private final UUID uuid;
    private IItemList<IAEFluidStack> fluids;

    /** 格納中の合計mB(キャッシュ) */
    private long storedCount;
    /** stackSize > 0 のエントリ数=格納タイプ数(キャッシュ) */
    private int storedTypes;

    /** 読み込み時に削除したエントリ数(AE2UEL標準セルと同じく、読めないエントリは削除する) */
    private int removedOnLoad;

    public DiskFluidCellStorage(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUUID() {
        return uuid;
    }

    /** 読み取り専用。中身の増減には {@link #insert} / {@link #extract} を使うこと。 */
    public IItemList<IAEFluidStack> getFluids() {
        if (fluids == null) {
            fluids = getChannel().createList();
        }
        return fluids;
    }

    /** 指定液体を amount mB 追加する(既存エントリがあれば加算、無ければ新規作成)。 */
    public void insert(IAEFluidStack input, long amount) {
        if (amount <= 0) {
            return;
        }
        IItemList<IAEFluidStack> list = getFluids();
        IAEFluidStack existing = list.findPrecise(input);
        if (existing != null) {
            if (existing.getStackSize() <= 0) {
                storedTypes++;
            }
            existing.incStackSize(amount);
        } else {
            IAEFluidStack toStore = input.copy();
            toStore.setStackSize(amount);
            list.add(toStore);
            storedTypes++;
        }
        storedCount += amount;
    }

    /** {@link #getFluids()} の findPrecise で取得した既存エントリから amount mB 減らす。 */
    public void extract(IAEFluidStack existing, long amount) {
        if (existing == null || amount <= 0) {
            return;
        }
        long before = existing.getStackSize();
        long removed = Math.min(amount, Math.max(0, before));
        existing.decStackSize(removed);
        storedCount -= removed;
        if (before > 0 && existing.getStackSize() <= 0) {
            storedTypes--;
        }
    }

    public boolean isEmpty() {
        return storedTypes <= 0;
    }

    public long getStoredItemCount() {
        return storedCount;
    }

    public int getStoredItemTypes() {
        return storedTypes;
    }

    /** 読み込み時に削除したエントリ数(0なら削除なし)。 */
    public int getRemovedOnLoad() {
        return removedOnLoad;
    }

    /** キャッシュを実データから数え直す(NBT読み込み直後に使う)。 */
    private void recalculate() {
        storedCount = 0;
        storedTypes = 0;
        if (fluids == null) {
            return;
        }
        for (IAEFluidStack stack : fluids) {
            if (stack.getStackSize() > 0) {
                storedCount += stack.getStackSize();
                storedTypes++;
            }
        }
    }

    private static IFluidStorageChannel getChannel() {
        return AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class);
    }


    public NBTTagCompound writeToNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        if (fluids != null) {
            for (IAEFluidStack stack : fluids) {
                long amount = stack.getStackSize();
                if (amount <= 0) {
                    continue;
                }
                FluidStack fs = stack.getFluidStack().copy();
                // Amount(int)タグは実数量として使わないため、Fluid種別+NBTタグの保存だけに使う
                // ダミー値を入れておく(0だと種別によっては無効値扱いされる恐れがあるため1)。
                fs.amount = 1;
                NBTTagCompound entry = new NBTTagCompound();
                fs.writeToNBT(entry);
                // 実際の数量はここにlongでそのまま保存(int上限の影響を受けない)
                entry.setLong(TAG_AMOUNT_MB, amount);
                list.appendTag(entry);
            }
        }
        tag.setTag(TAG_FLUIDS, list);
        return tag;
    }

    public static DiskFluidCellStorage readFromNBT(UUID uuid, NBTTagCompound tag) {
        DiskFluidCellStorage storage = new DiskFluidCellStorage(uuid);
        NBTTagList list = tag.getTagList(TAG_FLUIDS, 10); // 10 = NBTTagCompound
        IItemList<IAEFluidStack> fluids = getChannel().createList();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            // 新形式(AmountMb)があればそれを優先。無ければ旧形式(int Amount、既に
            // 切り詰められている可能性がある過去データ)にフォールバックする。
            // (FluidStack#writeToNBT の Amount は int タグ)
            long amount = entry.hasKey(TAG_AMOUNT_MB) ? entry.getLong(TAG_AMOUNT_MB) : entry.getInteger("Amount");
            if (amount <= 0) {
                continue;
            }
            // AE2UEL BasicCellInventory#loadCellItem と同じ扱い
            IAEFluidStack stack;
            try {
                // 未登録の液体は null になる(FluidStack#loadFluidStackFromNBT)
                FluidStack fs = FluidStack.loadFluidStackFromNBT(entry);
                stack = fs == null ? null : getChannel().createStack(fs);
                if (stack == null) {
                    ExampleMod.LOGGER.warn("[{}] 液体DISK UUID={} から液体 {} を削除します(この環境に存在しない液体のため)。",
                            Tags.MOD_ID, uuid, entry);
                    storage.removedOnLoad++;
                    continue;
                }
            } catch (Throwable ex) {
                if (AEConfig.instance().isRemoveCrashingItemsOnLoad()) {
                    ExampleMod.LOGGER.warn("[{}] 液体DISK UUID={} から液体 {} を削除します(読み込み中にエラーが発生したため)。",
                            Tags.MOD_ID, uuid, entry, ex);
                    storage.removedOnLoad++;
                    continue;
                }
                throw ex;
            }
            stack.setStackSize(amount);
            fluids.add(stack);
        }
        storage.fluids = fluids;
        storage.recalculate();
        return storage;
    }
}