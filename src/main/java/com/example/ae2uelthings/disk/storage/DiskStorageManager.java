package com.example.ae2uelthings.disk.storage;

import com.example.ae2uelthings.ExampleMod;
import com.example.ae2uelthings.Tags;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.DimensionManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


public class DiskStorageManager extends WorldSavedData {

    private static final String DATA_NAME = Tags.MOD_ID + "_disk_storage";

    /** バックアップファイルの拡張子(データファイル名の後ろに付ける)。 */
    private static final String BACKUP_SUFFIX = ".bak";


    private static DiskStorageManager cachedInstance = new DiskStorageManager(DATA_NAME);

    private final Map<UUID, DiskCellStorage> disks = new HashMap<>();
    private final Map<UUID, DiskFluidCellStorage> fluidDisks = new HashMap<>();

    /**
     * 読み込みに失敗したエントリの生NBT(アイテム版/液体版)。
     * 次回保存時にそのまま書き戻し、読めなかったDISKのデータがファイルから消えないようにする。
     */
    private NBTTagList unreadableDisks = new NBTTagList();
    private NBTTagList unreadableFluidDisks = new NBTTagList();

    /**
     * 読み込めなかったエントリのUUIDキー({@link #normalizeKey}済み)。セル側で「このDISKは
     * 読めなかった」と判定し、投入・分解をロックするために使う。
     */
    private final Set<String> unreadableDiskKeys = new HashSet<>();
    private final Set<String> unreadableFluidDiskKeys = new HashSet<>();

    /** ワールドのデータに紐付いた本物のインスタンスか(refresh()前のダミーはfalse)。 */
    private boolean loaded;

    /**
     * {@link #readFromNBT} を最後まで実行できたか。データファイルが存在するのにfalseのままなら、
     * ファイル全体の読み込み(解凍・NBT解析)に失敗している。
     */
    private boolean readCompleted;

    /**
     * データファイル全体の読み込みに失敗したか。trueの間は全DISKをロックし(投入・取出・分解不可)、
     * {@link #isDirty()} を常にfalseにして、壊れたファイルを空のデータで上書きしないようにする。
     */
    private boolean loadFailed;

    /** このインスタンスが保存されるデータファイル(.dat)。ダミーや特定できなかった場合はnull。 */
    private File dataFile;

    /** 直近の {@link #writeToNBT} で書き出したデータ。ワールド保存後に.bakへ書き出してnullに戻す。 */
    private NBTTagCompound pendingBackup;

    /** 同じ問題のログを1回だけ出すための記録(ワールドを閉じるとリセットされる)。 */
    private static final Set<String> REPORTED_KEYS = new HashSet<>();

    public DiskStorageManager(String name) {
        super(name);
    }

    // ------------------------------------------------------------------
    // static
    // ------------------------------------------------------------------


    public static DiskStorageManager getCached() {
        return cachedInstance;
    }

    /**
     * 修正メモ(データファイル破損時の全消失対策): 1.12.2の MapStorage#getOrLoadData は、
     * ファイルの解凍・NBT解析で出た例外を printStackTrace で握りつぶし、中身が空のインスタンスを
     * そのまま返す。以前はこれを正常な読み込みとして扱っていたため、各セルが「データが無い」と
     * 判定してUUIDを外し、さらに次の保存で壊れたファイルが空のデータで上書きされていた
     * (バックアップから戻してもセル側のUUIDが消えていて復旧不能)。
     * ファイルが存在するのに {@link #readFromNBT} が最後まで実行されなかった場合を読み込み失敗とみなし、
     * 全DISKをロックしたうえでファイルへの保存を止める。管理者が.bakから戻して再起動すれば元に戻る。
     */
    public static DiskStorageManager refresh() {
        WorldServer overworld = DimensionManager.getWorld(0);
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
        // 読み込み失敗の判定はワールド読み込み時の1回だけ行う(recoverコマンド等からの再呼び出しでは
        // 判定し直さない。新規ワールドで後からファイルが作られた場合に誤検知しないため)。
        if (!instance.loaded) {
            instance.dataFile = resolveDataFile(overworld);
            boolean fileExists = instance.dataFile != null && instance.dataFile.isFile();
            if (fileExists && !instance.readCompleted) {
                instance.loadFailed = true;
                ExampleMod.LOGGER.error(
                        "[{}] DISKデータファイルの読み込みに失敗しました: {}\n"
                                + "データ保護のため、全DISKをロックし(投入・取出・分解不可)、このファイルへの保存を停止します。\n"
                                + "サーバーを停止し、{} があればそれを {} という名前で置き換えてから再起動してください"
                                + "(.bak は最後に正常に保存できた時点のデータです)。",
                        Tags.MOD_ID, instance.dataFile, backupFileOf(instance.dataFile).getName(),
                        instance.dataFile.getName());
            } else if (fileExists) {
                // 正常に読めたファイルは、そのまま最新の正常なバックアップとして残しておく
                instance.copyDataFileToBackup();
            }
            instance.loaded = true;
            ExampleMod.LOGGER.info(
                    "[{}] DiskStorageManager.refresh(): {} (disks={}, fluidDisks={})",
                    Tags.MOD_ID,
                    instance.loadFailed ? "読み込み失敗(ロック中)" : freshlyCreated ? "新規作成" : "既存データを読み込み",
                    instance.disks.size(),
                    instance.fluidDisks.size());
        }
        cachedInstance = instance;
        return instance;
    }

