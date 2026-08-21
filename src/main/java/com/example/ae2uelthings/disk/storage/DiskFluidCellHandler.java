package com.example.ae2uelthings.disk.storage;

import appeng.api.storage.ICellHandler;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.data.IAEStack;
import com.example.ae2uelthings.api.IDiskFluidCellDefinition;
import net.minecraft.item.ItemStack;

public class DiskFluidCellHandler implements ICellHandler {

    @Override
    public boolean isCell(ItemStack is) {
        return is != null && !is.isEmpty() && is.getItem() instanceof IDiskFluidCellDefinition;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends IAEStack<T>> ICellInventoryHandler<T> getCellInventory(
            ItemStack is, ISaveProvider container, IStorageChannel<T> channel) {
        if (!isCell(is)) return null;
        if (!(channel instanceof IFluidStorageChannel)) {
            return null;
        }
        IDiskFluidCellDefinition cell = (IDiskFluidCellDefinition) is.getItem();
        return (ICellInventoryHandler<T>) new DiskFluidCellInventoryHandler(
                is, cell.getBytes(is), cell.getBytesPerType(is), container);
    }

    @Override
    public <T extends IAEStack<T>> double cellIdleDrain(ItemStack is, ICellInventoryHandler<T> handler) {
        return ((IDiskFluidCellDefinition) is.getItem()).getIdleDrain(is);
    }
}