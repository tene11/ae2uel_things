package com.example.modid.disk;

import com.example.modid.Tags;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * DISKセル4ティア分のアイテム登録 + アイテムモデル登録。
 *
 * BasicCellHandler は AE2 本体側で「IStorageCell を実装したアイテム」を
 * 自動的にセルとして認識するため、AE2側への明示的なセルハンドラ登録は不要
 * (要ローカル検証: AE2UELでもこの自動認識の挙動が維持されているか確認すること)。
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class ModDiskItems {

    public static final ItemDiskCell DISK_CELL_1K = new ItemDiskCell(DiskTier.TIER_1K);
    public static final ItemDiskCell DISK_CELL_4K = new ItemDiskCell(DiskTier.TIER_4K);
    public static final ItemDiskCell DISK_CELL_16K = new ItemDiskCell(DiskTier.TIER_16K);
    public static final ItemDiskCell DISK_CELL_64K = new ItemDiskCell(DiskTier.TIER_64K);
    public static final ItemDiskHousing DISK_HOUSING = new ItemDiskHousing();

    private static final ItemDiskCell[] ALL_CELLS = {
            DISK_CELL_1K, DISK_CELL_4K, DISK_CELL_16K, DISK_CELL_64K
    };

    private ModDiskItems() {
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().registerAll(ALL_CELLS);
        event.getRegistry().register(DISK_HOUSING);
    }

    // モデルjsonは src/main/resources/assets/modid/models/item/disk_cell_XX.json に
    // 置いてあるだけでは表示されないため、ここで明示的にひも付ける。
    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public static void registerModels(ModelRegistryEvent event) {
        for (ItemDiskCell item : ALL_CELLS) {
            ModelLoader.setCustomModelResourceLocation(
                    item, 0,
                    new ModelResourceLocation(item.getRegistryName(), "inventory"));
        }
        ModelLoader.setCustomModelResourceLocation(
                DISK_HOUSING, 0,
                new ModelResourceLocation(DISK_HOUSING.getRegistryName(), "inventory"));
    }
}