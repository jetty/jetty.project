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
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderResult;
import java.security.cert.CertPathValidator;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import javax.crypto.KDF;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.packets.PacketProtector;
import org.eclipse.jetty.quic.common.tls.generator.QuicMessagesGenerator;
import org.eclipse.jetty.quic.common.tls.parser.QuicMessagesParser;
import org.eclipse.jetty.tls.CertificateMessage;
import org.eclipse.jetty.tls.CertificateVerifyMessage;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.FinishedMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.SignatureAlgorithm;
import org.eclipse.jetty.tls.TLSException;
import org.eclipse.jetty.tls.TLSVersion;
import org.eclipse.jetty.tls.common.HKDF;
import org.eclipse.jetty.tls.common.generator.MessagesGenerator;
import org.eclipse.jetty.tls.common.parser.MessagesParser;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Implements the TLS machinery for QUIC, as per
/// [RFC 8446](https://datatracker.ietf.org/doc/html/rfc8446) and
/// [RFC 9001](https://datatracker.ietf.org/doc/html/rfc9001).
public abstract class TLSEngine
{
    private static final Logger LOG = LoggerFactory.getLogger(TLSEngine.class);

    private final MessageNotifier messageNotifier = new MessageNotifier();
    private final List<HandshakeListener> handshakeListeners = new ArrayList<>();
    private final SecureRandom random = new SecureRandom();
    private final PacketProtector protector;
    private final MessagesGenerator tlsGenerator;
    private final MessagesParser tlsParser;
    private TLSVersion tlsVersion;
    private String serverName;
    private CipherSuite cipherSuite;
    private String applicationProtocol;
    private TransportParameters transportParameters;
    private CertificateMessage peerCertificate;

    protected TLSEngine(PacketProtector protector, boolean client)
    {
        this.protector = protector;
        tlsGenerator = new QuicMessagesGenerator(protector.getByteBufferPool(), client);
        tlsParser = new QuicMessagesParser(client);
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
        messageNotifier.listeners.add(listener);
    }

    protected void notifyOutgoingMessages(EncryptionLevel encryptionLevel, List<Message> messages, Callback callback)
    {
        messageNotifier.notifyOutgoing(encryptionLevel, messages, callback);
    }

    public void onMessage(EncryptionLevel encryptionLevel, Message message)
    {
        messageNotifier.notifyIncoming(encryptionLevel, message);
    }

    public void addHandshakeListener(HandshakeListener listener)
    {
        handshakeListeners.add(listener);
    }

    protected void notifyHandshakeCompleted(HandshakeData data, Throwable failure)
    {
        for (HandshakeListener listener : handshakeListeners)
        {
            try
            {
                listener.handshakeCompleted(data, failure);
            }
            catch (Throwable x)
            {
                LOG.atInfo().setCause(x).log("failure while notifying listener {}", listener);
            }
        }
    }

    public TLSVersion getTLSVersion()
    {
        return tlsVersion;
    }

    public void setTLSVersion(TLSVersion tlsVersion)
    {
        this.tlsVersion = tlsVersion;
    }

    public String getServerName()
    {
        return serverName;
    }

    public void setServerName(String serverName)
    {
        this.serverName = serverName;
    }

    public CipherSuite getCipherSuite()
    {
        return cipherSuite;
    }

    public void setCipherSuite(CipherSuite cipherSuite)
    {
        this.cipherSuite = cipherSuite;
    }

    public String getApplicationProtocol()
    {
        return applicationProtocol;
    }

    public void setApplicationProtocol(String applicationProtocol)
    {
        this.applicationProtocol = applicationProtocol;
    }

    public TransportParameters getTransportParameters()
    {
        return transportParameters;
    }

    public void setTransportParameters(TransportParameters transportParameters)
    {
        this.transportParameters = transportParameters;
    }

    public CertificateMessage getPeerCertificate()
    {
        return peerCertificate;
    }

    public void setPeerCertificate(CertificateMessage peerCertificate)
    {
        this.peerCertificate = peerCertificate;
    }

