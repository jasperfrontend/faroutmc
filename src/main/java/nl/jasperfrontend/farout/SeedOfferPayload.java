package nl.jasperfrontend.farout;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Sent when a run ends, so the client can offer to save the seed it was run on. direction is a
 * {@link Heading#id()}. The saved seeds live on the client, so they're the player's own on any server.
 */
public record SeedOfferPayload(long seed, String direction) implements CustomPacketPayload {
	public static final Type<SeedOfferPayload> TYPE =
		new Type<>(Identifier.fromNamespaceAndPath(Farout.MOD_ID, "seed_offer"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SeedOfferPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_LONG, SeedOfferPayload::seed,
		ByteBufCodecs.STRING_UTF8, SeedOfferPayload::direction,
		SeedOfferPayload::new);

	@Override
	public Type<SeedOfferPayload> type() {
		return TYPE;
	}
}
