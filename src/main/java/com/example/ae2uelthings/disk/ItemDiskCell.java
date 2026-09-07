package com.example.ae2uelthings.disk;

import appeng.api.AEApi;
import appeng.api.config.FuzzyMode;
import appeng.api.storage.ICellWorkbenchItem;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import com.example.ae2uelthings.Tags;
import com.example.ae2uelthings.api.IDiskCellDefinition;
import com.example.ae2uelthings.disk.storage.DiskCellInventoryHandler;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.IItemHandler;
import appeng.util.InventoryAdaptor;

import java.util.List;


public class ItemDiskCell extends Item implements ICellWorkbenchItem, IDiskCellDefinition {

    private static final int MAX_TYPES = Integer.MAX_VALUE;

    private static final int BYTES_PER_TYPE = 1;

    private final DiskTier tier;

    public ItemDiskCell(DiskTier tier) {
        this.tier = tier;
        setTranslationKey(Tags.MOD_ID + ".disk_cell_" + tier.getSuffix());
        setRegistryName(tier.getItemId());
        setMaxStackSize(1);

        setCreativeTab(ModCreativeTab.INSTANCE);
    }

    public DiskTier getTier() {
        return tier;
    }


    @Override
    public int getBytes(ItemStack cellItem) {
        return tier.getUsableBytes();
    }

    @Override
    public int getBytesPerType(ItemStack cellItem) {
        return BYTES_PER_TYPE;
    }

    public int getTotalTypes(ItemStack cellItem) {
        return MAX_TYPES;
    }

    @Override
    public double getIdleDrain(ItemStack cellItem) {

        return 1.0D + tier.ordinal();
    }

    @Override
    public IStorageChannel<?> getChannel() {
        return AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
    }



    @Override
    public boolean isEditable(ItemStack itemStack) {
        // タイプフィルター(config)をセルワークベンチで編集できるようにする
        return true;
    }

    @Override
    public IItemHandler getUpgradesInventory(ItemStack itemStack) {
        // Fuzzy Card + Inverter Card の2枠(参考元のitem版DISKと同じ構成、DiskUpgrades参照)
        return DiskUpgrades.createInventory(itemStack, true);
    }

    @Override
    public IItemHandler getConfigInventory(ItemStack itemStack) {
        // タイプフィルター本体(参考元のCellConfigに相当、DiskConfig参照)
        return DiskConfig.createInventory(itemStack);
    }

    @Override
    public FuzzyMode getFuzzyMode(ItemStack itemStack) {
        NBTTagCompound tag = itemStack.getTagCompound();
        if (tag != null && tag.hasKey("FuzzyMode")) {
            try {
                return FuzzyMode.valueOf(tag.getString("FuzzyMode"));
            } catch (IllegalArgumentException ignored) {
                // fall through to default
            }
        }
        return FuzzyMode.IGNORE_ALL;
    }

    @Override
    public void setFuzzyMode(ItemStack itemStack, FuzzyMode fuzzyMode) {
        if (!itemStack.hasTagCompound()) {
            itemStack.setTagCompound(new NBTTagCompound());
        }
        itemStack.getTagCompound().setString("FuzzyMode", fuzzyMode.name());
    }

    // ------------------------------------------------------------------
    // Item overrides
    // ------------------------------------------------------------------

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, World world, List<String> tooltip, ITooltipFlag flag) {
        super.addInformation(stack, world, tooltip, flag);

        // "X of Y Bytes Used" / "X of Y Types" / (Partitioned時)Fuzzy等の行は、
        // AE2本体のストレージセルと全く同じ文言・並びになるよう、AE2本体が
        // 自分のセル用に使っているのと同じAPIをそのまま呼び出す(DiskUpgrades参照。
        // DISKセルは種類無制限のため、タイプ数の行だけ「無制限」表記に差し替えている)。
        DiskUpgrades.appendCellInformation(stack, tooltip, getChannel());

        DiskUpgrades.appendTooltip(tooltip, getUpgradesInventory(stack));
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World worldIn, EntityPlayer playerIn, EnumHand handIn) {
        ItemStack stack = playerIn.getHeldItem(handIn);
        if (playerIn.isSneaking() && !worldIn.isRemote) {
            if (isEmpty(stack)) {
                ItemStack base = getDowngradeBaseStack();
                ItemStack component = createComponentStack(tier);
                if (!base.isEmpty()) {
                    // 本家AE2のAbstractStorageCell#disassembleDriveと同様、
                    // stack.shrink(1)で"count=0の同一オブジェクト"を手のスロットに
                    // 残したままinventoryへの追加処理を行うと、InventoryAdaptorが
                    // そのスロットを空きスロットとして誤検出して降格アイテムを
                    // 挿入してしまい、直後にonItemRightClickの戻り値(古いstack参照)で
                    // setHeldItemされた際にそのスロットが上書きされて消えてしまう。
                    // これを防ぐため、まずスロットをItemStack.EMPTYで明示的に空にしてから
                    // giveOrDropを行い、戻り値は処理後の最新の手のアイテムを再取得する。
                    playerIn.setHeldItem(handIn, ItemStack.EMPTY);
                    giveOrDrop(playerIn, base);
                    giveOrDrop(playerIn, component);
                    if (playerIn.inventoryContainer != null) {
                        playerIn.inventoryContainer.detectAndSendChanges();
                    }
                    return new ActionResult<>(EnumActionResult.SUCCESS, playerIn.getHeldItem(handIn));
                }
            }
        }
        return super.onItemRightClick(worldIn, playerIn, handIn);
    }

    private boolean isEmpty(ItemStack stack) {
        try {
            Object handlerObj = AEApi.instance().registries().cell()
                    .getCellInventory(stack, null, getChannel());
            if (handlerObj instanceof DiskCellInventoryHandler) {
                return ((DiskCellInventoryHandler) handlerObj).isEmpty();
            }
        } catch (Exception | LinkageError e) {

            return false;
        }
        return false;
    }


    /**
     * インベントリへの追加を試み、入りきらなかった分だけその場にドロップする。
     * AE2本家のAbstractStorageCell#disassembleDriveと同じパターン
     * (InventoryAdaptor#addItemsで追加を試み、残りをplayer.dropItemでドロップ)に
     * 揃えている。呼び出し元でplayer.inventoryContainer.detectAndSendChanges()を
     * 呼び、サーバー側の変更を確実にクライアントへ同期させる点も本家と同様。
     */
    private static void giveOrDrop(EntityPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        InventoryAdaptor ia = InventoryAdaptor.getAdaptor(player);
        ItemStack leftover = ia != null ? ia.addItems(stack) : stack;
        if (!leftover.isEmpty()) {
            player.dropItem(leftover, false);
        }
    }

    private ItemStack getDowngradeBaseStack() {
        return new ItemStack(ModDiskItems.DISK_HOUSING);
    }

    /**
     * 分解時に返すコンポーネントを作る。tier.hasComponent()がfalse(拡張ティアで
     * NAE2側の素材が未確定、またはMAXティアでそもそも対応レシピが無い場合)は
     * ItemStack.EMPTYを返す(以前はここでmeta=-1のまま appliedenergistics2:material の
     * 不正なItemStackを生成してしまっていたバグを修正)。
     */
    private static ItemStack createComponentStack(DiskTier tier) {
        if (!tier.hasComponent()) {
            return ItemStack.EMPTY;
        }
        Item material = Item.getByNameOrId(tier.getComponentItemId());
        if (material == null) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(material, 1, tier.getComponentMeta());
    }
}