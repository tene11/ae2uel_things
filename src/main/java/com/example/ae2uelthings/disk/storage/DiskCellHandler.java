package com.example.ae2uelthings.disk.storage;

import appeng.api.storage.ICellHandler;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEStack;
import com.example.ae2uelthings.disk.ItemDiskCell;
import net.minecraft.item.ItemStack;

/**
 * DISKセルをAE2のセルハンドラレジストリに登録するためのアダプタ。
 *
 * 修正メモ: 以前の版は isCell/getCellInventory/cellIdleDrain の3つを非ジェネリックで
 * 実装していたが、実際の appeng.api.storage.ICellHandler は
 * getCellInventory / cellIdleDrain がともに <T extends IAEStack<T>> のジェネリックメソッドで、
 * getCellInventory には IStorageChannel<T> channel 引数も必要だった。
 * これは DiskFluidCellHandler.java (フルイド版、実際にコンパイルが通っている実装)の
 * シグネチャから判明した内容で、AE2UELでも同一のはず。
 */
public class DiskCellHandler implements ICellHandler {

    @Override
    public boolean isCell(ItemStack is) {
        return is != null && !is.isEmpty() && is.getItem() instanceof ItemDiskCell;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends IAEStack<T>> ICellInventoryHandler<T> getCellInventory(
            ItemStack is, ISaveProvider container, IStorageChannel<T> channel) {
        if (!isCell(is)) return null;
        if (!(channel instanceof IItemStorageChannel)) {
            return null;
        }
        ItemDiskCell cell = (ItemDiskCell) is.getItem();
        return (ICellInventoryHandler<T>) new DiskCellInventoryHandler(
                is, cell.getBytes(is), cell.getBytesPerType(is), container);
    }

    @Override
    public <T extends IAEStack<T>> double cellIdleDrain(ItemStack is, ICellInventoryHandler<T> handler) {
        return ((ItemDiskCell) is.getItem()).getIdleDrain();
    }
}