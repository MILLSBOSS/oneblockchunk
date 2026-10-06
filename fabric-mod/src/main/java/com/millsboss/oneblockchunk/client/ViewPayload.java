package com.millsboss.oneblockchunk.client;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Raw bytes from the plugin on the {@code oneblockchunk:view} channel; {@link Protocol} reads them. */
public record ViewPayload(byte[] data) implements CustomPacketPayload {

	public static final Type<ViewPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("oneblockchunk", "view"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ViewPayload> CODEC = StreamCodec.of(
			(buf, payload) -> buf.writeBytes(payload.data),
			buf -> {
				byte[] bytes = new byte[buf.readableBytes()];
				buf.readBytes(bytes);
				return new ViewPayload(bytes);
			});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
