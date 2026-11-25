package com.deathnote;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.WritableBookContent;

import java.util.function.Function;

public class ModItems {
    public static final Item DEATH_NOTE = register("death_note", Item::new, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant().component(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY));

    private static <T extends Item> T register (String name, Function<Item.Properties, T> factory, Item.Properties properties) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, DeathNote.id(name));
        T item = factory.apply(properties.setId(key));
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }

    public static void init() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> output.accept(ModItems.DEATH_NOTE));
    }
}