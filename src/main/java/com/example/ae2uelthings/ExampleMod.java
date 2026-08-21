package com.example.ae2uelthings;

import appeng.api.AEApi;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import com.example.ae2uelthings.disk.storage.DiskCellHandler;
import com.example.ae2uelthings.disk.storage.DiskFluidCellHandler;
import com.example.ae2uelthings.disk.storage.DiskStorageEventHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// 修正メモ(起動時バグ対策): "after:nae2" を追加。必須依存ではなくロード順序のヒントで、
// nae2が入っている環境ではae2uelthingsより先にConstructionされることを保証する。
// (ModDiskItems#registerItems側の対策だけでも十分ではあるが、意図を明示するため保険として付与)
@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION,
        dependencies = "required-after:appliedenergistics2;after:nae2")
public class ExampleMod {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Hello From {}!", Tags.MOD_NAME);

        MinecraftForge.EVENT_BUS.register(new DiskStorageEventHandler());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        AEApi.instance().registries().cell().addCellHandler(new DiskCellHandler());
        AEApi.instance().registries().cell().addCellHandler(new DiskFluidCellHandler());
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new com.example.ae2uelthings.command.CommandAe2uelThings());
    }

}