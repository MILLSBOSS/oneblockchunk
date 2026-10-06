package com.millsboss.oneblockchunk.client;

import com.millsboss.oneblockchunk.client.mixin.LivingEntityAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * One live view sent by the plugin: a box of real blocks from somewhere else (the pocket
 * chunk, or the land around a OneBlock Chunk) plus the real mobs and players in it, drawn
 * at {@link #scale} with its corner at {@link #ox},{@link #oy},{@link #oz} in this world.
 */
final class LiveView {

	// Fluids aren't block models, so they are drawn as tinted glass, like the plugin's copies.
	private static final BlockState WATER_LOOK = Protocol.state("minecraft:light_blue_stained_glass");
	private static final BlockState LAVA_LOOK = Protocol.state("minecraft:orange_stained_glass");

	final int id;
	final boolean insideOut;
	final int sx0, sy0, sz0, w, h, d;
	final double ox, oy, oz;
	final float scale;

	private final BlockState[] blocks;
	/** Only blocks with a face that can be seen get a render state. */
	private final Map<Integer, BlockModelRenderState> visible = new HashMap<>();
	final Map<Integer, MirroredEntity> entities = new HashMap<>();
	private int light = LightCoordsUtil.FULL_SKY;

	LiveView(int id, boolean insideOut, int sx0, int sy0, int sz0, int w, int h, int d,
			double ox, double oy, double oz, float scale, BlockState[] blocks) {
		this.id = id;
		this.insideOut = insideOut;
		this.sx0 = sx0;
		this.sy0 = sy0;
		this.sz0 = sz0;
		this.w = w;
		this.h = h;
		this.d = d;
		this.ox = ox;
		this.oy = oy;
		this.oz = oz;
		this.scale = scale;
		this.blocks = blocks;
		for (int i = 0; i < blocks.length; i++) refresh(i);
	}

	// ---------- blocks ----------

	private int index(int x, int y, int z) {
		return (y * d + z) * w + x;
	}

	private BlockState at(int x, int y, int z) {
		if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= d) return null;
		return blocks[index(x, y, z)];
	}

	void setBlock(int x, int y, int z, BlockState state) {
		if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= d) return;
		blocks[index(x, y, z)] = state;
		refresh(index(x, y, z));
		int[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		for (int[] o : n) {
			int nx = x + o[0], ny = y + o[1], nz = z + o[2];
			if (at(nx, ny, nz) != null) refresh(index(nx, ny, nz));
		}
	}

	private void refresh(int i) {
		BlockState s = blocks[i];
		if (s.isAir() || !hasOpenFace(i)) {
			visible.remove(i);
			return;
		}
		BlockState look = s.getBlock() instanceof LiquidBlock ? (s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? LAVA_LOOK : WATER_LOOK) : s;
		BlockModelRenderState rs = visible.computeIfAbsent(i, k -> new BlockModelRenderState());
		rs.clear();
		Render.resolver().update(rs, look, Render.displayContext());
		if (rs.isEmpty()) visible.remove(i);
	}

	private boolean hasOpenFace(int i) {
		int x = i % w, z = (i / w) % d, y = i / (w * d);
		int[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		for (int[] o : n) {
			BlockState nb = at(x + o[0], y + o[1], z + o[2]);
			if (nb == null) {
				// the edge of the box: the bottom of the mini chunk sits on the block, so it is hidden
				if (!insideOut && o[1] == -1) continue;
				return true;
			}
			if (!nb.canOcclude()) return true;
		}
		return false;
	}

	// ---------- each client tick ----------

	void tick(ClientLevel level) {
		if (insideOut) {
			light = LightCoordsUtil.pack(0, 15);
		} else {
			BlockPos p = BlockPos.containing(ox + 0.5, oy, oz + 0.5);
			light = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, p), level.getBrightness(LightLayer.SKY, p));
		}
		Iterator<MirroredEntity> it = entities.values().iterator();
		while (it.hasNext()) {
			MirroredEntity m = it.next();
			if (!m.tick()) it.remove();
		}
	}

	// ---------- drawing ----------

	void submit(PoseStack pose, SubmitNodeCollector out, CameraRenderState camera, float partialTick) {
		Vec3 cam = camera.pos;
		pose.pushPose();
		pose.translate(ox - cam.x, oy - cam.y, oz - cam.z);
		pose.scale(scale, scale, scale);
		for (Map.Entry<Integer, BlockModelRenderState> e : visible.entrySet()) {
			int i = e.getKey();
			int x = i % w, z = (i / w) % d, y = i / (w * d);
			pose.pushPose();
			pose.translate(x, y, z);
			e.getValue().submit(pose, out, light, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
		}
		pose.popPose();

		EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
		for (MirroredEntity m : entities.values()) {
			if (m.broken) continue;
			try {
				EntityRenderState state = dispatcher.extractEntity(m.entity, partialTick);
				state.lightCoords = light;
				state.shadowPieces.clear();
				state.shadowRadius = 0;
				state.nameTag = null;
				double dx = ox + (state.x - sx0) * scale;
				double dy = oy + (state.y - sy0) * scale;
				double dz = oz + (state.z - sz0) * scale;
				pose.pushPose();
				pose.translate(dx - cam.x, dy - cam.y, dz - cam.z);
				pose.scale(scale, scale, scale);
				dispatcher.submit(state, camera, 0, 0, 0, pose, out);
				pose.popPose();
			} catch (RuntimeException ex) {
				m.broken = true; // some entity the renderer can't draw out of context: skip it from now on
				OneBlockChunkClient.LOG.warn("OneBlockChunk: can't draw mirrored {}: {}", m.entity.getType(), ex.toString());
			}
		}
	}

	/** A real mob or player from the other side, kept up to date by the plugin. */
	static final class MirroredEntity {
		final Entity entity;
		boolean broken;
		boolean dying;
		boolean hasTarget;
		double tx, ty, tz;
		float yaw, pitch, headYaw, bodyYaw;

		MirroredEntity(Entity entity) {
			this.entity = entity;
		}

		/** @return false once it should be dropped. */
		boolean tick() {
			Entity e = entity;
			e.setOldPosAndRot();
			LivingEntity le = e instanceof LivingEntity l ? l : null;
			if (le != null) {
				le.yBodyRotO = le.yBodyRot;
				le.yHeadRotO = le.yHeadRot;
			}
			if (hasTarget) {
				e.setPos(tx, ty, tz);
				e.setYRot(yaw);
				e.setXRot(pitch);
				if (le != null) {
					le.yHeadRot = headYaw;
					le.yBodyRot = bodyYaw;
				}
			}
			e.tickCount++;
			if (le != null) {
				// real walking animation, worked out from the real (full size) movement
				le.calculateEntityAnimation(false);
				((LivingEntityAccessor) le).oneblockchunk$swingState().tick();
				if (le.hurtTime > 0) le.hurtTime--;
				if (dying && ++le.deathTime >= 20) return false;
			}
			return !(dying && le == null);
		}
	}
}
