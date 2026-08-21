package com.example.ae2uelthings.command;

import com.example.ae2uelthings.ExampleMod;
import com.example.ae2uelthings.Tags;
import com.example.ae2uelthings.disk.DiskTier;
import com.example.ae2uelthings.disk.ModCompat;
import com.example.ae2uelthings.disk.ModDiskItems;
import com.example.ae2uelthings.disk.storage.DiskStorageManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * DISK (Deep Item Storage disK) の復旧・管理コマンド。
 *
 * AE2Things本家(Fabric/NeoForge版)を移植したもの。
 * 以下の2つのサブコマンドを提供する:
 *
 * <ul>
 *   <li>{@code /ae2uelthings recover <uuid> [tier]} - 指定UUIDのデータが
 *       DiskStorageManagerに存在する場合、そのUUIDを持つ新しいDISKを生成する。
 *       {@code tier}は {@link DiskTier#getSuffix()} の値(1k/4k/16k/64k/256k/1024k/
 *       4096k/16384k/max)。省略時は{@link #DEFAULT_TIER}(64k)。
 *       256k〜16384kはNAE2導入環境でのみ実際に登録されているため、それ以外の環境で
 *       指定するとエラーになる。</li>
 *   <li>{@code /ae2uelthings getuuid} - 手に持っているDISKのUUIDを表示する。
 *       常にチャットへ通常テキストとして表示され、クリックでチャット欄への入力候補、
 *       Shiftクリックでカーソル位置への挿入ができる(1.12.2にはCOPY_TO_CLIPBOARDが無いため)。</li>
 * </ul>
 *
 * <p>実行権限: OP限定 (permission level 2)。</p>
 *
 * <h2>ローカライズ</h2>
 * <p>応答メッセージは全て翻訳キー経由({@link TextComponentTranslation}、
 * および {@link CommandException} はメッセージ文字列自体が翻訳キーとして扱われる仕様を利用)。
 * 実際の文言は {@code src/main/resources/assets/ae2uelthings/lang/en_us.lang} と
 * {@code ja_jp.lang} の {@code command.ae2uelthings.*} キーを参照。
 * プレイヤーのクライアント言語設定に応じて自動的に切り替わる。</p>
 */
public class CommandAe2uelThings extends CommandBase {

    /** DISK UUID が格納されるNBTタグキー */
    private static final String TAG_DISK_UUID = "DiskUUID";

    /** {@code tier}引数を省略した場合に使うデフォルトティア。 */
    private static final DiskTier DEFAULT_TIER = DiskTier.TIER_64K;

    /** チャット接頭辞。翻訳対象にはせず常に固定表記("[ae2uelthings] ")とする。 */
    private static final String PREFIX = TextFormatting.GRAY + "[" + Tags.MOD_ID + "] ";

    @Override
    public String getName() {
        return "ae2uelthings";
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("ae2uel");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/ae2uelthings <recover <uuid> [tier] | getuuid>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        // recoverはOP限定。getuuidはこの制限に従う。
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            sendHelp(sender);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "recover":
                recover(server, sender, args);
                return;
            case "getuuid":
                getUuid(sender);
                return;
            default:
                sendHelp(sender);
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "recover", "getuuid");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("recover")) {
            List<String> suffixes = new ArrayList<>();
            for (DiskTier tier : DiskTier.values()) {
                suffixes.add(tier.getSuffix());
            }
            return getListOfStringsMatchingLastWord(args, suffixes.toArray(new String[0]));
        }
        return Collections.emptyList();
    }

    // ------------------------------------------------------------------
    // getuuid
    // ------------------------------------------------------------------

    private void getUuid(ICommandSender sender) throws CommandException {
        EntityPlayer player = CommandBase.getCommandSenderAsPlayer(sender);
        ItemStack stack = player.getHeldItemMainhand();

        if (stack.isEmpty() || !stack.hasTagCompound()) {
            // CommandExceptionのメッセージはキーとして扱われ、表示時にTextComponentTranslationになる
            throw new CommandException("command.ae2uelthings.getuuid.not_disk");
        }

        NBTTagCompound tag = stack.getTagCompound();
        // 要注意: DiskCellInventoryHandler/DiskFluidCellInventoryHandlerは
        // tag.setString(TAG_DISK_UUID, uuid.toString()) で文字列として保存しているため、
        // ここも hasKey/getString で読む(hasUniqueId/getUniqueIdは別フォーマットなので不可)。
        if (!tag.hasKey(TAG_DISK_UUID)) {
            throw new CommandException("command.ae2uelthings.getuuid.no_uuid");
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(tag.getString(TAG_DISK_UUID));
        } catch (IllegalArgumentException e) {
            throw new CommandException("command.ae2uelthings.getuuid.invalid_tag", tag.getString(TAG_DISK_UUID));
        }

        // 1.12.2のClickEvent.ActionにはCOPY_TO_CLIPBOARDが存在しない(1.15+で追加されたもの)。
        // 代わりにSUGGEST_COMMAND(クリックでチャット欄にUUIDが入力される)と
        // setInsertion(Shiftクリックでカーソル位置に挿入)を組み合わせる。
        // UUID自体は常にチャットへ通常のテキストとして表示される。
        ITextComponent hoverText = new TextComponentTranslation("command.ae2uelthings.getuuid.hover");

        TextComponentString uuidText = new TextComponentString(uuid.toString());
        uuidText.getStyle()
                .setColor(TextFormatting.GREEN)
                .setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, uuid.toString()))
                .setInsertion(uuid.toString())
                .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverText));

        ITextComponent prefix = new TextComponentString(PREFIX)
                .appendSibling(new TextComponentTranslation("command.ae2uelthings.getuuid.prefix"))
                .appendSibling(uuidText);
        sender.sendMessage(prefix);
    }

    // ------------------------------------------------------------------
    // recover
    // ------------------------------------------------------------------

    private void recover(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new CommandException("command.ae2uelthings.recover.usage");
        }

        EntityPlayer player = CommandBase.getCommandSenderAsPlayer(sender);

        UUID uuid;
        try {
            uuid = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            throw new CommandException("command.ae2uelthings.recover.invalid_uuid", args[1]);
        }

        // 第3引数(tier)は省略可。省略時はDEFAULT_TIER(64k)を使う。
        DiskTier tier = DEFAULT_TIER;
        if (args.length >= 3) {
            tier = parseTier(args[2]);
        }

        // DiskStorageManager を refresh() で取得する
        DiskStorageManager manager = DiskStorageManager.refresh();

        // itemディスク の復旧
        // 要注意: DiskCellInventoryHandler#loadExisting() は
        // UUID.fromString(tag.getString(TAG_DISK_UUID)) で読むため、
        // ここも setString で書き込む(setUniqueIdは別フォーマットになり読めない)。
        if (manager.hasDisk(uuid)) {
            Item item = resolveCellItem(tier, false);
            ItemStack stack = new ItemStack(item);
            NBTTagCompound nbt = new NBTTagCompound();
            nbt.setString(TAG_DISK_UUID, uuid.toString());
            stack.setTagCompound(nbt);
            // バグ修正: addItemStackToInventoryの戻り値(bool)を見ていなかったため、
            // インベントリが満杯だとアイテムがどこにも生成されず消えるのに
            // 常に「復旧成功」の緑メッセージを返してしまっていた。戻り値がfalseの
            // 場合は足元にドロップし、その旨が分かる別メッセージを返す。
            boolean added = player.inventory.addItemStackToInventory(stack);
            if (!added) {
                player.dropItem(stack, false);
            }

            sendPrefixed(sender, TextFormatting.GREEN,
                    new TextComponentTranslation(
                            added ? "command.ae2uelthings.recover.success.item"
                                    : "command.ae2uelthings.recover.success.item.dropped",
                            tier.getSuffix(), uuid, player.getName()));
            return;
        }

        // fluid disk の復旧
        if (manager.hasFluidDisk(uuid)) {
            Item item = resolveCellItem(tier, true);
            ItemStack stack = new ItemStack(item);
            NBTTagCompound nbt = new NBTTagCompound();
            nbt.setString(TAG_DISK_UUID, uuid.toString());
            stack.setTagCompound(nbt);
            // バグ修正: item版と同じ理由で戻り値をチェックし、満杯なら足元にドロップする。
            boolean added = player.inventory.addItemStackToInventory(stack);
            if (!added) {
                player.dropItem(stack, false);
            }

            sendPrefixed(sender, TextFormatting.GREEN,
                    new TextComponentTranslation(
                            added ? "command.ae2uelthings.recover.success.fluid"
                                    : "command.ae2uelthings.recover.success.fluid.dropped",
                            tier.getSuffix(), uuid, player.getName()));
            return;
        }

        // 該当なし
        sendPrefixed(sender, TextFormatting.RED,
                new TextComponentTranslation("command.ae2uelthings.recover.not_found", uuid));
    }

    /**
     * {@code tier}引数の文字列(例: "64k", "max")をDiskTierへ変換する。
     * DiskTier#getSuffix()の値と大文字小文字を無視して一致するものを探す。
     */
    private DiskTier parseTier(String input) throws CommandException {
        String normalized = input.toLowerCase(Locale.ROOT);
        for (DiskTier tier : DiskTier.values()) {
            if (tier.getSuffix().equals(normalized)) {
                return tier;
            }
        }

        StringBuilder validSuffixes = new StringBuilder();
        for (DiskTier tier : DiskTier.values()) {
            if (validSuffixes.length() > 0) validSuffixes.append(", ");
            validSuffixes.append(tier.getSuffix());
        }
        throw new CommandException("command.ae2uelthings.recover.invalid_tier", input, validSuffixes.toString());
    }

    /**
     * 指定ティア・種別(アイテム/液体)に対応する登録済みアイテムを返す。
     * 拡張ティア(256k〜16384k)はNAE2導入環境でのみ実際に登録されているため、
     * 未導入環境でこれらを指定した場合は例外を投げる。
     */
    private Item resolveCellItem(DiskTier tier, boolean fluid) throws CommandException {
        switch (tier) {
            case TIER_1K:
                return fluid ? ModDiskItems.DISK_CELL_FLUID_1K : ModDiskItems.DISK_CELL_1K;
            case TIER_4K:
                return fluid ? ModDiskItems.DISK_CELL_FLUID_4K : ModDiskItems.DISK_CELL_4K;
            case TIER_16K:
                return fluid ? ModDiskItems.DISK_CELL_FLUID_16K : ModDiskItems.DISK_CELL_16K;
            case TIER_64K:
                return fluid ? ModDiskItems.DISK_CELL_FLUID_64K : ModDiskItems.DISK_CELL_64K;
            case TIER_256K:
            case TIER_1024K:
            case TIER_4096K:
            case TIER_16384K:
                if (!ModCompat.isNae2Loaded()) {
                    throw new CommandException("command.ae2uelthings.recover.tier_unavailable", tier.getSuffix());
                }
                switch (tier) {
                    case TIER_256K:
                        return fluid ? ModDiskItems.DISK_CELL_FLUID_256K : ModDiskItems.DISK_CELL_256K;
                    case TIER_1024K:
                        return fluid ? ModDiskItems.DISK_CELL_FLUID_1024K : ModDiskItems.DISK_CELL_1024K;
                    case TIER_4096K:
                        return fluid ? ModDiskItems.DISK_CELL_FLUID_4096K : ModDiskItems.DISK_CELL_4096K;
                    default:
                        return fluid ? ModDiskItems.DISK_CELL_FLUID_16384K : ModDiskItems.DISK_CELL_16384K;
                }
            case TIER_MAX:
                return fluid ? ModDiskItems.DISK_CELL_FLUID_MAX : ModDiskItems.DISK_CELL_MAX;
            default:
                // 要ローカル検証: DiskTierに新しいティアを追加した場合はここも更新すること
                throw new CommandException("command.ae2uelthings.recover.tier_unavailable", tier.getSuffix());
        }
    }

    private void sendHelp(ICommandSender sender) {
        sender.sendMessage(new TextComponentString(TextFormatting.GOLD + "=== AE2UELThings ==="));
        sender.sendMessage(new TextComponentTranslation("command.ae2uelthings.help.recover"));
        sender.sendMessage(new TextComponentTranslation("command.ae2uelthings.help.getuuid"));
    }

    /** "[ae2uelthings] " + 色付き翻訳メッセージ、という形の応答を送る共通ヘルパー。 */
    private void sendPrefixed(ICommandSender sender, TextFormatting color, ITextComponent body) {
        body.getStyle().setColor(color);
        ITextComponent message = new TextComponentString(PREFIX).appendSibling(body);
        sender.sendMessage(message);
    }
}