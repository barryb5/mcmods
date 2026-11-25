package com.deathnote.mixin;

import com.deathnote.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.WritableBookItem;
import net.minecraft.world.item.component.WritableBookContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "updateBookContents", at = @At("TAIL"))
    private void deathnote$onBookEdited(List<FilteredText> pages, int slot, CallbackInfo ci) {
        ItemStack stack = player.getInventory().getItem(slot);
        if (!stack.is(ModItems.DEATH_NOTE)) {
            return;
        }

        WritableBookContent content = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
    }
}