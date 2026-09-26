package com.example.ae2uelthings.disk.storage;

import appeng.api.AEApi;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.util.UUID;

/**
 * DISKセル1枚ぶんの実データ(アイテム版)。{@link DiskStorageManager} がUUIDをキーに保持する。
 *
 * <p>修正メモ(負荷対策): 以前は {@link #getStoredItemCount()} / {@link #getStoredItemTypes()} /
 * {@link #isEmpty()} が呼ばれるたびに全エントリを走査していた。これらは
 * DiskCellInventoryHandler#getUsedBytes 経由で、1回の投入ごとに複数回・ドライブの状態更新でも
 * 呼ばれるため、種類数の多いDISKほど投入/取出のたびに O(種類数) の負荷がかかっていた。
 * 合計個数と格納タイプ数をこのクラス内でキャッシュし、O(1)で返すようにした。</p>
 *
 * <p><b>注意:</b> キャッシュを正しく保つため、中身の増減は必ず {@link #insert} / {@link #extract}
 * 経由で行うこと。{@link #getItems()} は読み取り(findPrecise・走査)専用で、返ってきたリストや
 * その要素のstackSizeを直接書き換えてはいけない。</p>
 */
public class DiskCellStorage {

    private static final String TAG_ITEMS = "Items";
    private static final String TAG_ITEM = "Item";
    private static final String TAG_COUNT = "Count";

    private final UUID uuid;
    private IItemList<IAEItemStack> items;

    /** 格納中の合計個数(キャッシュ) */
    private long storedCount;
    /** stackSize > 0 のエントリ数=格納タイプ数(キャッシュ) */
    private int storedTypes;

    public DiskCellStorage(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUUID() {
        return uuid;
    }

    /** 読み取り専用。中身の増減には {@link #insert} / {@link #extract} を使うこと。 */
    public IItemList<IAEItemStack> getItems() {
        if (items == null) {
            items = getChannel().createList();
        }
        return items;
    }

    /**
     * 指定アイテムを amount 個追加する。既存エントリ(0個に減った残留エントリを含む)があれば加算し、
     * 無ければ新規エントリを作る。
     */
    public void insert(IAEItemStack input, long amount) {
        if (amount <= 0) {
            return;
        }
        IItemList<IAEItemStack> list = getItems();
        IAEItemStack existing = list.findPrecise(input);
        if (existing != null) {
            if (existing.getStackSize() <= 0) {
                storedTypes++;
            }
            existing.incStackSize(amount);
        } else {
            IAEItemStack toStore = input.copy();
            toStore.setStackSize(amount);
            list.add(toStore);
            storedTypes++;
        }
        storedCount += amount;
    }

    /**
     * {@link #getItems()} の findPrecise で取得した既存エントリから amount 個減らす。
     * 0個になったエントリはリストに残るが、AE2UELのItemListは走査時に自動で取り除く。
     */
    public void extract(IAEItemStack existing, long amount) {
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

    /** キャッシュを実データから数え直す(NBT読み込み直後に使う)。 */
    private void recalculate() {
        storedCount = 0;
        storedTypes = 0;
        if (items == null) {
            return;
        }
        for (IAEItemStack stack : items) {
            if (stack.getStackSize() > 0) {
                storedCount += stack.getStackSize();
                storedTypes++;
            }
        }
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
        storage.recalculate();
        return storage;
    }
}
