package org.eclipse.jetty.quic.common.internal.packets;

import org.eclipse.jetty.quic.api.QuicVersion;

public class QuicCrypto
{
    private static final byte[] INITIAL_SALT_V1 = new byte[]
    {
        (byte)0x38, (byte)0x76, (byte)0x2C, (byte)0xF7, (byte)0xF5, (byte)0x59, (byte)0x34, (byte)0xB3,
        (byte)0x4D, (byte)0x17, (byte)0x9A, (byte)0xE6, (byte)0xA4, (byte)0xC8, (byte)0x0C, (byte)0xAD,
        (byte)0xCC, (byte)0xBB, (byte)0x7F, (byte)0x0A
    };
    private static final byte[] INITIAL_SALT_V2 = new byte[]
    {
        (byte)0x0D, (byte)0xED, (byte)0xE3, (byte)0xDE, (byte)0xF7, (byte)0x00, (byte)0xA6, (byte)0xDB,
        (byte)0x81, (byte)0x93, (byte)0x81, (byte)0xBE, (byte)0x6E, (byte)0x26, (byte)0x9D, (byte)0xCB,
        (byte)0xF9, (byte)0xBD, (byte)0x2E, (byte)0xD9
    };

    /// @return the salt used to derive initial secrets.
    public static byte[] initialSalt(QuicVersion version)
    {
        return switch (version)
        {
            case QuicVersion.V1 -> INITIAL_SALT_V1;
            case QuicVersion.V2 -> INITIAL_SALT_V2;
        };
    }

    /// @return the QUIC label used to derive the AEAD secret.
    public static String encryptionLabel(QuicVersion version)
    {
        return switch (version)
        {
            case QuicVersion.V1 -> "quic key";
            case QuicVersion.V2 -> "quicv2 key";
        };
    }

    /// @return the QUIC label used to derive the initialization vector secret.
    public static String initializationVectorLabel(QuicVersion version)
    {
        return switch (version)
        {
            case QuicVersion.V1 -> "quic iv";
            case QuicVersion.V2 -> "quicv2 iv";
        };
    }

    /// @return the QUIC label used to derive the header protection secret.
    public static String headerProtectionLabel(QuicVersion version)
    {
        return switch (version)
        {
            case QuicVersion.V1 -> "quic hp";
            case QuicVersion.V2 -> "quicv2 hp";
        };
    }

    /// @return the QUIC label used to derive the key update secret.
    public static String keyUpdateLabel(QuicVersion version)
    {
        return switch (version)
        {
            case QuicVersion.V1 -> "quic ku";
            case QuicVersion.V2 -> "quicv2 ku";
        };
    }

    private QuicCrypto()
    {
    }
}
