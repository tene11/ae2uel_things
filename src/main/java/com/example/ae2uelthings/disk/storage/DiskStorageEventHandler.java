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

    /**
     * WorldServer#saveAllChunks は、WorldSavedData(DiskStorageManager)とチャンクの保存が
     * 終わった後にこのイベントを発行する。ここで直近の保存データを .bak に書き出す。
     */
    @SubscribeEvent
    public void onWorldSave(WorldEvent.Save event) {
        if (isServerOverworld(event.getWorld())) {
            DiskStorageManager.getCached().writeBackupIfPending();
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
