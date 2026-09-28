package com.example.ae2uelthings.disk.storage;

import appeng.api.AEApi;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.core.AEConfig;
import com.example.ae2uelthings.ExampleMod;
import com.example.ae2uelthings.Tags;
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
 * <p>読み込めないエントリの扱い(AE2UEL標準セルと同じ仕様): AE2UELの
 * BasicCellInventory#loadCellItem / AbstractCellInventory#loadCellItems に合わせている。
 * <ul>
 *   <li>追加元のmodが外れた等で、この環境に存在しないアイテムのエントリは、警告ログを出して削除する
 *       (容量・タイプ数からも外れ、次の保存でファイルからも消える)。</li>
 *   <li>読み込み中に例外が出たエントリは、AE2UELの設定 {@code removeCrashingItemsOnLoad} がtrueなら
 *       警告ログを出して削除し、falseなら例外をそのまま投げる(AE2UELはここでクラッシュする。
 *       このmodでは {@link DiskStorageManager#readFromNBT} がそのDISKを読み込み失敗として保持・ロックする)。</li>
 * </ul>
 * 削除があったDISKは {@link #getRemovedOnLoad()} が1以上になり、マネージャーが保存し直す。</p>
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

    /** 読み込み時に削除したエントリ数(AE2UEL標準セルと同じく、読めないエントリは削除する) */
    private int removedOnLoad;

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

    /** 読み込み時に削除したエントリ数(0なら削除なし)。 */
    public int getRemovedOnLoad() {
        return removedOnLoad;
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
            long count = entry.getLong(TAG_COUNT);
            if (count <= 0) {
                continue;
            }
            // AE2UEL BasicCellInventory#loadCellItem と同じ扱い
            IAEItemStack stack;
            try {
                // 未登録のアイテムは空のItemStack(air)として読み込まれる
                ItemStack template = new ItemStack(entry.getCompoundTag(TAG_ITEM));
                stack = template.isEmpty() ? null : getChannel().createStack(template);
                if (stack == null) {
                    ExampleMod.LOGGER.warn("[{}] DISK UUID={} からアイテム {} を削除します(この環境に存在しないアイテムのため)。",
                            Tags.MOD_ID, uuid, entry);
                    storage.removedOnLoad++;
                    continue;
                }
            } catch (Throwable ex) {
                if (AEConfig.instance().isRemoveCrashingItemsOnLoad()) {
                    ExampleMod.LOGGER.warn("[{}] DISK UUID={} からアイテム {} を削除します(読み込み中にエラーが発生したため)。",
                            Tags.MOD_ID, uuid, entry, ex);
                    storage.removedOnLoad++;
                    continue;
                }
                throw ex;
            }
            stack.setStackSize(count);
            items.add(stack);
        }
        storage.items = items;
        storage.recalculate();
        return storage;
    }
}
