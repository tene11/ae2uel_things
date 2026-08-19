package com.example.ae2uelthings.disk;

import com.example.ae2uelthings.Tags;
import net.minecraft.item.Item;

/**
 * DISKセルのハウジング(土台)アイテム。
 *
 * それ自体はストレージ機能を持たず(IStorageCellを実装しない)、
 * 各ティアのDISKセルをクラフトする際の共通ベース素材として使う。
 * DISKセル本体(1k/4k/16k/64k)と統一感を持たせるため、スタック不可(上限1)にしている。
 */
public class ItemDiskHousing extends Item {

    public static final String ID = "disk_housing";

    public ItemDiskHousing() {
        setTranslationKey(Tags.MOD_ID + "." + ID);
        setRegistryName(ID);
        setMaxStackSize(1);
        // 専用クリエイティブタブに変更 (以前はCreativeTabs.SEARCHで検索しないと見つからなかった)
        setCreativeTab(ModCreativeTab.INSTANCE);
    }
}