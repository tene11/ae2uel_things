package com.example.ae2uelthings.disk;

import appeng.api.AEApi;
import com.example.ae2uelthings.Tags;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.translation.I18n;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * DISKセルのアップグレードカード(Fuzzy Card / Inverter Card)関連の共通処理。
 *
 * <p>参考元(io.github.lapis256.ae2_mega_things)は {@code AE2MTItems.initUpgrades()} で、
 * item版DISK(ItemDISKDrive)にFuzzy Card+Inverter Cardの2枚、fluid版DISK(FluidDISKDrive)には
 * Inverter Cardのみ1枚を、それぞれ {@code Upgrades.add(...)} 経由で解放している。
 * Fuzzyがfluid側に付かないのは {@code AEKeyType.fluids().supportsFuzzyRangeSearch()} が
 * falseを返す(=フルイドにはアイテムのダメージ値のような"あいまい一致"対象が無い)ため。
 * この非対称性をそのまま踏襲し、ae2uelthings側もitem版=2枠・fluid版=1枠とする。</p>
 *
 * <p>ae2uelthings(AE2 rv6/1.12.2)には {@code Upgrades.add} のような仕組みが無いため、
 * Forge標準の {@link ItemStackHandler} をセル本体のNBT(タグ名 "Upgrades")へ直接
 * 永続化する形で同等のスロットを再現する。</p>
 *
 * <p><b>要ローカル検証:</b> Fuzzy Card/Inverter Cardの判定に
 * {@code AEApi.instance().definitions().materials().cardFuzzy()/cardInverter()} を使っている。
 * これはAE2 rv6の想定APIだが、実際のメソッド名・戻り値型(IItemDefinitionそのものか
 * Optional&lt;IItemDefinition&gt;か)をIDE上で必ず確認すること。異なる場合は、
 * {@link DiskTier} のcomponentMetaと同じ考え方で appliedenergistics2:material の
 * 直接メタ値参照に切り替える必要がある。</p>
 */
public final class DiskUpgrades {

    private static final String TAG_UPGRADES = "Upgrades";

    private DiskUpgrades() {
    }

    public static boolean isFuzzyCard(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return AEApi.instance().definitions().materials().cardFuzzy().isSameAs(stack);
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    public static boolean isInverterCard(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return AEApi.instance().definitions().materials().cardInverter().isSameAs(stack);
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    /**
     * DISKセル用のアップグレードインベントリを作る(呼び出しのたびにNBTから復元する)。
     *
     * @param cellItem   永続化先となるセル本体のItemStack
     * @param allowFuzzy trueならFuzzy Card+Inverter Cardの2枠(item版DISK用)、
     *                    falseならInverter Cardのみの1枠(fluid版DISK用)
     */
    public static ItemStackHandler createInventory(ItemStack cellItem, boolean allowFuzzy) {
        int slots = allowFuzzy ? 2 : 1;
        ItemStackHandler handler = new ItemStackHandler(slots) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                if (stack.isEmpty()) {
                    return true;
                }
                boolean fuzzy = allowFuzzy && isFuzzyCard(stack);
                boolean inverter = isInverterCard(stack);
                if (!fuzzy && !inverter) {
                    // Fuzzy Card/Inverter Card以外はそもそも受け付けない
                    return false;
                }
                // 参考元(Upgrades.add(card, drive, 1))と同じく、同じ種類のカードは
                // 合計1枚まで。他のスロットに既に同じ種類が入っていないか確認する。
                for (int i = 0; i < getSlots(); i++) {
                    if (i == slot) {
                        continue;
                    }
                    ItemStack other = getStackInSlot(i);
                    if (other.isEmpty()) {
                        continue;
                    }
                    if (fuzzy && isFuzzyCard(other)) {
                        return false;
                    }
                    if (inverter && isInverterCard(other)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public int getSlotLimit(int slot) {
                // カードは64個までスタックできるアイテムだが、アップグレードスロットは
                // 1枠につき1枚まで(参考元のUpgrades.add(card, drive, 1)と同じ仕様)。
                // これを指定しないとForge標準のItemStackHandlerはスタックのまま
                // 挿入を許可してしまう。
                return 1;
            }

            @Override
            protected void onContentsChanged(int slot) {
                NBTTagCompound tag = cellItem.hasTagCompound() ? cellItem.getTagCompound() : new NBTTagCompound();
                tag.setTag(TAG_UPGRADES, this.serializeNBT());
                cellItem.setTagCompound(tag);
            }
        };
        NBTTagCompound tag = cellItem.getTagCompound();
        if (tag != null && tag.hasKey(TAG_UPGRADES)) {
            handler.deserializeNBT(tag.getCompoundTag(TAG_UPGRADES));
        }
        return handler;
    }

    public static boolean hasFuzzyCard(IItemHandler upgrades) {
        return containsCard(upgrades, true);
    }

    public static boolean hasInverterCard(IItemHandler upgrades) {
        return containsCard(upgrades, false);
    }

    private static boolean containsCard(IItemHandler upgrades, boolean fuzzy) {
        if (upgrades == null) {
            return false;
        }
        for (int i = 0; i < upgrades.getSlots(); i++) {
            ItemStack stack = upgrades.getStackInSlot(i);
            if (fuzzy ? isFuzzyCard(stack) : isInverterCard(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 対応カードの案内 + 現在装着中のカードをツールチップへ追加する。
     *
     * 翻訳キーは assets/{modid}/lang/*.lang 側に以下を追加すること:
     * <pre>
     * item.ae2uelthings.disk_cell.upgrades.supported_fuzzy_inverter=Supports: Fuzzy Card, Inverter Card
     * item.ae2uelthings.disk_cell.upgrades.supported_inverter_only=Supports: Inverter Card
     * item.ae2uelthings.disk_cell.upgrades.installed=Installed: %s
     * </pre>
     *
     * @param allowFuzzy trueならFuzzy Card+Inverter Card対応の文言、falseならInverter Cardのみの文言
     */
    public static void appendTooltip(List<String> tooltip, IItemHandler upgrades, boolean allowFuzzy) {
        String supportedKey = "item." + Tags.MOD_ID + ".disk_cell.upgrades."
                + (allowFuzzy ? "supported_fuzzy_inverter" : "supported_inverter_only");
        tooltip.add(TextFormatting.DARK_GRAY + I18n.translateToLocal(supportedKey));

        if (upgrades == null) {
            return;
        }
        List<String> installedNames = new ArrayList<>();
        for (int i = 0; i < upgrades.getSlots(); i++) {
            ItemStack card = upgrades.getStackInSlot(i);
            if (!card.isEmpty()) {
                installedNames.add(card.getDisplayName());
            }
        }
        if (!installedNames.isEmpty()) {
            tooltip.add(TextFormatting.DARK_GRAY + I18n.translateToLocalFormatted(
                    "item." + Tags.MOD_ID + ".disk_cell.upgrades.installed",
                    String.join(", ", installedNames)));
        }
    }
}