package de.ellpeck.prettypipes.pipe.containers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import de.ellpeck.prettypipes.PrettyPipes;
import de.ellpeck.prettypipes.Registry;
import de.ellpeck.prettypipes.items.IModule;
import de.ellpeck.prettypipes.packets.PacketButton;
import de.ellpeck.prettypipes.packets.PacketHandler;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractPipeGui<T extends AbstractPipeContainer<?>> extends AbstractContainerScreen<T> {

    protected static final ResourceLocation TEXTURE = new ResourceLocation(PrettyPipes.ID, "textures/gui/pipe.png");
    private final List<Tab> tabs = new ArrayList<>();
    private final ItemStack[] lastItems = new ItemStack[this.menu.tile.modules.getSlots()];

    public AbstractPipeGui(T screenContainer, Inventory inv, Component titleIn) {
        super(screenContainer, inv, titleIn);
        this.imageWidth = 176;
        this.imageHeight = 171 + 32;
    }

    @Override
    protected void init() {
        super.init();
        this.initTabs();
    }

    @Override
    public void containerTick() {
        super.containerTick();

        var changed = false;
        for (var i = 0; i < this.menu.tile.modules.getSlots(); i++) {
            var stack = this.menu.tile.modules.getStackInSlot(i);
            if (stack != this.lastItems[i]) {
                this.lastItems[i] = stack;
                changed = true;
            }
        }
        if (changed)
            this.initTabs();
    }

    @Override
    public void render(PoseStack matrix, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(matrix);
        super.render(matrix, mouseX, mouseY, partialTicks);
        for (var widget : this.renderables) {
            if (widget instanceof AbstractWidget abstractWidget) {
                if (abstractWidget.isHoveredOrFocused())
                    abstractWidget.renderToolTip(matrix, mouseX, mouseY);
            }
        }
        this.renderTooltip(matrix, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(PoseStack matrix, int mouseX, int mouseY) {
        this.font.draw(matrix, this.playerInventoryTitle.getString(), 8, this.imageHeight - 96 + 2, 4210752);
        this.font.draw(matrix, this.title.getString(), 8, 6 + 32, 4210752);
        for (var tab : this.tabs)
            tab.drawForeground(matrix, mouseX, mouseY);
    }

    @Override
    protected void renderBg(PoseStack matrix, float partialTicks, int mouseX, int mouseY) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, TEXTURE);
        this.blit(matrix, this.leftPos, this.topPos + 32, 0, 0, 176, 171);

        for (var tab : this.tabs)
            tab.draw(matrix);

        // draw the slots since we're using a blank ui
        for (var slot : this.menu.slots) {
            if (slot instanceof SlotItemHandler)
                this.blit(matrix, this.leftPos + slot.x - 1, this.topPos + slot.y - 1, 176, 62, 18, 18);
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        for (var tab : this.tabs) {
            if (tab.onClicked(x, y, button))
                return true;
        }
        return super.mouseClicked(x, y, button);
    }

    private void initTabs() {
        this.tabs.clear();
        this.tabs.add(new Tab(new ItemStack(Registry.pipeBlock), 0, -1));
        for (var i = 0; i < this.menu.tile.modules.getSlots(); i++) {
            var stack = this.menu.tile.modules.getStackInSlot(i);
            if (stack.isEmpty())
                continue;
            var module = (IModule) stack.getItem();
            if (module.hasContainer(stack, this.menu.tile))
                this.tabs.add(new Tab(stack, this.tabs.size(), i));
        }
    }

    private class Tab {

        private final ItemStack moduleStack;
        private final int index;
        private final int x;
        private final int y;

        public Tab(ItemStack moduleStack, int tabIndex, int index) {
            this.moduleStack = moduleStack;
            this.index = index;
            this.x = AbstractPipeGui.this.leftPos + 5 + tabIndex * 28;
            this.y = AbstractPipeGui.this.topPos;
        }

        private void draw(PoseStack matrix) {
            var y = 2;
            var v = 0;
            var height = 30;
            var itemOffset = 9;
            if (this.index == AbstractPipeGui.this.menu.moduleIndex) {
                y = 0;
                v = 30;
                height = 32;
                itemOffset = 7;
            }
            AbstractPipeGui.this.blit(matrix, this.x, this.y + y, 176, v, 28, height);

            AbstractPipeGui.this.itemRenderer.renderGuiItem(this.moduleStack, this.x + 6, this.y + itemOffset);
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, TEXTURE);
        }

        private void drawForeground(PoseStack matrix, int mouseX, int mouseY) {
            if (mouseX < this.x || mouseY < this.y || mouseX >= this.x + 28 || mouseY >= this.y + 32)
                return;
            AbstractPipeGui.this.renderTooltip(matrix, this.moduleStack.getHoverName(), mouseX - AbstractPipeGui.this.leftPos, mouseY - AbstractPipeGui.this.topPos);
        }

        private boolean onClicked(double mouseX, double mouseY, int button) {
            if (this.index == AbstractPipeGui.this.menu.moduleIndex)
                return false;
            if (button != 0)
                return false;
            if (mouseX < this.x || mouseY < this.y || mouseX >= this.x + 28 || mouseY >= this.y + 32)
                return false;
            PacketHandler.sendToServer(new PacketButton(AbstractPipeGui.this.menu.tile.getBlockPos(), PacketButton.ButtonResult.PIPE_TAB, this.index));
            AbstractPipeGui.this.getMinecraft().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
            return true;
        }
    }
}
