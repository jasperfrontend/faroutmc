package nl.jasperfrontend.farout;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Tells the client whether a run is active, so it knows when to draw the facing line. */
public record RunStatePayload(boolean active) implements CustomPacketPayload {
	public static final Type<RunStatePayload> TYPE =
		new Type<>(Identifier.fromNamespaceAndPath(Farout.MOD_ID, "run_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RunStatePayload> CODEC =
		StreamCodec.composite(ByteBufCodecs.BOOL, RunStatePayload::active, RunStatePayload::new);

	@Override
	public Type<RunStatePayload> type() {
		return TYPE;
	}
}
