package com.example.ae2uelthings.disk.storage;

import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * {@link DiskStorageManager} のstaticキャッシュを、サーバーワールドのロード/アンロードに
 * 合わせて更新するためのイベントハンドラ。
 *
 * 元々は DiskCellTooltipHandler.java 内のstaticネストクラスだったが、DiskStorageManagerを
 * トップレベル化したのに合わせてこちらも独立させた。
 *
 * オーバーワールドのロード時に一度キャッシュを解決し、アンロード時にリセットする。
 * AE2Things本家は「毎tick呼び直す」方式だったが、ロード/アンロードイベント駆動の方が
 * 無駄なMapStorageアクセスを避けられる。シングルプレイの「ワールドを離れる→別ワールドに入る」
 * のような操作で不整合が出るようなら、WorldTickEvent(Phase.START)での毎tick更新に切り替えること。
 *
 * MOD初期化処理 (例: ExampleMod#preInit 相当) で以下の登録が必要:
 *   MinecraftForge.EVENT_BUS.register(new DiskStorageEventHandler());
 */
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