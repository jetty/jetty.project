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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.TransportParametersGenerator;
import org.eclipse.jetty.quic.common.TransportParametersParser;
import org.eclipse.jetty.quic.common.tls.HandshakeData;
import org.eclipse.jetty.quic.server.SessionTicket;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.TLSVersion;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.thread.AutoLock;

/// An implementation of [SessionTicket.Factory] that produces
/// [SessionTicket]s for the stateless server case.
///
/// Session tickets produced by this class contain all the
/// information to resume a connection and therefore require
/// no server-side state to store such information.
///
/// Plaintext structure of a serialized [SessionTicket]:
///
/// | Field | Length | Notes |
/// |-------|--------|-------|
/// | epoch_millis | 8 | Epoch millis to use tickets across server restarts |
/// | lifetime_millis | 4 | Ticket lifetime in milliseconds |
/// | age_add | 4 | To obscure the ticket age |
/// | nonce_length | 1 | |
/// | nonce | N | The ticket nonce |
/// | quic_version | 2 | Negotiated QUIC version |
/// | tls_version | 2 | Negotiated TLS version |
/// | cipher_suite | 2 | Negotiated cipher suite |
/// | server_name_length | 1 | |
/// | server_name_protocol | N | Server name |
/// | application_protocol_length | 1 | |
/// | application_protocol | N | Negotiated application protocol |
/// | transport_parameters_length | 2 | |
/// | transport_parameters | N | Server QUIC transport parameters |
/// | resumption_secret_length | 1 | |
/// | resumption_secret | N | The resumption secret |
///
/// The plaintext structure is encrypted so that the final structure is:
/// ```
/// [cipher_nonce][encrypted_ticket][aead_tag]
/// ```
///
/// This implementation encrypts the plaintext structure with keys that
/// encryption keys are rotated every [#getKeyRotationPeriod()].
/// Encryption key rotation provides forward secrecy so that if a key
/// is compromised, tickets issued with older keys cannot be decrypted.
///
/// Decryption keys are kept around for the [#getSessionTicketLifetime()]
/// so that old tickets can still be decrypted and used.
///
/// In this way, the memory necessary for the server-side state is limited
/// and does not depend on the number of [SessionTicket]s.
public class DefaultSessionTicketFactory implements SessionTicket.Factory
{
    private static final int NONCE_LENGTH = 12;

    private final SecureRandom random = new SecureRandom();

    private final AutoLock lock = new AutoLock();
    // Most recent key at the head.
    private final Deque<TicketKey> ticketKeys = new ArrayDeque<>();
    private final TransportParametersGenerator generator = new TransportParametersGenerator();
    private Duration sessionTicketLifetime = Duration.ofHours(6);
    private Duration keyRotationPeriod = Duration.ofHours(1);

    public Duration getSessionTicketLifetime()
    {
        return sessionTicketLifetime;
    }

    public void setSessionTicketLifetime(Duration sessionTicketLifetime)
    {
        // RFC-8446 #4.6.1
        if (sessionTicketLifetime.minus(Duration.ofDays(7)).isPositive())
            throw new IllegalArgumentException("invalid sessionTicketLifetime: " + sessionTicketLifetime);
        this.sessionTicketLifetime = sessionTicketLifetime;
    }

    public Duration getKeyRotationPeriod()
    {
        return keyRotationPeriod;
    }

    public void setKeyRotationPeriod(Duration keyRotationPeriod)
    {
        this.keyRotationPeriod = keyRotationPeriod;
    }

    @Override
    public byte[] generateSessionTicket(SessionTicket ticket)
    {
        try
        {
            TicketKey key;
            try (var _ = lock.lock())
            {
                key = ticketKeys.peekFirst();
                if (key == null || NanoTime.secondsSince(key.nanoTime()) > getKeyRotationPeriod().toSeconds())
                    key = lockedRotateKey();
            }

            SessionTicket.Configuration configuration = ticket.configuration();
            HandshakeData handshake = ticket.handshakeData();
            byte[] serverName = handshake.serverName().getBytes(StandardCharsets.UTF_8);
            String appProtocol = handshake.applicationProtocol();
            byte[] protocol = appProtocol == null ? BufferUtil.EMPTY_BYTES : appProtocol.getBytes(StandardCharsets.US_ASCII);
            RetainableByteBuffer.DynamicCapacity accumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
            int length = generator.generate(accumulator, handshake.transportParameters());
            byte[] transportParameters = new byte[length];
            accumulator.get(transportParameters, 0, length);
            byte[] resumptionSecret = ticket.resumptionMasterSecret().getEncoded();
            int capacity = 8 + 4 + 4 +
                1 + configuration.nonce().length +
                2 + 2 + 2 +
                1 + serverName.length +
                1 + protocol.length +
                transportParameters.length +
                1 + resumptionSecret.length;
            byte[] plainText = new byte[capacity];
            ByteBuffer.wrap(plainText)
                .putLong(Instant.now().toEpochMilli())
                .putInt(configuration.lifetime())
                .putInt(configuration.ageAdd())
                .put((byte)configuration.nonce().length)
                .put(configuration.nonce())
                .putShort((short)handshake.quicVersion().code())
                .putShort((short)handshake.tlsVersion().code())
                .putShort((short)handshake.cipherSuite().code())
                .put((byte)serverName.length)
                .put(serverName)
                .put((byte)protocol.length)
                .put(protocol)
                .put(transportParameters)
                .put((byte)resumptionSecret.length)
                .put(resumptionSecret);

            return encrypt(key.secretKey(), plainText);
        }
        catch (Throwable x)
        {
            return null;
        }
    }

