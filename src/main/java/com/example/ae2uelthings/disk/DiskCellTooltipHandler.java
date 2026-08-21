package com.example.ae2uelthings.disk;

import com.example.ae2uelthings.Tags;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.translation.I18n;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Iterator;
import java.util.List;


@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class DiskCellTooltipHandler {

    private DiskCellTooltipHandler() {
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }

        if (stack.getItem() instanceof ItemDiskCell || stack.getItem() instanceof ItemDiskFluidCell) {
            stripDebugNbtLines(event.getToolTip());
            return;
        }

        // AE2本体のFuzzy Card/Inverter Card自体をホバーしたときに、ae2uelthingsのDISKで
        // 使えることが分かるよう一言添える(DiskUpgradesの判定をそのまま流用)。
        if (DiskUpgrades.isFuzzyCard(stack) || DiskUpgrades.isInverterCard(stack)) {
            event.getToolTip().add(TextFormatting.DARK_GRAY
                    + I18n.translateToLocal("item." + Tags.MOD_ID + ".disk_cell.upgrades.works_with_disk"));
        }
    }

    private static void stripDebugNbtLines(List<String> tooltip) {
        Iterator<String> it = tooltip.iterator();
        while (it.hasNext()) {
            String line = it.next();
            if (line.contains("NBT:") && line.contains("tag")) {
                it.remove();
            }
        }
    }
}