package com.itemflowmonitor.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Сервер → Клиент: текущие данные трекера (rate, режим, предмет).
 */
public record TrackerUpdateS2CPacket(
		BlockPos pos,
		boolean active,
		double rate,
		int modeOrdinal,
		int periodOrdinal,
		int rateModeOrdinal,
		String trackedItemId,
		int currentCount,
		int maxCapacity
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<TrackerUpdateS2CPacket> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("itemflowmonitor", "tracker_update"));

	public static final StreamCodec<FriendlyByteBuf, TrackerUpdateS2CPacket> CODEC =
			CustomPacketPayload.codec(TrackerUpdateS2CPacket::write, TrackerUpdateS2CPacket::new);

	private TrackerUpdateS2CPacket(FriendlyByteBuf buf) {
		this(buf.readBlockPos(), buf.readBoolean(), buf.readDouble(),
				buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(),
				buf.readVarInt(), buf.readVarInt());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeBlockPos(pos);
		buf.writeBoolean(active);
		buf.writeDouble(rate);
		buf.writeVarInt(modeOrdinal);
		buf.writeVarInt(periodOrdinal);
		buf.writeVarInt(rateModeOrdinal);
		buf.writeUtf(trackedItemId);
		buf.writeVarInt(currentCount);
		buf.writeVarInt(maxCapacity);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
