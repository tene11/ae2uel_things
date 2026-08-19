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
 * 1枚のDISKが実際に保持している中身(タイプ×個数のリスト)を表すデータホルダー。
 *
 * このクラス自体はItemStackやNBTの読み書きタイミングを一切知らない。
 * 「今メモリ上にどんなアイテムが何個入っているか」だけを保持し、
 * 永続化(いつ・どこに書くか)は {@link DiskStorageManager} 側の責務とする。
 *
 * 修正メモ: 当初 appeng.util.item.AEItemStack.loadItemStackFromNBT を使う予定だったが、
 * AE2UELには存在しなかった(コンパイルエラーで確認)。かわりに vanilla の
 * ItemStack#writeToNBT / new ItemStack(NBTTagCompound) だけで自前シリアライズしている。
 * ItemStackのCountフィールドはbyte(最大127)しかないため、実際の個数は
 * "Count"という別のlongフィールドに持たせ、ItemStack側のCountは常に1に固定している。
 *
 * 修正メモ2: getItemStack()という名前のメソッドは存在しなかった(IntelliJのStructure panelで確認)。
 * appeng.api.storage.data.IAEItemStack には代わりに getDefinition(): ItemStack があり、
 * これがアイテムの種類(Item+meta+NBT)だけを表す基準ItemStackを返す。個数は別途
 * こちらで管理している "Count" タグ側で持つので、getDefinition()の戻り値のCountは
 * copy()後にsetCount(1)で上書きして無視している。
 */
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

    /**
     * 中身が空かどうか。IItemList自体は0個のエントリを残したまま保持することがあるため、
     * リストが空かどうかではなく、stackSize>0のエントリが1つでもあるかで判定する。
     */
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

    /** 格納されている全タイプの合計アイテム数 (1item=1byteモデルでの使用byte数と一致) */
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

    /** 格納されているタイプ数 (0個になったが未クリーンアップのエントリは数えない) */
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
    // NBT (DiskStorageManager 側から呼ばれる)
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
                template.setCount(1); // 実個数はCountタグ(long)側で持つ
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