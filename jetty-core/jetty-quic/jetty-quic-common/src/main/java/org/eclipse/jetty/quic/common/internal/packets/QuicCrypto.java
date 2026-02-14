//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.common.internal.packets;

import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.util.StringUtil;

public class QuicCrypto
{
    private static final byte[] INITIAL_SALT_V1 = StringUtil.fromHexString("38762cf7f55934b34d179ae6a4c80cadccbb7f0a");
    private static final byte[] INITIAL_SALT_V2 = StringUtil.fromHexString("0dede3def700a6db819381be6e269dcbf9bd2ed9");
    private static final byte[] RETRY_INTEGRITY_SECRET_V1 = StringUtil.fromHexString("d9c9943e6101fd200021506bcc02814c73030f25c79d71ce876eca876e6fca8e");
    private static final byte[] RETRY_INTEGRITY_SECRET_V2 = StringUtil.fromHexString("c4dd2484d681aefa4ff4d69c2c20299984a765a5d3c31982f38fc74162155e9f");

    /// @return the salt used to derive initial secrets.
    public static byte[] initialSalt(QuicVersion version)
    {
        return switch (version)
        {
            case QuicVersion.V1 -> INITIAL_SALT_V1;
            case QuicVersion.V2 -> INITIAL_SALT_V2;
            default -> throw new AssertionError();
        };
    }

    /// @return the QUIC label used to derive the AEAD secret.
    public static String encryptionLabel(QuicVersion version)
    {
        return switch (version)
        {
            case QuicVersion.V1 -> "quic key";
            case QuicVersion.V2 -> "quicv2 key";
            default -> throw new AssertionError();
        };
    }

    /// @return the QUIC label used to derive the initialization vector secret.
    public static String initializationVectorLabel(QuicVersion version)
    {
        return switch (version)
        {
            case QuicVersion.V1 -> "quic iv";
            case QuicVersion.V2 -> "quicv2 iv";
            default -> throw new AssertionError();
        };
    }

    /// @return the QUIC label used to derive the header protection secret.
    public static String headerProtectionLabel(QuicVersion version)
    {
        return switch (version)
        {
            case QuicVersion.V1 -> "quic hp";
            case QuicVersion.V2 -> "quicv2 hp";
            default -> throw new AssertionError();
        };
    }

    /// @return the QUIC label used to derive the key update secret.
    public static String keyUpdateLabel(QuicVersion version)
    {
        return switch (version)
        {
            case QuicVersion.V1 -> "quic ku";
            case QuicVersion.V2 -> "quicv2 ku";
            default -> throw new AssertionError();
        };
    }

    /// @return the QUIC secret used for `RetryPacket` integrity.
    public static byte[] retryIntegritySecret(QuicVersion version)
    {
        return switch (version)
        {
            case QuicVersion.V1 -> RETRY_INTEGRITY_SECRET_V1;
            case QuicVersion.V2 -> RETRY_INTEGRITY_SECRET_V2;
            default -> throw new AssertionError();
        };
    }

    private QuicCrypto()
    {
    }
}
