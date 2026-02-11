package com.itemflowmonitor.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Клиент → Сервер: создать/обновить/отключить трекер.
 */
public record TrackerConfigC2SPacket(
		BlockPos pos,
		boolean active,
		int modeOrdinal,
		int periodOrdinal,
		int rateModeOrdinal,
		String itemId
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<TrackerConfigC2SPacket> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("itemflowmonitor", "tracker_config"));

	public static final StreamCodec<FriendlyByteBuf, TrackerConfigC2SPacket> CODEC =
			CustomPacketPayload.codec(TrackerConfigC2SPacket::write, TrackerConfigC2SPacket::new);

	private TrackerConfigC2SPacket(FriendlyByteBuf buf) {
		this(buf.readBlockPos(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeBlockPos(pos);
		buf.writeBoolean(active);
		buf.writeVarInt(modeOrdinal);
		buf.writeVarInt(periodOrdinal);
		buf.writeVarInt(rateModeOrdinal);
		buf.writeUtf(itemId);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
