package com.example.ae2uelthings.disk.storage;

import com.example.ae2uelthings.ExampleMod;
import com.example.ae2uelthings.Tags;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.DimensionManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


public class DiskStorageManager extends WorldSavedData {

    private static final String DATA_NAME = Tags.MOD_ID + "_disk_storage";


    private static DiskStorageManager cachedInstance = new DiskStorageManager(DATA_NAME);

    private final Map<UUID, DiskCellStorage> disks = new HashMap<>();
    private final Map<UUID, DiskFluidCellStorage> fluidDisks = new HashMap<>();

    public DiskStorageManager(String name) {
        super(name);
    }

    // ------------------------------------------------------------------
    // static
    // ------------------------------------------------------------------


    public static DiskStorageManager getCached() {
        return cachedInstance;
    }

    public static DiskStorageManager refresh() {
        World overworld = DimensionManager.getWorld(0);
        if (overworld == null) {
            ExampleMod.LOGGER.warn("[{}] DiskStorageManager.refresh(): Could not get overworld", Tags.MOD_ID);
            return cachedInstance;
        }
        MapStorage storage = overworld.getPerWorldStorage();
        DiskStorageManager instance = (DiskStorageManager) storage.getOrLoadData(DiskStorageManager.class, DATA_NAME);
        boolean freshlyCreated = instance == null;
        if (instance == null) {
            instance = new DiskStorageManager(DATA_NAME);
            storage.setData(DATA_NAME, instance);
        }
        cachedInstance = instance;
        ExampleMod.LOGGER.info(
                "[{}] DiskStorageManager.refresh(): {} (disks={}, fluidDisks={})",
                Tags.MOD_ID,
                freshlyCreated ? " " : " ",
                instance.disks.size(),
                instance.fluidDisks.size());
        return instance;
    }

    public static void reset() {
        cachedInstance = new DiskStorageManager(DATA_NAME);
    }


    public DiskCellStorage getOrCreateDisk(UUID uuid) {
        return disks.computeIfAbsent(uuid, DiskCellStorage::new);
    }

    public DiskCellStorage getDisk(UUID uuid) {
        return disks.get(uuid);
    }

    public boolean hasDisk(UUID uuid) {
        return disks.containsKey(uuid);
    }

    public void removeDisk(UUID uuid) {
        disks.remove(uuid);
        markDirty();
    }

    public void updateDisk(DiskCellStorage storage) {
        disks.put(storage.getUUID(), storage);
        markDirty();
    }



    public DiskFluidCellStorage getOrCreateFluidDisk(UUID uuid) {
        return fluidDisks.computeIfAbsent(uuid, DiskFluidCellStorage::new);
    }

    public DiskFluidCellStorage getFluidDisk(UUID uuid) {
        return fluidDisks.get(uuid);
    }

    public boolean hasFluidDisk(UUID uuid) {
        return fluidDisks.containsKey(uuid);
    }

    public void removeFluidDisk(UUID uuid) {
        fluidDisks.remove(uuid);
        markDirty();
    }

    public void updateFluidDisk(DiskFluidCellStorage storage) {
        fluidDisks.put(storage.getUUID(), storage);
        markDirty();
    }

    // ------------------------------------------------------------------
    // WorldSavedData
    // ------------------------------------------------------------------

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        disks.clear();
        NBTTagList diskList = nbt.getTagList("Disks", 10); // 10 = NBTTagCompound
        for (int i = 0; i < diskList.tagCount(); i++) {
            NBTTagCompound entry = diskList.getCompoundTagAt(i);
            UUID uuid = UUID.fromString(entry.getString("Uuid"));
            DiskCellStorage disk = DiskCellStorage.readFromNBT(uuid, entry.getCompoundTag("Data"));
            if (!disk.isEmpty()) {
                disks.put(uuid, disk);
            }
        }

        fluidDisks.clear();
        NBTTagList fluidDiskList = nbt.getTagList("FluidDisks", 10);
        for (int i = 0; i < fluidDiskList.tagCount(); i++) {
            NBTTagCompound entry = fluidDiskList.getCompoundTagAt(i);
            UUID uuid = UUID.fromString(entry.getString("Uuid"));
            DiskFluidCellStorage disk = DiskFluidCellStorage.readFromNBT(uuid, entry.getCompoundTag("Data"));
            if (!disk.isEmpty()) {
                fluidDisks.put(uuid, disk);
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList diskList = new NBTTagList();
        for (Map.Entry<UUID, DiskCellStorage> e : disks.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("Uuid", e.getKey().toString());
            entry.setTag("Data", e.getValue().writeToNBT());
            diskList.appendTag(entry);
        }
        nbt.setTag("Disks", diskList);

        NBTTagList fluidDiskList = new NBTTagList();
        for (Map.Entry<UUID, DiskFluidCellStorage> e : fluidDisks.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("Uuid", e.getKey().toString());
            entry.setTag("Data", e.getValue().writeToNBT());
            fluidDiskList.appendTag(entry);
        }
        nbt.setTag("FluidDisks", fluidDiskList);

        return nbt;
    }
}