    private byte[] encrypt(SecretKey secretKey, byte[] plainText) throws Exception
    {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] cipherNonce = new byte[NONCE_LENGTH];
        random.nextBytes(cipherNonce);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(128, cipherNonce));
        byte[] encrypted = cipher.doFinal(plainText);
        byte[] ticketBytes = new byte[cipherNonce.length + encrypted.length];
        System.arraycopy(cipherNonce, 0, ticketBytes, 0, cipherNonce.length);
        System.arraycopy(encrypted, 0, ticketBytes, cipherNonce.length, encrypted.length);
        return ticketBytes;
    }

    @Override
    public SessionTicket parseSessionTicket(byte[] bytes)
    {
        byte[] decrypted = decrypt(bytes);
        if (decrypted == null)
            return null;

        ByteBuffer byteBuffer = ByteBuffer.wrap(decrypted);

        long epochMillis = byteBuffer.getLong();
        int lifetime = byteBuffer.getInt();
        if (Instant.ofEpochMilli(epochMillis).plusMillis(lifetime).isBefore(Instant.now()))
            return null;

        int ageAdd = byteBuffer.getInt();
        int nonceLength = byteBuffer.get() & 0xFF;
        byte[] nonce = new byte[nonceLength];
        byteBuffer.get(nonce);
        SessionTicket.Configuration configuration = new SessionTicket.Configuration(lifetime, ageAdd, nonce);

        QuicVersion quicVersion = QuicVersion.from(byteBuffer.getShort() & 0xFFFF);
        TLSVersion tlsVersion = TLSVersion.from(byteBuffer.getShort() & 0xFFFF);
        CipherSuite cipherSuite = CipherSuite.from(byteBuffer.getShort() & 0xFFFF);
        int serverNameLength = byteBuffer.get() & 0xFF;
        byte[] serverNameBytes = new byte[serverNameLength];
        byteBuffer.get(serverNameBytes);
        String serverName = new String(serverNameBytes, StandardCharsets.UTF_8);
        int protocolLength = byteBuffer.get() & 0xFF;
        byte[] protocolBytes = new byte[protocolLength];
        byteBuffer.get(protocolBytes);
        String protocol = new String(protocolBytes, StandardCharsets.US_ASCII);
        TransportParameters transportParameters = new TransportParametersParser(new VarLenInt()).parse(byteBuffer);
        HandshakeData handshake = new HandshakeData(quicVersion, tlsVersion, serverName, cipherSuite, protocol, transportParameters);

        int resumptionSecretLength = byteBuffer.get() & 0xFF;
        byte[] resumptionSecretBytes = new byte[resumptionSecretLength];
        byteBuffer.get(resumptionSecretBytes);
        SecretKey resumptionMasterSecret = new SecretKeySpec(resumptionSecretBytes, "AES");

        return new SessionTicket(configuration, handshake, resumptionMasterSecret);
    }

    private byte[] decrypt(byte[] bytes)
    {
        List<TicketKey> keys;
        try (var _ = lock.lock())
        {
            keys = List.copyOf(ticketKeys);
        }
        // Try decryption using all the ticket
        // keys, starting from the most recent.
        for (TicketKey ticketKey : keys)
        {
            try
            {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                byte[] cipherNonce = Arrays.copyOfRange(bytes, 0, NONCE_LENGTH);
                cipher.init(Cipher.DECRYPT_MODE, ticketKey.secretKey(), new GCMParameterSpec(128, cipherNonce));
                return cipher.doFinal(bytes, NONCE_LENGTH, bytes.length - NONCE_LENGTH);
            }
            catch (Throwable ignored)
            {
            }
        }
        return null;
    }

    private TicketKey lockedRotateKey() throws Exception
    {
        // Remove expired keys.
        while (true)
        {
            TicketKey ticketKey = ticketKeys.peekLast();
            if (ticketKey == null || NanoTime.secondsSince(ticketKey.nanoTime()) < getSessionTicketLifetime().toSeconds())
                break;
            ticketKeys.pollLast();
        }

        // Generate and store a new key.
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey secretKey = keyGen.generateKey();
        TicketKey ticketKey = new TicketKey(secretKey, NanoTime.now());
        ticketKeys.offerFirst(ticketKey);
        return ticketKey;
    }

    private record TicketKey(SecretKey secretKey, long nanoTime)
    {
    }
}
