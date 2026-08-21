package com.example.ae2uelthings.disk.storage;

import appeng.api.AEApi;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.util.UUID;

public class DiskCellStorage {

    private static final String TAG_ITEMS = "Items";
    private static final String TAG_ITEM = "Item";
    private static final String TAG_COUNT = "Count";

    private final UUID uuid;
    private IItemList<IAEItemStack> items;

    public DiskCellStorage(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUUID() {
        return uuid;
    }

    public IItemList<IAEItemStack> getItems() {
        if (items == null) {
            items = getChannel().createList();
        }
        return items;
    }

    public boolean isEmpty() {
        if (items == null) {
            return true;
        }
        for (IAEItemStack stack : items) {
            if (stack.getStackSize() > 0) {
                return false;
            }
        }
        return true;
    }

    public long getStoredItemCount() {
        if (items == null) {
            return 0;
        }
        long total = 0;
        for (IAEItemStack stack : items) {
            total += stack.getStackSize();
        }
        return total;
    }

    public int getStoredItemTypes() {
        if (items == null) {
            return 0;
        }
        int count = 0;
        for (IAEItemStack stack : items) {
            if (stack.getStackSize() > 0) {
                count++;
            }
        }
        return count;
    }

    private static IItemStorageChannel getChannel() {
        return AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
    }

    // ------------------------------------------------------------------
    // NBT (DiskStorageManager side)
    // ------------------------------------------------------------------

    public NBTTagCompound writeToNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        if (items != null) {
            for (IAEItemStack stack : items) {
                if (stack.getStackSize() <= 0) {
                    continue;
                }
                NBTTagCompound entry = new NBTTagCompound();

                ItemStack template = stack.getDefinition().copy();
                template.setCount(1);
                NBTTagCompound itemTag = new NBTTagCompound();
                template.writeToNBT(itemTag);

                entry.setTag(TAG_ITEM, itemTag);
                entry.setLong(TAG_COUNT, stack.getStackSize());
                list.appendTag(entry);
            }
        }
        tag.setTag(TAG_ITEMS, list);
        return tag;
    }

    public static DiskCellStorage readFromNBT(UUID uuid, NBTTagCompound tag) {
        DiskCellStorage storage = new DiskCellStorage(uuid);
        NBTTagList list = tag.getTagList(TAG_ITEMS, 10); // 10 = NBTTagCompound
        IItemList<IAEItemStack> items = getChannel().createList();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            ItemStack template = new ItemStack(entry.getCompoundTag(TAG_ITEM));
            long count = entry.getLong(TAG_COUNT);
            if (!template.isEmpty() && count > 0) {
                IAEItemStack stack = getChannel().createStack(template);
                if (stack != null) {
                    stack.setStackSize(count);
                    items.add(stack);
                }
            }
        }
        storage.items = items;
        return storage;
    }
}