package nl.jasperfrontend.farout;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** /farout menu: tells the client to open the settings screen. */
public record OpenMenuPayload() implements CustomPacketPayload {
	public static final OpenMenuPayload INSTANCE = new OpenMenuPayload();
	public static final Type<OpenMenuPayload> TYPE =
		new Type<>(Identifier.fromNamespaceAndPath(Farout.MOD_ID, "open_menu"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OpenMenuPayload> CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<OpenMenuPayload> type() {
		return TYPE;
	}
}
