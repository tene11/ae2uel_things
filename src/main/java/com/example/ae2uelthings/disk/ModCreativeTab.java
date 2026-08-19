package com.example.ae2uelthings.disk;

import com.example.ae2uelthings.Tags;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;

/**
 * ae2uelthings専用のクリエイティブタブ。
 *
 * 以前はDISK関連アイテムを全部 {@code CreativeTabs.SEARCH} に設定していたが、
 * これだと検索バーに名前を打たないと見つからず、タブを眺めて発見することができない。
 * AE2本体側の内部クリエイティブタブを直接参照する手も考えたが、AE2UEL側の
 * 非公開クラスに依存するとフォーク間で壊れるリスクがあるため、素直に自前のタブを作る。
 *
 * アイコンはgetTabIconItem()が最初に呼ばれた時点で1回だけ解決すればよいので、
 * ここではModDiskItems.DISK_CELL_1Kを直接参照している
 * (staticフィールドの初期化順序に注意: ModDiskItemsのクラス初期化が先に走っている必要がある)。
 */
public class ModCreativeTab extends CreativeTabs {

    public static final ModCreativeTab INSTANCE = new ModCreativeTab();

    private ModCreativeTab() {
        super(Tags.MOD_ID);
    }

    @Override
    public ItemStack createIcon() {
        return new ItemStack(ModDiskItems.DISK_CELL_1K);
    }
}