package nl.jasperfrontend.farout;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The lane's edges, so the client can fog up the view the further you stray from it. lateralIsX says
 * which coordinate the edges are on: x when heading north or south, z when heading east or west.
 */
public record LanePayload(boolean active, boolean lateralIsX, double low, double high) implements CustomPacketPayload {
	public static final LanePayload NONE = new LanePayload(false, true, 0, 0);

	public static final Type<LanePayload> TYPE =
		new Type<>(Identifier.fromNamespaceAndPath(Farout.MOD_ID, "lane"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LanePayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.BOOL, LanePayload::active,
		ByteBufCodecs.BOOL, LanePayload::lateralIsX,
		ByteBufCodecs.DOUBLE, LanePayload::low,
		ByteBufCodecs.DOUBLE, LanePayload::high,
		LanePayload::new);

	@Override
	public Type<LanePayload> type() {
		return TYPE;
	}
}
