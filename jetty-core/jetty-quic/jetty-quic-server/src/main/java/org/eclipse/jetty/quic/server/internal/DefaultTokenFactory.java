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

package org.eclipse.jetty.quic.server.internal;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jetty.quic.server.TokenFactory;
import org.eclipse.jetty.util.NanoTime;

/// The default implementation of [TokenFactory].
///
/// The token has the following structure:
/// ```
/// Token {
///   byte type
///   long nanoTime
///   byte[4|16] ipv4|ipv6 address
///   byte[] odcid
///   byte[] mac
/// }
/// ```
///
// TODO:
//  Issues:
//   1. Port not bound: socketAddressToBytes uses only the IP address (getAddress().getAddress()), not the port. A token could be used from the same IP but a different port. Including the port is stricter, though RFC 9000 only speaks of address validation, and ports are often ephemeral.
//   2. Address length inferred from current connection: In isTokenValid, the number of bytes to read for the address is expectedAddressBytes.length — derived from the current connection, not from what's stored in the token. If the client migrates between IPv4 (4 bytes) and IPv6 (16 bytes) between the
//   two connections, the token is mis-parsed entirely, potentially reading the ODCID bytes as part of the address or vice versa.
//   3. Plaintext contents: The token is plaintext || MAC — the timestamp and IP address are visible to anyone who receives the packet. AEAD encryption would hide them. Not a correctness issue, but a privacy one.
//   4. System.nanoTime() is JVM-relative: It's a monotonic clock relative to JVM startup. This works fine within a single JVM, but NanoTime.millisSince() would give nonsense for tokens issued before a server restart (the nanoTime value would be from a previous JVM lifetime). For Retry tokens (10s) this
//    is low risk; for NEW_TOKEN (15 min) a server restart within the validity window would silently produce wrong elapsed times rather than correctly rejecting the token.
//   5. Ephemeral secret key: The key is generated fresh on each DefaultTokenFactory construction. This means tokens are automatically invalidated on server restart (generally fine) but also means in a cluster, different nodes can't validate each other's tokens — the client may connect to node A, get a
//   token, then reconnect to node B which rejects it and falls back to Retry.
//  Alternative format for confidentiality, but it is not required:
//  nonce || AEAD_Encrypt(
//      key       = HKDF(server_secret, "quic token"),
//      nonce     = nonce,
//      aad       = client_ip || client_port,   // authenticated, not encrypted
//      plaintext = timestamp || original_dcid  // original_dcid only for Retry tokens
//  )

public class DefaultTokenFactory implements TokenFactory
{
    private static final int RETRY_TYPE = 0;
    private static final int NORMAL_TYPE = 1;

    private final SecretKey secretKey;
    private long retryTokenLifetime = 10 * 1000;
    private long tokenLifetime = 15 * 60 * 1000;

    public DefaultTokenFactory()
    {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        secretKey = new SecretKeySpec(key, "HmacSHA256");
    }

    public long getRetryTokenLifetime()
    {
        return retryTokenLifetime;
    }

    public void setRetryTokenLifetime(long retryTokenLifetime)
    {
        this.retryTokenLifetime = retryTokenLifetime;
    }

    public long getTokenLifetime()
    {
        return tokenLifetime;
    }

    public void setTokenLifetime(long tokenLifetime)
    {
        this.tokenLifetime = tokenLifetime;
    }

    @Override
    public byte[] newRetryToken(SocketAddress remoteAddress, byte[] originalDestinationConnectionId) throws Exception
    {
        return newToken(RETRY_TYPE, Objects.requireNonNull(remoteAddress), Objects.requireNonNull(originalDestinationConnectionId));
    }

    @Override
    public byte[] newToken(SocketAddress remoteAddress) throws Exception
    {
        return newToken(NORMAL_TYPE, Objects.requireNonNull(remoteAddress), null);
    }

    private byte[] newToken(int type, SocketAddress remoteAddress, byte[] originalDestinationConnectionId) throws Exception
    {
        byte[] addressBytes = socketAddressToBytes(remoteAddress);
        int length = 1 + 8 + addressBytes.length;
        if (originalDestinationConnectionId != null)
            length += originalDestinationConnectionId.length;
        byte[] bytes = new byte[length];
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        byteBuffer.put((byte)type);
        byteBuffer.putLong(System.nanoTime());
        byteBuffer.put(addressBytes);
        if (originalDestinationConnectionId != null)
            byteBuffer.put(originalDestinationConnectionId);

        Mac mac = Mac.getInstance(secretKey.getAlgorithm());
        mac.init(secretKey);
        mac.update(bytes);
        byte[] macBytes = mac.doFinal();

        byte[] token = new byte[bytes.length + macBytes.length];
        System.arraycopy(bytes, 0, token, 0, bytes.length);
        System.arraycopy(macBytes, 0, token, bytes.length, macBytes.length);
        return token;
    }

    @Override
    public boolean isTokenValid(SocketAddress remoteAddress, byte[] originalDestinationConnectionId, byte[] token)
    {
        try
        {
            ByteBuffer byteBuffer = ByteBuffer.wrap(token);
            byte type = byteBuffer.get();

            // Verify expiration.
            long millis = NanoTime.millisSince(byteBuffer.getLong());
            if (type == RETRY_TYPE && millis > retryTokenLifetime)
                return false;
            if (type == NORMAL_TYPE && millis > tokenLifetime)
                return false;

            // Verify IP address.
            byte[] expectedAddressBytes = socketAddressToBytes(remoteAddress);
            byte[] addressBytes = new byte[expectedAddressBytes.length];
            byteBuffer.get(addressBytes);
            if (!Arrays.equals(addressBytes, expectedAddressBytes))
                return false;

            int length = 1 + 8 + addressBytes.length;
            if (type == RETRY_TYPE)
            {
                // Verify original destination connection id.
                if (originalDestinationConnectionId == null)
                    return false;
                byte[] original = new byte[originalDestinationConnectionId.length];
                byteBuffer.get(original);
                if (!Arrays.equals(original, originalDestinationConnectionId))
                    return false;
                length += original.length;
            }

            // Verify MAC.
            Mac mac = Mac.getInstance(secretKey.getAlgorithm());
            mac.init(secretKey);
            mac.update(token, 0, length);
            byte[] expectedMacBytes = mac.doFinal();
            byte[] macBytes = new byte[token.length - length];
            System.arraycopy(token, length, macBytes, 0, macBytes.length);
            return MessageDigest.isEqual(macBytes, expectedMacBytes);
        }
        catch (Throwable x)
        {
            return false;
        }
    }

    private byte[] socketAddressToBytes(SocketAddress socketAddress)
    {
        byte[] addressBytes;
        if (socketAddress instanceof InetSocketAddress inet)
            addressBytes = inet.getAddress().getAddress();
        else
            addressBytes = Arrays.copyOfRange(socketAddress.toString().getBytes(StandardCharsets.US_ASCII), 0, 16);
        return addressBytes;
    }
}
