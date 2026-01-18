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

package org.eclipse.jetty.quic.common.tls;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.KDF;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

import org.eclipse.jetty.quic.common.packets.PacketProtector;
import org.eclipse.jetty.quic.common.tls.generator.QuicMessagesGenerator;
import org.eclipse.jetty.quic.common.tls.parser.QuicMessagesParser;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.FinishedMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.TLSException;
import org.eclipse.jetty.tls.common.generator.MessageGenerator;
import org.eclipse.jetty.tls.common.generator.MessagesGenerator;
import org.eclipse.jetty.tls.common.parser.MessageParser;
import org.eclipse.jetty.tls.common.parser.MessagesParser;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Implements the TLS machinery for QUIC, as per
/// [RFC 8446](https://datatracker.ietf.org/doc/html/rfc8446) and
/// [RFC 9001](https://datatracker.ietf.org/doc/html/rfc9001).
public abstract class TLSEngine implements MessageGenerator.Listener, MessageParser.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(TLSEngine.class);

    private final List<Message.Listener> listeners = new ArrayList<>();
    private final SecureRandom random = new SecureRandom();
    private final PacketProtector protector;
    private final MessagesGenerator tlsGenerator;
    private final MessagesParser tlsParser;
    private String applicationProtocol;

    protected TLSEngine(PacketProtector protector, boolean client)
    {
        this.protector = protector;
        tlsGenerator = new QuicMessagesGenerator(protector.getByteBufferPool(), client);
        tlsParser = new QuicMessagesParser(client);
    }

    public String getNegotiatedApplicationProtocol()
    {
        return applicationProtocol;
    }

    protected void setNegotiatedApplicationProtocol(String applicationProtocol)
    {
        this.applicationProtocol = applicationProtocol;
    }

    public PacketProtector getPacketProtector()
    {
        return protector;
    }

    public MessagesGenerator getMessagesGenerator()
    {
        return tlsGenerator;
    }

    public MessagesParser getMessagesParser()
    {
        return tlsParser;
    }

    public void addMessageListener(Message.Listener listener)
    {
        listeners.add(listener);
    }

    protected void notifyMessages(List<Message> messages, Callback callback)
    {
        for (Message.Listener listener : listeners)
        {
            try
            {
                listener.onMessages(messages, callback);
            }
            catch (Throwable x)
            {
                LOG.atInfo().setCause(x).log("failure while notifying listener {}", listener);
            }
        }
    }

    public byte[] newRandomBytes(int length)
    {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    protected FinishedMessage createFinishedMessage(CipherSuite cipherSuite, boolean input) throws Exception
    {
        // RFC-8446[4.4.4].
        int hashLength = cipherSuite.hashLength();
        int shaLength = hashLength * 8;
        KDF kdf = KDF.getInstance("HKDF-SHA" + shaLength);
        SecretKey trafficKey = getPacketProtector().getTrafficSecretKey(input);
        SecretKey finishedKey = kdf.deriveKey("Generic", HKDF.expandLabel(trafficKey, "finished", hashLength));

        Mac mac = Mac.getInstance("HmacSHA" + shaLength);
        mac.init(finishedKey);
        byte[] verifyData = mac.doFinal(getPacketProtector().getTranscriptHash().getHash());
        return new FinishedMessage(verifyData);
    }

    protected boolean verifyFinishedMessage(CipherSuite cipherSuite, FinishedMessage finished, boolean input) throws Exception
    {
        // RFC-8446[4.4.4].
        byte[] verifyData = finished.verifyData();
        int hashLength = cipherSuite.hashLength();
        if (verifyData.length != hashLength)
            throw new TLSException(TLSException.Alert.DECODE_ERROR, "invalid verify data length");

        int shaLength = hashLength * 8;
        KDF kdf = KDF.getInstance("HKDF-SHA" + shaLength);
        SecretKey trafficKey = getPacketProtector().getTrafficSecretKey(input);
        SecretKey finishedKey = kdf.deriveKey("Generic", HKDF.expandLabel(trafficKey, "finished", hashLength));

        Mac mac = Mac.getInstance("HmacSHA" + shaLength);
        mac.init(finishedKey);
        byte[] expected = mac.doFinal(getPacketProtector().getTranscriptHash().getHash());

        // Differently from Arrays.equals(), MessageDigest.isEqual()
        // implements constant-time comparison to avoid timing attacks.
        return MessageDigest.isEqual(verifyData, expected);
    }

    @Override
    public String toString()
    {
        return "%s@%x".formatted(TypeUtil.toShortName(getClass()), hashCode());
    }
}
