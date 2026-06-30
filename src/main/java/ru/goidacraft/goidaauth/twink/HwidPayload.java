package ru.goidacraft.goidaauth.twink;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Optional client→server payload carrying a machine fingerprint.
 * Sent by a companion client mod (not required). When absent, the server
 * falls back to IP-based matching in HARDWARE mode.
 */
public record HwidPayload(String hwid) implements CustomPacketPayload {

    public static final Type<HwidPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("goidaauth", "hwid"));

    public static final StreamCodec<FriendlyByteBuf, HwidPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(256),
            HwidPayload::hwid,
            HwidPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
