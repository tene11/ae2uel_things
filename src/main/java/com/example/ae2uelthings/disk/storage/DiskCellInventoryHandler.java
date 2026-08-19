package com.example.ae2uelthings.disk.storage;

import appeng.api.AEApi;
import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.config.IncludeExclude;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import com.example.ae2uelthings.ExampleMod;
import com.example.ae2uelthings.Tags;
import com.example.ae2uelthings.disk.ItemDiskCell;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.items.IItemHandler;

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
 * 固定値を返すのではなく、ItemDiskCell(IStorageCell実装済み)側の既存実装に
 * 委譲している。ItemDiskCellはFuzzyModeをNBTに永続化する処理を既に持っているため、
 * ここで別々の実装を持つと二重管理になってしまうため。
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
    // ICellInventoryHandler<IAEItemStack>
    // ------------------------------------------------------------------

    @Override
    public IAEItemStack injectItems(IAEItemStack input, Actionable mode, IActionSource src) {
        if (input == null || input.getStackSize() <= 0) return input;

        IItemList<IAEItemStack> items = storage != null ? storage.getItems() : null;
        IAEItemStack existing = items != null ? items.findPrecise(input) : null;
        long freeBytes = usableBytes - getStoredItemCount();

        if (existing != null) {
            long toAccept = Math.min(input.getStackSize(), Math.max(0, freeBytes));
            if (toAccept <= 0) return input;

            if (mode == Actionable.MODULATE) {
                existing.incStackSize(toAccept);
                markDirty();
            }
            if (toAccept >= input.getStackSize()) return null;
            IAEItemStack remainder = input.copy();
            remainder.decStackSize(toAccept);
            return remainder;
        } else {
            long acceptable = freeBytes - bytesPerType;
            long toAccept = Math.min(input.getStackSize(), Math.max(0, acceptable));
            if (toAccept <= 0) return input;

            if (mode == Actionable.MODULATE) {
                IAEItemStack toStore = input.copy();
                toStore.setStackSize(toAccept);
                getOrCreateStorage().getItems().add(toStore);
                markDirty();
            }
            if (toAccept >= input.getStackSize()) return null;
            IAEItemStack remainder = input.copy();
            remainder.decStackSize(toAccept);
            return remainder;
        }
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
        // ItemDiskCellは現状タイプフィルター機能を持たない設計
        // (getConfigInventoryは常に0スロットの空インベントリを返す)ため、常にfalse。
        // 将来フィルター機能を実装する場合は、getConfigInventory().getSlots() > 0 等で判定する。
        return false;
    }

    @Override
    public boolean isFuzzy() {
        // ItemDiskCellはアップグレードカード機構(getUpgradesInventory)を持たない設計
        // (常に0スロット)のため、ファジーカードが刺さる余地がなく常にfalse。
        return false;
    }

    @Override
    public IncludeExclude getIncludeExcludeMode() {
        return IncludeExclude.WHITELIST;
    }

    @Override
    public ItemStack getItemStack() {
        return cellItem;
    }

    @Override
    public double getIdleDrain() {
        return ((ItemDiskCell) cellItem.getItem()).getIdleDrain();
    }

    @Override
    public FuzzyMode getFuzzyMode() {
        return ((ItemDiskCell) cellItem.getItem()).getFuzzyMode(cellItem);
    }

    @Override
    public IItemHandler getConfigInventory() {
        return ((ItemDiskCell) cellItem.getItem()).getConfigInventory(cellItem);
    }

    @Override
    public IItemHandler getUpgradesInventory() {
        return ((ItemDiskCell) cellItem.getItem()).getUpgradesInventory(cellItem);
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
        return Math.max(0, usableBytes - getStoredItemCount());
    }

    @Override
    public long getUsedBytes() {
        return getStoredItemCount();
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