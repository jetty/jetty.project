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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.KDF;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.packets.PacketProtector;
import org.eclipse.jetty.quic.common.tls.generator.QuicMessagesGenerator;
import org.eclipse.jetty.quic.common.tls.parser.QuicMessagesParser;
import org.eclipse.jetty.tls.CertificateVerifyMessage;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.FinishedMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.SignatureAlgorithm;
import org.eclipse.jetty.tls.TLSException;
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
public abstract class TLSEngine implements MessageParser.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(TLSEngine.class);

    private final List<MessageListener> messageListeners = new ArrayList<>();
    private final List<HandshakeListener> handshakeListeners = new ArrayList<>();
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

    public void addMessageListener(MessageListener listener)
    {
        messageListeners.add(listener);
    }

    protected void notifyMessages(EncryptionLevel encryptionLevel, List<Message> messages, Callback callback)
    {
        for (MessageListener listener : messageListeners)
        {
            try
            {
                listener.onMessages(encryptionLevel, messages, callback);
            }
            catch (Throwable x)
            {
                LOG.atInfo().setCause(x).log("failure while notifying listener {}", listener);
            }
        }
    }

    public void addHandshakeListener(HandshakeListener listener)
    {
        handshakeListeners.add(listener);
    }

    protected void notifyHandshakeCompleted(Throwable failure)
    {
        for (HandshakeListener listener : handshakeListeners)
        {
            try
            {
                listener.handshakeCompleted(failure);
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

    protected CertificateVerifyMessage createCertificateVerifyMessage(SignatureAlgorithm signatureAlgorithm, PrivateKey privateKey, boolean client) throws Exception
    {
        // RFC-8446[4.4.3].
        String context = client ? "TLS 1.3, client CertificateVerify" : "TLS 1.3, server CertificateVerify";
        byte[] contextBytes = context.getBytes(StandardCharsets.US_ASCII);
        byte[] transcriptHash = getPacketProtector().getTranscriptHash().getHash();
        byte[] content = new byte[64 + contextBytes.length + 1 + transcriptHash.length];
        Arrays.fill(content, 0, 64, (byte)0x20);
        int offset = 64;
        System.arraycopy(contextBytes, 0, content, offset, contextBytes.length);
        offset += contextBytes.length;
        content[offset] = (byte)0x00;
        offset += 1;
        System.arraycopy(transcriptHash, 0, content, offset, transcriptHash.length);

        byte[] signature = signatureAlgorithm.sign(privateKey, content);
        return new CertificateVerifyMessage(signatureAlgorithm, signature);
    }

    protected FinishedMessage createFinishedMessage(CipherSuite cipherSuite) throws Exception
    {
        // RFC-8446[4.4.4].
        int hashLength = cipherSuite.hashLength();
        int shaLength = hashLength * 8;
        KDF kdf = KDF.getInstance("HKDF-SHA" + shaLength);
        SecretKey trafficKey = getPacketProtector().getTrafficSecretKey(false);
        SecretKey finishedKey = kdf.deriveKey("Generic", org.eclipse.jetty.tls.common.HKDF.expandLabel(trafficKey, "finished", hashLength));

        Mac mac = Mac.getInstance("HmacSHA" + shaLength);
        mac.init(finishedKey);
        byte[] verifyData = mac.doFinal(getPacketProtector().getTranscriptHash().getHash());
        return new FinishedMessage(verifyData);
    }

    protected boolean verifyFinishedMessage(CipherSuite cipherSuite, FinishedMessage finished) throws Exception
    {
        // RFC-8446[4.4.4].
        byte[] verifyData = finished.verifyData();
        int hashLength = cipherSuite.hashLength();
        if (verifyData.length != hashLength)
            throw new TLSException(TLSException.Alert.DECODE_ERROR, "invalid verify data length");

        int shaLength = hashLength * 8;
        KDF kdf = KDF.getInstance("HKDF-SHA" + shaLength);
        SecretKey trafficKey = getPacketProtector().getTrafficSecretKey(true);
        SecretKey finishedKey = kdf.deriveKey("Generic", org.eclipse.jetty.tls.common.HKDF.expandLabel(trafficKey, "finished", hashLength));

        Mac mac = Mac.getInstance("HmacSHA" + shaLength);
        mac.init(finishedKey);
        byte[] expected = mac.doFinal(getPacketProtector().getTranscriptHash().getHash());

        // Differently from Arrays.equals(), MessageDigest.isEqual()
        // implements constant-time comparison to avoid timing attacks.
        return MessageDigest.isEqual(verifyData, expected);
    }

    protected final void fail(Throwable failure)
    {
        if (failure == null)
            return;
        if (LOG.isDebugEnabled())
            LOG.atDebug().setCause(failure).log("failure on {}", this);
        dispose(failure);
    }

    protected void dispose(Throwable failure)
    {
        notifyHandshakeCompleted(TLSException.wrap(failure));
    }

    protected static void destroy(SecretKey secretKey)
    {
        try
        {
            if (secretKey != null)
                secretKey.destroy();
        }
        catch (Throwable x)
        {
            if (LOG.isTraceEnabled())
                LOG.atTrace().setCause(x).log("failure while destroying {}", secretKey);
        }
    }

    @Override
    public String toString()
    {
        return "%s@%x".formatted(TypeUtil.toShortName(getClass()), hashCode());
    }

    public interface MessageListener
    {
        void onMessages(EncryptionLevel encryptionLevel, List<Message> messages, Callback callback);
    }

    public interface HandshakeListener
    {
        void handshakeCompleted(Throwable failure);
    }
}