    public byte[] newRandomBytes(int length)
    {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    protected List<X509KeyStorePair> loadKeyStore(KeyStore keyStore, String password) throws Exception
    {
        if (keyStore == null)
            return List.of();
        List<X509KeyStorePair> keyStorePairs = new ArrayList<>();
        char[] chars = password == null ? new char[0] : password.toCharArray();
        KeyStore.PasswordProtection ksPassword = new KeyStore.PasswordProtection(chars);
        Arrays.fill(chars, '*');
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements())
        {
            String alias = aliases.nextElement();
            if (keyStore.isKeyEntry(alias))
            {
                KeyStore.Entry entry = keyStore.getEntry(alias, ksPassword);
                if (entry instanceof KeyStore.PrivateKeyEntry pke)
                {
                    PrivateKey privateKey = pke.getPrivateKey();
                    X509KeyStorePair keyStorePair = new X509KeyStorePair(alias, privateKey, Arrays.stream(pke.getCertificateChain())
                        .map(X509Certificate.class::cast)
                        .toList());
                    keyStorePairs.add(keyStorePair);
                }
            }
        }
        ksPassword.destroy();
        return keyStorePairs;
    }

    protected void processCertificateVerifyMessage(List<SignatureAlgorithm> signatureAlgorithms, CertificateVerifyMessage certificateVerify, boolean client) throws Exception
    {
        SignatureAlgorithm signatureAlgorithm = certificateVerify.signatureAlgorithm();
        if (!signatureAlgorithms.contains(signatureAlgorithm))
            throw new TLSException(TLSException.Alert.BAD_CERTIFICATE, "unsupported_certificate_signature_algorithm");

        if (!verifyCertificateVerifyMessage(getPeerCertificate().entries().getFirst().certificate(), certificateVerify, client))
            throw new TLSException(TLSException.Alert.DECRYPT_ERROR, "invalid_certificate_verify");

        getPacketProtector().getTranscriptHash().offer(certificateVerify, true);
    }

