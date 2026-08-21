package com.example.ae2uelthings.api;

import appeng.api.storage.IStorageChannel;
import net.minecraft.item.ItemStack;


public interface IDiskCellDefinition {

    int getBytes(ItemStack cellItem);

    int getBytesPerType(ItemStack cellItem);

    double getIdleDrain(ItemStack cellItem);

    IStorageChannel<?> getChannel();
}