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
            // レジストリ名・NBTタグ数(F3+H)行はどちらも取り除かない。バニラの通常の
            // アイテムと同じ見え方にしておく(アイテムID/NBT状態の確認に便利なため)。
            return;
        }

        // AE2本体のFuzzy Card/Inverter Card自体をホバーしたときに、ae2uelthingsのDISKで
        // 使えることが分かるよう一言添える(DiskUpgradesの判定をそのまま流用)。
        //
        // Fuzzy CardはアイテムDISKセルのみ対応(DiskUpgrades.createInventoryのallowFuzzy参照)、
        // Inverter Cardはアイテム版・流体版の両方に対応しているため、行を分けて追加する。
        if (DiskUpgrades.isFuzzyCard(stack)) {
            insertBeforeRegistryNameLine(event.getToolTip(), stack,
                    I18n.translateToLocal("item." + Tags.MOD_ID + ".disk_cell.upgrades.works_with_disk"));
        }

        if (DiskUpgrades.isInverterCard(stack)) {
            insertBeforeRegistryNameLine(event.getToolTip(), stack,
                    I18n.translateToLocal("item." + Tags.MOD_ID + ".disk_cell.upgrades.works_with_disk"));
            insertBeforeRegistryNameLine(event.getToolTip(), stack,
                    I18n.translateToLocal("item." + Tags.MOD_ID + ".disk_cell.upgrades.works_with_disk_fluid"));
        }
    }

    private static void insertBeforeRegistryNameLine(List<String> tooltip, ItemStack stack, String line) {
        String registryName = stack.getItem().getRegistryName() != null
                ? stack.getItem().getRegistryName().toString()
                : null;

        int insertIndex = tooltip.size();
        if (registryName != null) {
            for (int i = 0; i < tooltip.size(); i++) {
                if (registryName.equals(TextFormatting.getTextWithoutFormattingCodes(tooltip.get(i)))) {
                    insertIndex = i;
                    break;
                }
            }
        }
        tooltip.add(insertIndex, line);
    }
}