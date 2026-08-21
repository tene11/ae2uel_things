package com.example.ae2uelthings.disk;

import appeng.api.AEApi;
import appeng.api.config.FuzzyMode;
import appeng.api.storage.ICellWorkbenchItem;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IFluidStorageChannel;
import com.example.ae2uelthings.Tags;
import com.example.ae2uelthings.api.DiskCapacityFormat;
import com.example.ae2uelthings.api.IDiskFluidCellDefinition;
import com.example.ae2uelthings.disk.storage.DiskFluidCellInventoryHandler;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.IItemHandler;

import java.util.List;


public class ItemDiskFluidCell extends Item implements ICellWorkbenchItem, IDiskFluidCellDefinition {

    private static final int MAX_TYPES = Integer.MAX_VALUE;
    private static final int BYTES_PER_TYPE = 1;

    private final DiskTier tier;

    public ItemDiskFluidCell(DiskTier tier) {
        this.tier = tier;
        setTranslationKey(Tags.MOD_ID + ".disk_cell_fluid_" + tier.getSuffix());
        setRegistryName("disk_cell_fluid_" + tier.getSuffix());
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
        return AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class);
    }

    // ------------------------------------------------------------------
    // ICellWorkbenchItem
    // ------------------------------------------------------------------

    @Override
    public boolean isEditable(ItemStack itemStack) {
        // タイプフィルター(config)をセルワークベンチで編集できるようにする(item版と同じ)
        return true;
    }

    @Override
    public IItemHandler getUpgradesInventory(ItemStack itemStack) {
        // Inverter Cardのみ1枠(参考元のfluid版DISKと同じ構成。FuzzyはFluidでは非対応)
        return DiskUpgrades.createInventory(itemStack, false);
    }

    @Override
    public IItemHandler getConfigInventory(ItemStack itemStack) {
        // タイプフィルター本体(item版と共通のDiskConfigを流用)
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

        long totalMb = (long) tier.getUsableBytes() * DiskFluidCellInventoryHandler.MB_PER_BYTE;
        tooltip.add(I18n.translateToLocalFormatted(
                "item." + Tags.MOD_ID + ".disk_cell_fluid." + tier.getSuffix() + ".tooltip",
                DiskCapacityFormat.formatFluidMb(totalMb)));

        long usedBytes = getUsedBytesForTooltip(stack);
        if (usedBytes >= 0) {
            tooltip.add(I18n.translateToLocalFormatted(
                    "item." + Tags.MOD_ID + ".disk_cell.used_bytes",
                    usedBytes, tier.getUsableBytes()));
        }

        DiskUpgrades.appendTooltip(tooltip, getUpgradesInventory(stack), false);
    }

    private long getUsedBytesForTooltip(ItemStack stack) {
        try {
            Object handlerObj = AEApi.instance().registries().cell()
                    .getCellInventory(stack, null, getChannel());
            if (handlerObj instanceof DiskFluidCellInventoryHandler) {
                return ((DiskFluidCellInventoryHandler) handlerObj).getUsedBytes();
            }
        } catch (Exception | LinkageError e) {

        }
        return -1;
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World worldIn, EntityPlayer playerIn, EnumHand handIn) {
        ItemStack stack = playerIn.getHeldItem(handIn);
        if (playerIn.isSneaking() && !worldIn.isRemote) {
            if (isEmpty(stack)) {
                ItemStack base = getDowngradeBaseStack();
                // バグ修正: 以前はハウジングのみドロップし、対応するコンポーネント
                // (appliedenergistics2:material 54〜57 / nae2:material 5〜8)を
                // 一切返していなかった(アイテム版ItemDiskCellでは返しているのに非対称だった)。
                // disk_cell_fluid_*.json のレシピと対になるよう、item版と同じ考え方で
                // tier.getFluidComponentMeta()経由のコンポーネントを返す。
                ItemStack component = createComponentStack(tier);
                if (!base.isEmpty()) {
                    stack.shrink(1);
                    playerIn.entityDropItem(base, 0.25F);
                    if (!component.isEmpty()) {
                        playerIn.entityDropItem(component, 0.25F);
                    }
                    return new ActionResult<>(EnumActionResult.SUCCESS, stack);
                }
            }
        }
        return super.onItemRightClick(worldIn, playerIn, handIn);
    }

    private boolean isEmpty(ItemStack stack) {
        try {
            Object handlerObj = AEApi.instance().registries().cell()
                    .getCellInventory(stack, null, getChannel());
            if (handlerObj instanceof DiskFluidCellInventoryHandler) {
                return ((DiskFluidCellInventoryHandler) handlerObj).isEmpty();
            }
        } catch (Exception | LinkageError e) {
            return false;
        }
        return false;
    }


    private ItemStack getDowngradeBaseStack() {
        return new ItemStack(ModDiskItems.DISK_HOUSING);
    }

    /**
     * 分解時に返すコンポーネントを作る(液体版)。tier.hasFluidComponent()がfalse
     * (MAXティアなど対応レシピが無い場合)はItemStack.EMPTYを返す。
     * アイテム版ItemDiskCell#createComponentStackと同じ考え方だが、meta値は
     * tier.getFluidComponentMeta()(disk_cell_fluid_*.jsonのレシピに対応する値)を使う。
     */
    private static ItemStack createComponentStack(DiskTier tier) {
        if (!tier.hasFluidComponent()) {
            return ItemStack.EMPTY;
        }
        Item material = Item.getByNameOrId(tier.getComponentItemId());
        if (material == null) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(material, 1, tier.getFluidComponentMeta());
    }
}