    public static void reset() {
        cachedInstance = new DiskStorageManager(DATA_NAME);
        REPORTED_KEYS.clear();
    }

    /**
     * オーバーワールドのperWorldStorageが使うデータファイルの場所。
     * 1.12.2 Forgeの perWorldStorage は WorldSpecificSaveHandler#getMapFileFromName で
     * {@code <WorldServer#getChunkSaveLocation()>/data/<名前>.dat} に保存する
     * (オーバーワールドではワールドフォルダ直下の data/。Forgeソースで確認済み)。
     */
    private static File resolveDataFile(WorldServer overworld) {
        try {
            return new File(new File(overworld.getChunkSaveLocation(), "data"), DATA_NAME + ".dat");
        } catch (RuntimeException e) {
            ExampleMod.LOGGER.warn("[{}] DISKデータファイルの場所を特定できませんでした。読み込み失敗の検知とバックアップは無効になります。",
                    Tags.MOD_ID, e);
            return null;
        }
    }

    private static File backupFileOf(File dataFile) {
        return new File(dataFile.getPath() + BACKUP_SUFFIX);
    }

    /** UUID文字列を比較用のキーに正規化する(UUIDとして読めれば標準形式、読めなければそのまま)。 */
    public static String normalizeKey(String rawUuid) {
        if (rawUuid == null) {
            return "";
        }
        try {
            return UUID.fromString(rawUuid).toString();
        } catch (IllegalArgumentException e) {
            return rawUuid;
        }
    }

    /**
     * keyに対する問題をまだログに出していなければtrueを返し、出したものとして記録する。
     * ME Driveはセルのハンドラを何度も作り直すため、同じエラーログが大量に出るのを防ぐ。
     */
    public static boolean shouldReport(String key) {
        return REPORTED_KEYS.add(key);
    }

    /** ワールドのデータを読み込んだ本物のインスタンスか(クライアント側やワールド読み込み前のダミーはfalse)。 */
    public boolean isLoaded() {
        return loaded;
    }

    /** データファイル全体の読み込みに失敗し、全DISKをロックしている状態か。 */
    public boolean isLoadFailed() {
        return loadFailed;
    }

    /** このUUIDのアイテムDISKが、読み込みに失敗して生NBTのまま保持されているか。 */
    public boolean isUnreadableDisk(String rawUuid) {
        return unreadableDiskKeys.contains(normalizeKey(rawUuid));
    }

    /** このUUIDの液体DISKが、読み込みに失敗して生NBTのまま保持されているか。 */
    public boolean isUnreadableFluidDisk(String rawUuid) {
        return unreadableFluidDiskKeys.contains(normalizeKey(rawUuid));
    }


    /** このUUIDのアイテムDISKのデータ。無ければnull(新しく作らない)。 */
    public DiskCellStorage getDisk(UUID uuid) {
        return disks.get(uuid);
    }

    public DiskCellStorage getOrCreateDisk(UUID uuid) {
        return disks.computeIfAbsent(uuid, DiskCellStorage::new);
    }

