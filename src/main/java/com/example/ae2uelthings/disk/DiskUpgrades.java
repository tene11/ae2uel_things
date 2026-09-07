package com.example.ae2uelthings.disk;

import appeng.api.AEApi;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.IStorageChannel;
import com.example.ae2uelthings.ExampleMod;
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
     * AE2本体のストレージセルと全く同じ書式("X of Y Bytes Used" / "X of Y Types" /
     * Partitioned時はFuzzy・Preciseの行)でツールチップにセル情報を追加する
     * (appeng.items.storage.AbstractStorageCell#addCheckedInformation参照。同じAPI
     * {@code AEApi.instance().client().addCellInformation(...)} をそのまま使う)。
     *
     * <p>DISKセルは種類無制限(totalItemTypes == Integer.MAX_VALUE)なので、そのまま
     * 呼び出すと"X of 2147483647 Types"のような不自然な表示になる。そのため
     * バイト使用量・タイプ数の2行が追加された直後に2行目(タイプ数の行)だけを
     * 特定し、「使用数 of 合計数」の部分を丸ごと「無制限」表記に差し替える
     * (末尾の"Types"相当の単位語はAE2本体の翻訳をそのまま残すため、合計数の
     * 直後から行末までを切り出して"無制限"に付け足す)。</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void appendCellInformation(ItemStack stack, List<String> tooltip, IStorageChannel<?> channel) {
        try {
            Object handlerObj = AEApi.instance().registries().cell().getCellInventory(stack, null, channel);
            if (!(handlerObj instanceof ICellInventoryHandler)) {
                if (handlerObj != null) {
                    ExampleMod.LOGGER.warn(
                            "[{}] appendCellInformation: unexpected handler type {}",
                            Tags.MOD_ID, handlerObj.getClass().getName());
                }
                return;
            }

            ICellInventoryHandler handler = (ICellInventoryHandler) handlerObj;
            ICellInventory cellInv = handler.getCellInv();

            // AE2本体は必ず[バイト使用量, タイプ数, (preformatted時)Partitioned...]の順で追加する。
            int typesLineIndex = tooltip.size() + 1;
            AEApi.instance().client().addCellInformation(handler, tooltip);

            // NAE2(Neeve's AE2: Extended Life Additions)の「Cell View」機能は、Mixinで
            // appeng.core.api.ApiClientHelper#addCellInformation の末尾(RETURN時)に
            // 直接割り込み、空行+「[キー]で中身を確認」のようなヒント行を無条件で追加する
            // (co.neeve.nae2.mixin.jei.cellview.MixinApiClientHelper参照)。このMixinは
            // ItemTooltipEventより前段の、このAPI呼び出し自体に直接割り込むため、
            // ItemTooltipEvent側で後から取り除こうとしても間に合わない。ここで直接呼び出した
            // 直後に取り除く必要がある。
            //
            // またこのヒントは appeng.api.implementations.items.IStorageCell を実装した
            // アイテムしか対象にしないJEI連携と対になっており、それを実装していない
            // ae2uelthings のDISKセルでは「[キー]を押しても中身は表示されない」という
            // 実態のないヒントになってしまうため、そもそも表示しない。
            stripNae2CellViewHint(tooltip);

            if (cellInv != null && typesLineIndex < tooltip.size() && cellInv.getTotalItemTypes() >= Integer.MAX_VALUE) {
                String original = tooltip.get(typesLineIndex);
                String totalStr = String.valueOf(cellInv.getTotalItemTypes());
                String unlimitedText = I18n.translateToLocal("item." + Tags.MOD_ID + ".disk_cell.unlimited_types");

                int idx = original.indexOf(totalStr);
                // "{使用数} {of} {合計数} {Types}" のうち、"{使用数} {of} {合計数}" を
                // "無制限" に置き換え、末尾の" {Types}"(AE2本体の翻訳)はそのまま残す。
                String rewritten = idx >= 0
                        ? unlimitedText + original.substring(idx + totalStr.length())
                        : unlimitedText;
                tooltip.set(typesLineIndex, rewritten);
            }
        } catch (Exception | LinkageError e) {
            ExampleMod.LOGGER.warn("[{}] appendCellInformation: ", Tags.MOD_ID, e);
        }
    }

    /**
     * NAE2の「Cell View」Mixinがappeng.core.api.ApiClientHelper#addCellInformationの
     * 末尾に無条件で追加する「空行」+「ヒント行」の2行を取り除く。
     *
     * <p>NAE2側にこの2行だけを狙い撃ちできる判定APIは無いため、「末尾が空行で、かつ
     * その直前にもう1行ある」という組み合わせ(AE2本体がこの位置に空行を単独で
     * 追加することは無い)と、NAE2がロードされていることの2条件で判定する。
     * 該当しなければ何もしない(誤って自前の行やAE2本体の行を消さないための保険)。</p>
     */
    private static void stripNae2CellViewHint(List<String> tooltip) {
        if (!ModCompat.isNae2Loaded()) {
            return;
        }
        int size = tooltip.size();
        if (size >= 2 && tooltip.get(size - 2).isEmpty()) {
            tooltip.remove(size - 1);
            tooltip.remove(size - 2);
        }
    }
}