package com.example.ae2uelthings.disk;

import com.example.ae2uelthings.Tags;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Iterator;
import java.util.List;

/**
 * DISKセルのツールチップから、Minecraft標準の「NBT: N tag(s)」行
 * (F3+H 詳細ツールチップON時に自動追加される)を取り除く。
 *
 * 修正メモ: 以前ここに DiskStorageEventHandler をstaticネストクラスとして同居させていたが、
 * DiskStorageManagerのトップレベル化に合わせて
 * com.example.ae2uelthings.disk.storage.DiskStorageEventHandler として独立させた。
 * このクラスはツールチップ表示の責務のみを担当する。
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class DiskCellTooltipHandler {

    private DiskCellTooltipHandler() {
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemDiskCell)) {
            return;
        }
        List<String> tooltip = event.getToolTip();
        Iterator<String> it = tooltip.iterator();
        while (it.hasNext()) {
            String line = it.next();
            if (line.contains("NBT:") && line.contains("tag")) {
                it.remove();
            }
        }
    }
}