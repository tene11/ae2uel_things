package com.example.ae2uelthings.disk.storage;

import appeng.api.AEApi;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IItemList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

import java.util.UUID;

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

    public DiskFluidCellStorage(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUUID() {
        return uuid;
    }

    public IItemList<IAEFluidStack> getFluids() {
        if (fluids == null) {
            fluids = getChannel().createList();
        }
        return fluids;
    }

    public boolean isEmpty() {
        if (fluids == null) {
            return true;
        }
        for (IAEFluidStack stack : fluids) {
            if (stack.getStackSize() > 0) {
                return false;
            }
        }
        return true;
    }


    public long getStoredItemCount() {
        if (fluids == null) {
            return 0;
        }
        long total = 0;
        for (IAEFluidStack stack : fluids) {
            total += stack.getStackSize();
        }
        return total;
    }

    public int getStoredItemTypes() {
        if (fluids == null) {
            return 0;
        }
        int count = 0;
        for (IAEFluidStack stack : fluids) {
            if (stack.getStackSize() > 0) {
                count++;
            }
        }
        return count;
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
            FluidStack fs = FluidStack.loadFluidStackFromNBT(entry);
            if (fs == null) {
                continue;
            }
            // 新形式(AmountMb)があればそれを優先。無ければ旧形式(int Amount、既に
            // 切り詰められている可能性がある過去データ)にフォールバックする。
            long amount = entry.hasKey(TAG_AMOUNT_MB) ? entry.getLong(TAG_AMOUNT_MB) : fs.amount;
            if (amount <= 0) {
                continue;
            }
            IAEFluidStack stack = getChannel().createStack(fs);
            if (stack != null) {
                stack.setStackSize(amount);
                fluids.add(stack);
            }
        }
        storage.fluids = fluids;
        return storage;
    }
}