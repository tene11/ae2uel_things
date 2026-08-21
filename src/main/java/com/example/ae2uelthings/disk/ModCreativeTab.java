package com.example.ae2uelthings.disk;

import com.example.ae2uelthings.Tags;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;


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