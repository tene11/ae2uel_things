package com.example.ae2uelthings;

import appeng.api.AEApi;
import com.example.ae2uelthings.disk.storage.DiskCellHandler;
import com.example.ae2uelthings.disk.storage.DiskFluidCellHandler;
import com.example.ae2uelthings.disk.storage.DiskStorageEventHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION,
        dependencies = "required-after:appliedenergistics2")
public class ExampleMod {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Hello From {}!", Tags.MOD_NAME);

        // DiskStorageManagerのstaticキャッシュを、ワールドのロード/アンロードに
        // 合わせて更新するためのイベントハンドラ。これが無いと、DISKの中身は
        // どこにも保存されずリログ(ワールド再読み込み)で消える。
        MinecraftForge.EVENT_BUS.register(new DiskStorageEventHandler());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        AEApi.instance().registries().cell().addCellHandler(new DiskCellHandler());
        AEApi.instance().registries().cell().addCellHandler(new DiskFluidCellHandler());
    }
}