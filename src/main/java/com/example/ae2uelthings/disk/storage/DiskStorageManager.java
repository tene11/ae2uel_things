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

/**
 * サーバー全体で1つだけ存在する、全DISKの中身を保持する永続化ストレージ。
 *
 * 元々は DiskTier.java 内のstaticネストクラスだったが、他クラス(DiskCellInventoryHandler等)
 * から参照する際に毎回 DiskTier. で修飾するかimportし忘れるかの問題が出やすいため、
 * 独立したトップレベルクラスとして切り出した。
 *
 * オーバーワールド(dimension 0)の {@link MapStorage} に単一インスタンスとして保存する。
 * DISKセル自身のItemStack NBTには {@link UUID} 文字列だけを持たせ、実データはここに
 * 集約することで、ME Drive/端末のGUI表示時に発生するインベントリ同期パケットに
 * 重いNBTが乗るのを避ける設計 (AE2Things本家・本スレッドで確認した設計を踏襲)。
 *
 * {@link DiskCellInventoryHandler} 等の呼び出し側はWorld参照を持たないため、
 * このクラスは {@link #getCached()} で「直近に解決されたインスタンス」を無条件に返す。
 * 実際の解決は {@link #refresh()} が担当し、これは {@link DiskStorageEventHandler} から
 * ワールドロード時に呼ばれる。クライアント単体実行時はrefresh()が一度も呼ばれないため、
 * getCached()は起動時に生成された空のプレースホルダーを返し続け、安全にフォールバックする。
 */
public class DiskStorageManager extends WorldSavedData {

    private static final String DATA_NAME = Tags.MOD_ID + "_disk_storage";

    /** クライアント単体実行時や、まだリフレッシュされていない起動直後のための空プレースホルダー */
    private static DiskStorageManager cachedInstance = new DiskStorageManager(DATA_NAME);

    private final Map<UUID, DiskCellStorage> disks = new HashMap<>();
    private final Map<UUID, DiskFluidCellStorage> fluidDisks = new HashMap<>();

    public DiskStorageManager(String name) {
        super(name);
    }

    // ------------------------------------------------------------------
    // static アクセサ
    // ------------------------------------------------------------------

    /** 現在キャッシュされているインスタンスを返す。World参照なしで任意の場所から安全に呼べる。 */
    public static DiskStorageManager getCached() {
        return cachedInstance;
    }

    /**
     * オーバーワールドのMapStorageから実インスタンスを取得(無ければ生成)し、staticキャッシュを更新する。
     * サーバースレッドかつオーバーワールドがロード済みのタイミングでのみ呼ぶこと
     * (呼び出し元は {@link DiskStorageEventHandler} を想定)。
     *
     * 要ローカル検証: World#getPerWorldStorage() / MapStorage#getOrLoadData の
     * シグネチャはForgeバージョンに依存する場合がある。CleanroomMC環境で
     * コンパイルエラーになる場合は世界オブジェクトの取得元 (world.mapStorage 等)を確認すること。
     */
    public static DiskStorageManager refresh() {
        World overworld = DimensionManager.getWorld(0);
        if (overworld == null) {
            ExampleMod.LOGGER.warn("[{}] DiskStorageManager.refresh(): overworldがまだ取得できません", Tags.MOD_ID);
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
                freshlyCreated ? "新規作成(セーブデータ未検出)" : "セーブデータからロード",
                instance.disks.size(),
                instance.fluidDisks.size());
        return instance;
    }

    /** サーバー停止/ワールドアンロード時、古いWorldへの参照を握り続けないようにリセットする */
    public static void reset() {
        cachedInstance = new DiskStorageManager(DATA_NAME);
    }

    // ------------------------------------------------------------------
    // ディスク単位のアクセス
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // ディスク単位のアクセス (フルイド版)
    // ------------------------------------------------------------------

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