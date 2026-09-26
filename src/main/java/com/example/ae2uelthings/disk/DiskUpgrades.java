package com.example.ae2uelthings.disk;

import appeng.api.AEApi;
import appeng.core.localization.GuiText;
import appeng.core.localization.Tooltips;
import com.example.ae2uelthings.Tags;
import com.example.ae2uelthings.disk.storage.DiskCellInventoryHandler;
import com.example.ae2uelthings.disk.storage.DiskFluidCellInventoryHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.Style;
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
 * <p>Fuzzy Card/Inverter Cardの判定には
 * {@code AEApi.instance().definitions().materials().cardFuzzy()/cardInverter()}
 * ({@code IItemDefinition#isSameAs}) を使っている。</p>
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
     * 現在装着中のカードをツールチップへ追加する(何も挿さっていなければ何も追加しない)。
     *
     * 翻訳キーは assets/{modid}/lang/*.lang 側の以下を使う:
     * <pre>
     * item.ae2uelthings.disk_cell.upgrades.installed=Installed: %s
     * </pre>
     */
    public static void appendTooltip(List<String> tooltip, IItemHandler upgrades) {
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

    /**
     * DISKセルのツールチップに容量・フィルター情報を追加する(クライアント側で呼ばれる)。
     *
     * <p>修正メモ(クライアント/サーバー間のデータ競合対策): 以前はここで
     * {@code AEApi.instance().registries().cell().getCellInventory(...)} を呼んでおり、
     * サーバー側の {@link com.example.ae2uelthings.disk.storage.DiskStorageManager} を
     * クライアント(描画スレッド)から直接読み書きしていた。シングルプレイではサーバースレッドと
     * 同時にアクセスして ConcurrentModificationException になる恐れがあり、マルチプレイでは
     * クライアント側にデータが無いため常に「0 of X Bytes Used」と表示されていた。</p>
     *
     * <p>参考元(AE2Things)の DISKCellHandler#addCellInformationToTooltip と同じく、
     * サーバー側の persist() でセル自身のNBTへ書き込んだ要約値
     * ({@link DiskCellInventoryHandler#TAG_ITEM_COUNT})
     * と、セル自身が持つ config/upgrades だけを読む。ItemStackのNBTはバニラの仕組みで
     * クライアントへ同期されるため、シングル/マルチどちらでも同じ表示になる。
     * AE2本体の addCellInformation を通らなくなったため、NAE2の「Cell View」ヒント行の
     * 除去処理も不要になった。</p>
     *
     * <p>表示する行(AE2本体のセルと同じ書式):</p>
     * <ul>
     *   <li>「X of Y Bytes Used」</li>
     *   <li>「無制限 Types」(DISKは種類無制限のため)</li>
     *   <li>フィルター設定時のみ「[Partitioned] - Included/Excluded Precise/Fuzzy」</li>
     *   <li>F3+H(詳細表示)時のみ「Disk UUID: ...」</li>
     * </ul>
     *
     * @param totalBytes   セルの総byte数
     * @param fluid        液体DISKならtrue(合計量はmBで保存されているためbyte換算する)
     * @param advanced     F3+Hの詳細表示が有効か({@code ITooltipFlag#isAdvanced()})
     */
    public static void appendCellInformation(ItemStack stack, List<String> tooltip, long totalBytes,
                                             boolean fluid, IItemHandler config, IItemHandler upgrades, boolean advanced) {
        NBTTagCompound tag = stack.getTagCompound();
        long count = tag != null ? tag.getLong(DiskCellInventoryHandler.TAG_ITEM_COUNT) : 0;

        // 使用byte数。DiskCellInventoryHandler/DiskFluidCellInventoryHandler#getUsedBytes と同じ計算
        // (参考元と同じく合計個数そのまま。液体は mB → byte を切り上げ換算)。計算方法を変える場合は両方を揃えること。
        long storedBytes = count;
        if (fluid) {
            int mbPerByte = DiskFluidCellInventoryHandler.MB_PER_BYTE;
            storedBytes = count <= 0 ? 0 : (count + mbPerByte - 1) / mbPerByte;
        }
        tooltip.add(Tooltips.bytesUsed(storedBytes, totalBytes).getFormattedText());

        String unlimitedText = I18n.translateToLocal("item." + Tags.MOD_ID + ".disk_cell.unlimited_types");
        tooltip.add(Tooltips.of(
                Tooltips.of(unlimitedText).setStyle(new Style().setColor(TextFormatting.LIGHT_PURPLE)),
                Tooltips.of(" "),
                Tooltips.of(GuiText.Types)).getFormattedText());

        if (DiskConfig.hasAnyFilter(config)) {
            // DiskCellInventoryHandler#getIncludeExcludeMode / #isFuzzy と同じ判定
            String list = (hasInverterCard(upgrades) ? GuiText.Excluded : GuiText.Included).getLocal();
            String mode = (!fluid && hasFuzzyCard(upgrades) ? GuiText.Fuzzy : GuiText.Precise).getLocal();
            tooltip.add("[" + GuiText.Partitioned.getLocal() + "]" + " - " + list + ' ' + mode);
        }

        if (advanced && tag != null && tag.hasKey(DiskCellInventoryHandler.TAG_DISK_UUID)) {
            tooltip.add(TextFormatting.GRAY + I18n.translateToLocalFormatted(
                    "item." + Tags.MOD_ID + ".disk_cell.uuid",
                    TextFormatting.AQUA + tag.getString(DiskCellInventoryHandler.TAG_DISK_UUID)));
        }
    }
}