    /** 中身のある(保存対象になる)アイテムDISKのデータがあるか。 */
    public boolean hasDisk(UUID uuid) {
        DiskCellStorage disk = disks.get(uuid);
        return disk != null && !disk.isEmpty();
    }

    public void removeDisk(UUID uuid) {
        disks.remove(uuid);
        markDirty();
    }

    public void updateDisk(DiskCellStorage storage) {
        disks.put(storage.getUUID(), storage);
        markDirty();
    }


    /** このUUIDの液体DISKのデータ。無ければnull(新しく作らない)。 */
    public DiskFluidCellStorage getFluidDisk(UUID uuid) {
        return fluidDisks.get(uuid);
    }

    public DiskFluidCellStorage getOrCreateFluidDisk(UUID uuid) {
        return fluidDisks.computeIfAbsent(uuid, DiskFluidCellStorage::new);
    }

    /** 中身のある(保存対象になる)液体DISKのデータがあるか。 */
    public boolean hasFluidDisk(UUID uuid) {
        DiskFluidCellStorage disk = fluidDisks.get(uuid);
        return disk != null && !disk.isEmpty();
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
    // バックアップ
    // ------------------------------------------------------------------

    /**
     * ワールド保存の完了後(WorldEvent.Save)に呼ぶ。直近の {@link #writeToNBT} で書き出したデータを
     * .bak に書き出す。バニラの MapStorage#saveData は一時ファイルを使わず直接上書きするため、
     * 保存中のクラッシュ・電源断で .dat が壊れることがある。.bak は一時ファイルに書いてから
     * 置き換えるので、常に「最後に正常に書き出せたデータ」が残る。
     * データに変更が無かった保存では何もしない。
     */
    public void writeBackupIfPending() {
        NBTTagCompound data = pendingBackup;
        pendingBackup = null;
        if (data == null || loadFailed || dataFile == null) {
            return;
        }
        File backup = backupFileOf(dataFile);
        File tmp = new File(backup.getPath() + ".tmp");
        try {
            NBTTagCompound root = new NBTTagCompound();
            root.setTag("data", data); // MapStorage#saveData と同じ形式
            try (OutputStream out = new FileOutputStream(tmp)) {
                CompressedStreamTools.writeCompressed(root, out);
            }
            replaceWith(tmp, backup);
        } catch (IOException | RuntimeException e) {
            ExampleMod.LOGGER.warn("[{}] DISKデータのバックアップ({})を書き出せませんでした。", Tags.MOD_ID, backup, e);
        }
    }

    /** 読み込みに成功したデータファイルを、そのまま .bak としてコピーしておく。 */
    private void copyDataFileToBackup() {
        File backup = backupFileOf(dataFile);
        File tmp = new File(backup.getPath() + ".tmp");
        try {
            Files.copy(dataFile.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            replaceWith(tmp, backup);
        } catch (IOException | RuntimeException e) {
            ExampleMod.LOGGER.warn("[{}] DISKデータのバックアップ({})を作成できませんでした。", Tags.MOD_ID, backup, e);
        }
    }

    /** tmp で target を置き換える(可能なら原子的に)。 */
    private static void replaceWith(File tmp, File target) throws IOException {
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------
    // WorldSavedData
    // ------------------------------------------------------------------

    /** 読み込みに失敗している間は保存しない(壊れたファイルを空のデータで上書きしないため)。 */
    @Override
    public boolean isDirty() {
        return !loadFailed && super.isDirty();
    }

    /**
     * 修正メモ(1件の破損で残りのDISKが消える問題の対策): 以前はエントリを1件ずつ読む途中で
     * 例外(不正なUUID文字列など)が出ると、そこで読み込み全体が止まっていた。バニラの
     * MapStorageはこの例外を握りつぶして途中まで読んだデータをそのまま使うため、
     * 次の保存でそれ以降のDISKがファイルから消えていた。
     * エントリごとに例外を捕まえ、読めなかったものはログに出したうえで生NBTを保持し、
     * {@link #writeToNBT} で書き戻すようにした(読めるようになれば次回の読み込みで復活する)。
     */
    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        disks.clear();
        unreadableDisks = new NBTTagList();
        unreadableDiskKeys.clear();
        NBTTagList diskList = nbt.getTagList("Disks", 10); // 10 = NBTTagCompound
        for (int i = 0; i < diskList.tagCount(); i++) {
            NBTTagCompound entry = diskList.getCompoundTagAt(i);
            try {
                UUID uuid = UUID.fromString(entry.getString("Uuid"));
                DiskCellStorage disk = DiskCellStorage.readFromNBT(uuid, entry.getCompoundTag("Data"));
                // 読み込み時にエントリを削除した(AE2UEL標準セルと同じ仕様)DISKは、空になっても
                // マップに残す。セル側からは「このセッション中に空になったDISK」に見えるため、
                // AE2UELと同じく通常の空セルに戻る(データ無し扱いにはならない)。
                if (!disk.isEmpty() || disk.getRemovedOnLoad() > 0) {
                    disks.put(uuid, disk);
                }
                if (disk.getRemovedOnLoad() > 0) {
                    // AE2UELが読み込み直後に saveChanges() するのと同じく、次の保存でファイルからも消す
                    markDirty();
                }
            } catch (RuntimeException e) {
                unreadableDisks.appendTag(entry.copy());
                unreadableDiskKeys.add(normalizeKey(entry.getString("Uuid")));
                ExampleMod.LOGGER.error("[{}] DISKデータの読み込みに失敗しました (Uuid=\"{}\")。"
                                + "データは消さずに保持し、次回保存時にそのまま書き戻します。",
                        Tags.MOD_ID, entry.getString("Uuid"), e);
            }
        }

        fluidDisks.clear();
        unreadableFluidDisks = new NBTTagList();
        unreadableFluidDiskKeys.clear();
        NBTTagList fluidDiskList = nbt.getTagList("FluidDisks", 10);
        for (int i = 0; i < fluidDiskList.tagCount(); i++) {
            NBTTagCompound entry = fluidDiskList.getCompoundTagAt(i);
            try {
                UUID uuid = UUID.fromString(entry.getString("Uuid"));
                DiskFluidCellStorage disk = DiskFluidCellStorage.readFromNBT(uuid, entry.getCompoundTag("Data"));
                // 読み込み時にエントリを削除した(AE2UEL標準セルと同じ仕様)DISKは、空になっても
                // マップに残す。セル側からは「このセッション中に空になったDISK」に見えるため、
                // AE2UELと同じく通常の空セルに戻る(データ無し扱いにはならない)。
                if (!disk.isEmpty() || disk.getRemovedOnLoad() > 0) {
                    fluidDisks.put(uuid, disk);
                }
                if (disk.getRemovedOnLoad() > 0) {
                    // AE2UELが読み込み直後に saveChanges() するのと同じく、次の保存でファイルからも消す
                    markDirty();
                }
            } catch (RuntimeException e) {
                unreadableFluidDisks.appendTag(entry.copy());
                unreadableFluidDiskKeys.add(normalizeKey(entry.getString("Uuid")));
                ExampleMod.LOGGER.error("[{}] 液体DISKデータの読み込みに失敗しました (Uuid=\"{}\")。"
                                + "データは消さずに保持し、次回保存時にそのまま書き戻します。",
                        Tags.MOD_ID, entry.getString("Uuid"), e);
            }
        }

        if (unreadableDisks.tagCount() > 0 || unreadableFluidDisks.tagCount() > 0) {
            ExampleMod.LOGGER.warn("[{}] 読み込めなかったDISKデータ: アイテム{}件 / 液体{}件 (保持中)",
                    Tags.MOD_ID, unreadableDisks.tagCount(), unreadableFluidDisks.tagCount());
        }
        readCompleted = true;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        // 読み込めなかったエントリを先に書き戻す(同じUUIDの正常なデータがあれば、
        // 次回読み込み時は後に書いた正常データの方が優先される)
        NBTTagList diskList = new NBTTagList();
        for (int i = 0; i < unreadableDisks.tagCount(); i++) {
            diskList.appendTag(unreadableDisks.getCompoundTagAt(i).copy());
        }
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
        for (int i = 0; i < unreadableFluidDisks.tagCount(); i++) {
            fluidDiskList.appendTag(unreadableFluidDisks.getCompoundTagAt(i).copy());
        }
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

        // ワールド保存完了後に .bak へ書き出すため控えておく(writeBackupIfPending 参照)
        pendingBackup = nbt;
        return nbt;
    }
}
