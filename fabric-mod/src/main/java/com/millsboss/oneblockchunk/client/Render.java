package com.millsboss.oneblockchunk.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;

/** Shared block model lookup, the same one block displays use. */
final class Render {

	private static BlockModelResolver resolver;
	private static BlockDisplayContext context;

	private Render() {
	}

	static BlockModelResolver resolver() {
		if (resolver == null) resolver = new BlockModelResolver(Minecraft.getInstance().getModelManager());
		return resolver;
	}

	static BlockDisplayContext displayContext() {
		if (context == null) context = BlockDisplayContext.create();
		return context;
	}

	/** Models were reloaded (resource packs): rebuild lookups on next use. */
	static void reset() {
		resolver = null;
		context = null;
	}
}
