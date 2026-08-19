package com.example.ae2uelthings.disk.storage;

import appeng.api.AEApi;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IItemList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

import java.util.UUID;

/**
 * 1枚の液体DISKが実際に保持している中身(フルイド種類×量のリスト)を表すデータホルダー。
 * {@link DiskCellStorage} のフルイド版で、役割・構造は完全に対応している。
 *
 * 旧実装(inline NBT版)との違い: 以前は cellItem.getTagCompound() に直接書き込んでいたが、
 * アイテム版と同じくUUID経由で {@link DiskStorageManager} 側に集約する方式に変更した。
 * 併せて、旧実装がフルイドの同一性を stack.getFluid().getName() (登録名のみ)で
 * 判定していた点も、IItemList#findPrecise によるAE2標準の判定(NBT差異も含む)に切り替えている。
 *
 * FluidStack#amount は int (vanilla Forgeの標準API)なので、アイテム版のItemStack#Countで
 * 問題になった byte 溢れの心配はない。
 */
public class DiskFluidCellStorage {

    private static final String TAG_FLUIDS = "Fluids";

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

    /** stackSize>0のエントリが1つも無ければ空とみなす(0個エントリは残ることがあるため) */
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

    /** 格納されている全種類の合計mB (1mB=1byteモデルでの使用byte数と一致) */
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

    /** 格納されているフルイド種類数 */
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

    // ------------------------------------------------------------------
    // NBT (DiskStorageManager 側から呼ばれる)
    // ------------------------------------------------------------------

    public NBTTagCompound writeToNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        if (fluids != null) {
            for (IAEFluidStack stack : fluids) {
                if (stack.getStackSize() <= 0) {
                    continue;
                }
                FluidStack fs = stack.getFluidStack().copy();
                fs.amount = (int) Math.min(Integer.MAX_VALUE, stack.getStackSize());
                NBTTagCompound entry = new NBTTagCompound();
                fs.writeToNBT(entry);
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
            FluidStack fs = FluidStack.loadFluidStackFromNBT(list.getCompoundTagAt(i));
            if (fs != null && fs.amount > 0) {
                IAEFluidStack stack = getChannel().createStack(fs);
                if (stack != null) {
                    fluids.add(stack);
                }
            }
        }
        storage.fluids = fluids;
        return storage;
    }
}