package com.example.ae2uelthings.disk.storage;

import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;


public class DiskStorageEventHandler {

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (isServerOverworld(event.getWorld())) {
            DiskStorageManager.refresh();
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (isServerOverworld(event.getWorld())) {
            DiskStorageManager.reset();
        }
    }

    private static boolean isServerOverworld(World world) {
        return !world.isRemote && world.provider.getDimension() == 0;
    }
}