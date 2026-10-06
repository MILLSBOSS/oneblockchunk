package com.millsboss.oneblockchunk.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.InflaterInputStream;

/** Reads the plugin's messages. Must match {@code ModLink} in the plugin. */
final class Protocol {

	static final int VERSION = 1;
	private static final byte OPEN = 1, CLOSE = 2, BLOCKS = 3, ADD = 4, STATE = 5, REMOVE = 6, ACTION = 7, EQUIP = 8;

	private static final Map<String, BlockState> STATES = new HashMap<>();

	private Protocol() {
	}

	static void handle(byte[] data, ClientLevel level, Map<Integer, LiveView> views) throws IOException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		byte type = in.readByte();
		switch (type) {
			case OPEN -> {
				int version = in.readInt();
				if (version != VERSION) {
					OneBlockChunkClient.LOG.warn("OneBlockChunk: server speaks protocol {}, this mod speaks {}; update the mod or plugin.", version, VERSION);
					return;
				}
				int id = in.readInt();
				boolean insideOut = in.readBoolean();
				int sx0 = in.readInt(), sy0 = in.readInt(), sz0 = in.readInt();
				int w = in.readInt(), h = in.readInt(), d = in.readInt();
				double ox = in.readDouble(), oy = in.readDouble(), oz = in.readDouble();
				float scale = in.readFloat();
				byte[] packed = new byte[in.readInt()];
				in.readFully(packed);
				BlockState[] blocks = new BlockState[w * h * d];
				try (DataInputStream z = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(packed)))) {
					BlockState[] palette = new BlockState[z.readInt()];
					for (int i = 0; i < palette.length; i++) palette[i] = state(z.readUTF());
					for (int i = 0; i < blocks.length; i++) blocks[i] = palette[readVarInt(z)];
				}
				views.put(id, new LiveView(id, insideOut, sx0, sy0, sz0, w, h, d, ox, oy, oz, scale, blocks));
				OneBlockChunkClient.LOG.info("OneBlockChunk: live view {} opened ({}, {}x{}x{} blocks at {}x scale)",
						id, insideOut ? "outside world around you" : "chunk inside a OneBlock Chunk", w, h, d, scale);
			}
			case CLOSE -> {
				int id = in.readInt();
				if (views.remove(id) != null) OneBlockChunkClient.LOG.info("OneBlockChunk: live view {} closed", id);
			}
			case BLOCKS -> {
				LiveView v = views.get(in.readInt());
				int n = in.readInt();
				for (int i = 0; i < n; i++) {
					int x = in.readShort(), y = in.readShort(), z = in.readShort();
					BlockState s = state(in.readUTF());
					if (v != null) v.setBlock(x, y, z, s);
				}
			}
			case ADD -> {
				LiveView v = views.get(in.readInt());
				int key = in.readInt();
				byte kind = in.readByte();
				Entity e;
				if (kind == 1) {
					UUID uuid = new UUID(in.readLong(), in.readLong());
					e = new RemotePlayer(level, new GameProfile(uuid, in.readUTF()));
				} else {
					String typeId = in.readUTF();
					byte[] nbt = new byte[in.readInt()];
					in.readFully(nbt);
					e = mob(level, typeId, nbt);
				}
				if (v == null || e == null) return;
				e.setId(-1_000_000 - key);
				v.entities.put(key, new LiveView.MirroredEntity(e));
			}
			case STATE -> {
				LiveView v = views.get(in.readInt());
				int key = in.readInt();
				double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
				float yaw = in.readFloat(), pitch = in.readFloat(), head = in.readFloat(), body = in.readFloat();
				String pose = in.readUTF();
				int flags = in.readByte();
				LiveView.MirroredEntity m = v == null ? null : v.entities.get(key);
				if (m == null) return;
				boolean first = !m.hasTarget;
				m.tx = x;
				m.ty = y;
				m.tz = z;
				m.yaw = yaw;
				m.pitch = pitch;
				m.headYaw = head;
				m.bodyYaw = body;
				m.hasTarget = true;
				Entity e = m.entity;
				if (first) { // no sliding in from wherever the entity was created
					e.setPos(x, y, z);
					e.setOldPosAndRot();
				}
				e.setPose(pose(pose));
				e.setSharedFlagOnFire((flags & 1) != 0);
				e.setShiftKeyDown((flags & 2) != 0);
				e.setSprinting((flags & 4) != 0);
				e.setInvisible((flags & 8) != 0);
				if (e instanceof TamableAnimal t) t.setInSittingPose((flags & 64) != 0);
				if (e instanceof LivingEntity le) {
					boolean using = (flags & 16) != 0;
					InteractionHand hand = (flags & 32) != 0 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
					if (using && !le.isUsingItem()) le.startUsingItem(hand);
					else if (!using && le.isUsingItem()) le.stopUsingItem();
				}
			}
			case REMOVE -> {
				LiveView v = views.get(in.readInt());
				int key = in.readInt();
				boolean died = in.readBoolean();
				if (v == null) return;
				LiveView.MirroredEntity m = v.entities.get(key);
				if (m == null) return;
				if (died && m.entity instanceof LivingEntity le) {
					le.setHealth(0); // plays the real death animation, then the tick drops it
					m.dying = true;
				} else {
					v.entities.remove(key);
				}
			}
			case ACTION -> {
				LiveView v = views.get(in.readInt());
				int key = in.readInt();
				byte action = in.readByte();
				LiveView.MirroredEntity m = v == null ? null : v.entities.get(key);
				if (m == null || !(m.entity instanceof LivingEntity le)) return;
				switch (action) {
					case 0 -> le.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
					case 1 -> le.swing(InteractionHand.OFF_HAND, SwingAnimation.DEFAULT, false);
					case 2 -> le.animateHurt(0);
					default -> {
					}
				}
			}
			case EQUIP -> {
				LiveView v = views.get(in.readInt());
				int key = in.readInt();
				EquipmentSlot[] slots = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
				ItemStack[] items = new ItemStack[slots.length];
				for (int i = 0; i < slots.length; i++) items[i] = item(in.readUTF());
				LiveView.MirroredEntity m = v == null ? null : v.entities.get(key);
				if (m == null || !(m.entity instanceof LivingEntity le)) return;
				for (int i = 0; i < slots.length; i++) le.setItemSlot(slots[i], items[i]);
			}
			default -> OneBlockChunkClient.LOG.debug("OneBlockChunk: unknown message {}", type);
		}
	}

	private static Entity mob(ClientLevel level, String typeId, byte[] nbt) {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(typeId)).orElse(null);
		if (type == null) return null;
		try {
			CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(nbt), NbtAccounter.unlimitedHeap());
			ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag);
			Entity e = EntityType.create(type, input, level, EntitySpawnReason.LOAD).orElse(null);
			if (e != null) return e;
		} catch (IOException | RuntimeException ex) {
			OneBlockChunkClient.LOG.debug("OneBlockChunk: falling back to a plain {}: {}", typeId, ex.toString());
		}
		return type.create(level, EntitySpawnReason.LOAD);
	}

	private static Pose pose(String name) {
		if (name.equals("SNEAKING")) return Pose.CROUCHING; // Bukkit's name for it
		try {
			return Pose.valueOf(name);
		} catch (IllegalArgumentException ex) {
			return Pose.STANDING;
		}
	}

	private static ItemStack item(String id) {
		if (id.isEmpty()) return ItemStack.EMPTY;
		return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).map(ItemStack::new).orElse(ItemStack.EMPTY);
	}

	static BlockState state(String s) {
		return STATES.computeIfAbsent(s, k -> {
			try {
				return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, k, false).blockState();
			} catch (Exception ex) {
				return Blocks.AIR.defaultBlockState();
			}
		});
	}

	private static int readVarInt(DataInputStream in) throws IOException {
		int value = 0, shift = 0;
		byte b;
		do {
			b = in.readByte();
			value |= (b & 0x7F) << shift;
			shift += 7;
		} while ((b & 0x80) != 0);
		return value;
	}
}
