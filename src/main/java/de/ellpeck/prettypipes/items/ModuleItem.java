package de.ellpeck.prettypipes.items;

import de.ellpeck.prettypipes.Registry;
import de.ellpeck.prettypipes.Utility;
import de.ellpeck.prettypipes.misc.ItemFilter;
import de.ellpeck.prettypipes.pipe.PipeBlockEntity;
import de.ellpeck.prettypipes.pipe.containers.AbstractPipeContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Stack;
import java.util.function.Consumer;

public abstract class ModuleItem extends Item implements IModule {

    private final String name;

    public ModuleItem(String name) {
        super(new Properties().tab(Registry.TAB).stacksTo(16));
        this.name = name;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void appendHoverText(ItemStack stack, @Nullable Level worldIn, List<Component> tooltip, TooltipFlag flagIn) {
        super.appendHoverText(stack, worldIn, tooltip, flagIn);
        Utility.addTooltip(this.name, tooltip);
    }

    @Override
    public void tick(ItemStack module, PipeBlockEntity tile) {

    }

    @Override
    public boolean canNetworkSee(ItemStack module, PipeBlockEntity tile) {
        return true;
    }

    @Override
    public boolean canAcceptItem(ItemStack module, PipeBlockEntity tile, ItemStack stack) {
        return true;
    }

    @Override
    public int getMaxInsertionAmount(ItemStack module, PipeBlockEntity tile, ItemStack stack, IItemHandler destination) {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getPriority(ItemStack module, PipeBlockEntity tile) {
        return 0;
    }

    @Override
    public AbstractPipeContainer<?> getContainer(ItemStack module, PipeBlockEntity tile, int windowId, Inventory inv, Player player, int moduleIndex) {
        return null;
    }

    @Override
    public float getItemSpeedIncrease(ItemStack module, PipeBlockEntity tile) {
        return 0;
    }

    @Override
    public boolean canPipeWork(ItemStack module, PipeBlockEntity tile) {
        return true;
    }

    @Override
    public List<ItemStack> getAllCraftables(ItemStack module, PipeBlockEntity tile) {
        return Collections.emptyList();
    }

    @Override
    public int getCraftableAmount(ItemStack module, PipeBlockEntity tile, Consumer<ItemStack> unavailableConsumer, ItemStack stack, Stack<ItemStack> dependencyChain) {
        return 0;
    }

    @Override
    public ItemStack craft(ItemStack module, PipeBlockEntity tile, BlockPos destPipe, Consumer<ItemStack> unavailableConsumer, ItemStack stack, Stack<ItemStack> dependencyChain) {
        return stack;
    }

    @Override
    public Integer getCustomNextNode(ItemStack module, PipeBlockEntity tile, List<BlockPos> nodes, int index) {
        return null;
    }

    @Override
    public ItemFilter getItemFilter(ItemStack module, PipeBlockEntity tile) {
        return null;
    }
}