    protected void verifyCertificateChain(SslContextFactory sslContextFactory, List<X509Certificate> certificateChain)
    {
        try
        {
            if (sslContextFactory.isTrustAll())
                return;

            KeyStore trustStore = sslContextFactory.getTrustStore();
            X509CertSelector certSelector = new X509CertSelector();
            certSelector.setCertificate(certificateChain.getFirst());
            PKIXBuilderParameters params = new PKIXBuilderParameters(trustStore, certSelector);
            // TODO: support revocation via PKIXRevocationChecker.
            params.setRevocationEnabled(false);
            params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(certificateChain)));
            CertPathBuilderResult buildResult = CertPathBuilder.getInstance("PKIX").build(params);
            CertPathValidator.getInstance("PKIX").validate(buildResult.getCertPath(), params);
        }
        catch (Throwable x)
        {
            throw new TLSException(TLSException.Alert.BAD_CERTIFICATE, "invalid_certificate_chain", x);
        }
    }

    protected CertificateVerifyMessage createCertificateVerifyMessage(SignatureAlgorithm signatureAlgorithm, PrivateKey privateKey, boolean client) throws Exception
    {
        byte[] signature = signatureAlgorithm.sign(privateKey, createCertificateVerifyContent(client));
        return new CertificateVerifyMessage(signatureAlgorithm, signature);
    }

    protected boolean verifyCertificateVerifyMessage(X509Certificate certificate, CertificateVerifyMessage message, boolean client) throws Exception
    {
        SignatureAlgorithm signatureAlgorithm = message.signatureAlgorithm();
        if (!signatureAlgorithm.supports(certificate.getPublicKey()))
            return false;
        return signatureAlgorithm.verify(certificate.getPublicKey(), createCertificateVerifyContent(client), message.signature());
    }

    private byte[] createCertificateVerifyContent(boolean client) throws Exception
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
        return content;
    }

    protected FinishedMessage createFinishedMessage(CipherSuite cipherSuite) throws Exception
    {
        // RFC-8446[4.4.4].
        int hashLength = cipherSuite.hashLength();
        int shaLength = hashLength * 8;
        KDF kdf = KDF.getInstance("HKDF-SHA" + shaLength);
        SecretKey trafficKey = getPacketProtector().getTrafficSecretKey(false);
        SecretKey finishedKey = kdf.deriveKey("Generic", HKDF.expandLabel(trafficKey, "finished", hashLength));

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
        SecretKey finishedKey = kdf.deriveKey("Generic", HKDF.expandLabel(trafficKey, "finished", hashLength));

        Mac mac = Mac.getInstance("HmacSHA" + shaLength);
        mac.init(finishedKey);
        byte[] expected = mac.doFinal(getPacketProtector().getTranscriptHash().getHash());

        // Differently from Arrays.equals(), MessageDigest.isEqual()
        // implements constant-time comparison to avoid timing attacks.
        return MessageDigest.isEqual(verifyData, expected);
    }

    protected boolean identityMatches(X509Certificate certificate, String name)
    {
        try
        {
            // First, try to match the SubjectAlternativeNames (SAN).
            Collection<List<?>> subjectAlternativeNames = certificate.getSubjectAlternativeNames();
            if (subjectAlternativeNames != null)
            {
                for (List<?> entry : subjectAlternativeNames)
                {
                    // See X509Certificate.getSubjectAlternativeNames() javadocs for the structure of the entry.
                    int entryType = (int)entry.getFirst();
                    // EntryType 2 is DNSName, 7 is IPAddress.
                    if ((entryType == 2 && domainNameMatches((String)entry.get(1), name)) ||
                        (entryType == 7 && name.equalsIgnoreCase((String)entry.get(1))))
                    {
                        return true;
                    }
                }
            }

            // Second, try the CommonName (CN).
            LdapName ldapName = new LdapName(certificate.getSubjectX500Principal().getName());
            for (Rdn rdn : ldapName.getRdns())
            {
                if ("CN".equalsIgnoreCase(rdn.getType()) && domainNameMatches((String)rdn.getValue(), name))
                {
                    return true;
                }
            }

            return false;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.atDebug().setCause(x).log("could not match identity of certificate {} with server name {}", certificate, name);
            return false;
        }
    }

    private boolean domainNameMatches(String namePattern, String name)
    {
        // Direct match.
        if (namePattern.equalsIgnoreCase(name))
            return true;
        // Not a pattern, so no match.
        if (!namePattern.startsWith("*."))
            return false;
        // Pattern match: "*.example.com" matches "www.example.com",
        // but not "example.com" and neither "one.two.example.com".
        // Check whether the name ends with the pattern, case-insensitive:
        // the name must end with ".example.com".
        String noGlobPattern = namePattern.substring(1);
        if (!name.regionMatches(true, name.length() - noGlobPattern.length(), noGlobPattern, 0, noGlobPattern.length()))
            return false;
        // Get the prefix, such as "www" or "one.two".
        String prefix = name.substring(0, name.length() - noGlobPattern.length());
        // Match only one subdomain.
        return prefix.indexOf('.') < 0;
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
        notifyHandshakeCompleted(null, TLSException.wrap(failure));
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

    /// Listener for incoming and outgoing TLS messages.
    public interface MessageListener
    {
        /// Invoked when the [TLSEngine] receives a TLS [Message].
        ///
        /// @param encryptionLevel the encryption level
        /// @param message the TLS message
        default void onIncomingMessage(EncryptionLevel encryptionLevel, Message message)
        {
        }

        /// Invoked when the [TLSEngine] emits a TLS [Message].
        ///
        /// Listeners are called sequentially when the previous listener succeeded the callback.
        /// If a listener fails the callback or throws an exception, the remaining listeners are not called.
        ///
        /// @param encryptionLevel the encryption level
        /// @param messages the TLS messages
        /// @param callback the [Callback] to notify when the messages have been processed
        default void onOutgoingMessages(EncryptionLevel encryptionLevel, List<Message> messages, Callback callback)
        {
            callback.succeeded();
        }
    }

    public interface HandshakeListener
    {
        void handshakeCompleted(HandshakeData data, Throwable failure);
    }

    private static class MessageNotifier extends IteratingCallback
    {
        private final List<MessageListener> listeners = new ArrayList<>();
        private EncryptionLevel encryptionLevel;
        private List<Message> messages;
        private Callback callback;
        private int index;

        public void notifyIncoming(EncryptionLevel encryptionLevel, Message message)
        {
            for (MessageListener listener : listeners)
            {
                listener.onIncomingMessage(encryptionLevel, message);
            }
        }

        public void notifyOutgoing(EncryptionLevel encryptionLevel, List<Message> messages, Callback callback)
        {
            if (reset())
            {
                this.encryptionLevel = encryptionLevel;
                this.messages = messages;
                this.callback = callback;
                this.index = 0;
                iterate();
            }
            else
            {
                callback.failed(new IllegalStateException());
            }
        }

        @Override
        protected Action process()
        {
            if (index == listeners.size())
                return Action.SUCCEEDED;
            MessageListener listener = listeners.get(index++);
            listener.onOutgoingMessages(encryptionLevel, messages, this);
            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleted(Throwable causeOrNull)
        {
            if (causeOrNull == null)
                callback.succeeded();
            else
                callback.failed(causeOrNull);
        }
    }
}
