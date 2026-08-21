package com.example.ae2uelthings.disk;

import com.example.ae2uelthings.ExampleMod;
import com.example.ae2uelthings.Tags;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;

/**
 * DISKセルの標準4ティア(アイテム版+液体版)+ 拡張ティア + 理論上の最大ティアのアイテム登録 +
 * アイテムモデル登録。
 *
 * 修正メモ1(拡張ティア対応): NAE2("Crafting Storage"として256k/1024k/4096k/16384kの
 * ラインナップを自身で持つ)がロードされている場合のみ、DiskTierの拡張ティア
 * (TIER_256K〜TIER_16384K)のアイテムを追加登録するようにした。
 * 判定は {@link ModCompat#isNae2Loaded()} 経由。
 *
 * 修正メモ2(理論上の最大ティア): CrazyAE("New storage fluids & items cells (256KB-2GB)"、
 * すなわちほぼintの上限まで対応)を参考にDiskTier.TIER_MAXを用意した。ただし特定MODの
 * 検出とは無関係に常に登録される(NAE2系の拡張ティアとは扱いが異なる点に注意)。
 *
 * どちらも「相手MOD側からこちらの上位ティアが認識される」という意味の対応ではなく、
 * 「相手MODが入っている大容量志向の環境向けに、ae2uelthings自身の上位ティアを
 * 追加で解放する」という位置付け。
 *
 * 修正メモ3(起動時バグ対策: NAE2検出タイミング): 以前は ALL_CELLS/ALL_FLUID_CELLS を
 * static final フィールドとしてクラス初期化時に(=buildAllCells()/buildAllFluidCells()内で
 * ModCompat.isNae2Loaded()を呼んだ時点で)確定させていた。しかしこのクラスは
 * {@code @Mod.EventBusSubscriber} が付いているため、Forgeのアノテーションスキャンにより
 * ae2uelthings自身の Construction フェーズ中にロード(=static初期化)される。
 * ae2uelthingsとnae2の間には明示的なロード順序指定が無いため、環境によっては
 * nae2がまだConstructionを終えていない(=Loader.isModLoaded("nae2")がまだ正しい値を
 * 返さない)タイミングでこのクラスが読み込まれ、NAE2が実際には入っているのに
 * 拡張ティアが登録されない、という起動順序依存のバグが起こり得た。
 * 対策として、ALL_CELLS/ALL_FLUID_CELLSの構築(=isNae2Loaded()の判定含む)を
 * registerItems(RegistryEvent.Register&lt;Item&gt;) の中に移した。このイベントは
 * 全MODのConstructionが完了した後にまとめて発火することがFrogeのライフサイクルとして
 * 保証されているため、呼び出しタイミングを気にする必要がなくなる。
 *
 * 未対応の要TODO:
 * - 拡張ティア・TIER_MAXのテクスチャ/モデルjsonが未作成
 *   (現状は登録されるとmissing texture表示になる)。
 * - 拡張ティア・TIER_MAXのクラフトレシピが未定義(componentMetaが-1のため、
 *   通常レシピでは作れない)。
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class ModDiskItems {

    public static final ItemDiskCell DISK_CELL_1K = new ItemDiskCell(DiskTier.TIER_1K);
    public static final ItemDiskCell DISK_CELL_4K = new ItemDiskCell(DiskTier.TIER_4K);
    public static final ItemDiskCell DISK_CELL_16K = new ItemDiskCell(DiskTier.TIER_16K);
    public static final ItemDiskCell DISK_CELL_64K = new ItemDiskCell(DiskTier.TIER_64K);
    public static final ItemDiskHousing DISK_HOUSING = new ItemDiskHousing();

    // ---- 拡張ティア(NAE2検出時のみ実際に登録される) ----
    public static final ItemDiskCell DISK_CELL_256K = new ItemDiskCell(DiskTier.TIER_256K);
    public static final ItemDiskCell DISK_CELL_1024K = new ItemDiskCell(DiskTier.TIER_1024K);
    public static final ItemDiskCell DISK_CELL_4096K = new ItemDiskCell(DiskTier.TIER_4096K);
    public static final ItemDiskCell DISK_CELL_16384K = new ItemDiskCell(DiskTier.TIER_16384K);

    public static final ItemDiskFluidCell DISK_CELL_FLUID_1K = new ItemDiskFluidCell(DiskTier.TIER_1K);
    public static final ItemDiskFluidCell DISK_CELL_FLUID_4K = new ItemDiskFluidCell(DiskTier.TIER_4K);
    public static final ItemDiskFluidCell DISK_CELL_FLUID_16K = new ItemDiskFluidCell(DiskTier.TIER_16K);
    public static final ItemDiskFluidCell DISK_CELL_FLUID_64K = new ItemDiskFluidCell(DiskTier.TIER_64K);

    // ---- 拡張ティア・液体版(NAE2検出時のみ実際に登録される) ----
    public static final ItemDiskFluidCell DISK_CELL_FLUID_256K = new ItemDiskFluidCell(DiskTier.TIER_256K);
    public static final ItemDiskFluidCell DISK_CELL_FLUID_1024K = new ItemDiskFluidCell(DiskTier.TIER_1024K);
    public static final ItemDiskFluidCell DISK_CELL_FLUID_4096K = new ItemDiskFluidCell(DiskTier.TIER_4096K);
    public static final ItemDiskFluidCell DISK_CELL_FLUID_16384K = new ItemDiskFluidCell(DiskTier.TIER_16384K);

    // ---- 理論上の最大ティア(特定MODの検出とは無関係に常に登録される) ----
    public static final ItemDiskCell DISK_CELL_MAX = new ItemDiskCell(DiskTier.TIER_MAX);
    public static final ItemDiskFluidCell DISK_CELL_FLUID_MAX = new ItemDiskFluidCell(DiskTier.TIER_MAX);

    // 修正メモ3参照: もうクラス初期化時には確定させない。registerItems()実行時に
    // 一度だけ構築してここへ格納し、registerModels()側はそれを読むだけにする
    // (registerModelsが発火する ModelRegistryEvent は registerItems の RegistryEvent より
    // 後に来るため、この順序であれば安全)。
    private static ItemDiskCell[] ALL_CELLS;
    private static ItemDiskFluidCell[] ALL_FLUID_CELLS;

    private static ItemDiskCell[] buildAllCells() {
        List<ItemDiskCell> list = new ArrayList<>();
        list.add(DISK_CELL_1K);
        list.add(DISK_CELL_4K);
        list.add(DISK_CELL_16K);
        list.add(DISK_CELL_64K);
        if (ModCompat.isNae2Loaded()) {
            list.add(DISK_CELL_256K);
            list.add(DISK_CELL_1024K);
            list.add(DISK_CELL_4096K);
            list.add(DISK_CELL_16384K);
        }
        list.add(DISK_CELL_MAX);
        return list.toArray(new ItemDiskCell[0]);
    }

    private static ItemDiskFluidCell[] buildAllFluidCells() {
        List<ItemDiskFluidCell> list = new ArrayList<>();
        list.add(DISK_CELL_FLUID_1K);
        list.add(DISK_CELL_FLUID_4K);
        list.add(DISK_CELL_FLUID_16K);
        list.add(DISK_CELL_FLUID_64K);
        if (ModCompat.isNae2Loaded()) {
            list.add(DISK_CELL_FLUID_256K);
            list.add(DISK_CELL_FLUID_1024K);
            list.add(DISK_CELL_FLUID_4096K);
            list.add(DISK_CELL_FLUID_16384K);
        }
        list.add(DISK_CELL_FLUID_MAX);
        return list.toArray(new ItemDiskFluidCell[0]);
    }

    private ModDiskItems() {
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        // NAE2検出の判定はここで初めて行う(全MOD Construction完了後に確定して発火する
        // RegistryEventの中なので、MOD読み込み順序に左右されない)。
        boolean nae2Loaded = ModCompat.isNae2Loaded();
        ExampleMod.LOGGER.info(
                "[{}] registerItems(): ModCompat.isNae2Loaded()={} (拡張ティアの登録可否に使用)",
                Tags.MOD_ID, nae2Loaded);

        ALL_CELLS = buildAllCells();
        ALL_FLUID_CELLS = buildAllFluidCells();

        event.getRegistry().registerAll(ALL_CELLS);
        event.getRegistry().registerAll(ALL_FLUID_CELLS);
        event.getRegistry().register(DISK_HOUSING);
    }

    // モデルjsonは src/main/resources/assets/modid/models/item/disk_cell_XX.json に
    // 置いてあるだけでは表示されないため、ここで明示的にひも付ける。
    // (拡張ティア・maxティアは現状テクスチャ/モデルjson未作成のため、登録された環境では
    //  実際に登録はされるがmissing texture表示になる。要追加対応。)
    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public static void registerModels(ModelRegistryEvent event) {
        for (ItemDiskCell item : ALL_CELLS) {
            ModelLoader.setCustomModelResourceLocation(
                    item, 0,
                    new ModelResourceLocation(item.getRegistryName(), "inventory"));
        }
        for (ItemDiskFluidCell item : ALL_FLUID_CELLS) {
            ModelLoader.setCustomModelResourceLocation(
                    item, 0,
                    new ModelResourceLocation(item.getRegistryName(), "inventory"));
        }
        ModelLoader.setCustomModelResourceLocation(
                DISK_HOUSING, 0,
                new ModelResourceLocation(DISK_HOUSING.getRegistryName(), "inventory"));
    }
}