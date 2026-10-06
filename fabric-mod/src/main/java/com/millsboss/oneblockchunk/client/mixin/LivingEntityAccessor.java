package com.millsboss.oneblockchunk.client.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the live view tick a mirrored entity's arm swing without ticking the whole entity. */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
	@Accessor("swingState")
	LivingEntity.SwingState oneblockchunk$swingState();
}
