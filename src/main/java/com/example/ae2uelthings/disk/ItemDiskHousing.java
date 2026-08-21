package com.example.ae2uelthings.disk;

import com.example.ae2uelthings.Tags;
import net.minecraft.item.Item;


public class ItemDiskHousing extends Item {

    public static final String ID = "disk_housing";

    public ItemDiskHousing() {
        setTranslationKey(Tags.MOD_ID + "." + ID);
        setRegistryName(ID);
        setMaxStackSize(1);

        setCreativeTab(ModCreativeTab.INSTANCE);
    }
}