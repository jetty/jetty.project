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

package org.eclipse.jetty.quic.server.internal.tls;

import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import javax.crypto.SecretKey;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.tls.ext.QuicTransportParametersExtension;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.packets.PacketProtector;
import org.eclipse.jetty.quic.common.tls.HandshakeData;
import org.eclipse.jetty.quic.common.tls.TLSEngine;
import org.eclipse.jetty.quic.common.tls.X509KeyStorePair;
import org.eclipse.jetty.quic.server.QuicServerQuicConfiguration;
import org.eclipse.jetty.quic.server.SessionTicket;
import org.eclipse.jetty.tls.CertificateMessage;
import org.eclipse.jetty.tls.CertificateRequestMessage;
import org.eclipse.jetty.tls.CertificateVerifyMessage;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.ClientHelloMessage;
import org.eclipse.jetty.tls.EncryptedExtensionsMessage;
import org.eclipse.jetty.tls.FinishedMessage;
import org.eclipse.jetty.tls.KeyShare;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.NewSessionTicketMessage;
import org.eclipse.jetty.tls.ServerHelloMessage;
import org.eclipse.jetty.tls.SignatureAlgorithm;
import org.eclipse.jetty.tls.TLSException;
import org.eclipse.jetty.tls.TLSVersion;
import org.eclipse.jetty.tls.common.GroupKeyPair;
import org.eclipse.jetty.tls.ext.ALPNExtension;
import org.eclipse.jetty.tls.ext.EarlyDataExtension;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.KeyShareExtension;
import org.eclipse.jetty.tls.ext.ServerNameExtension;
import org.eclipse.jetty.tls.ext.SignatureAlgorithmsExtension;
import org.eclipse.jetty.tls.ext.SupportedVersionsExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// The server-side implementation of QUIC TLS state machine for QUIC.
public class ServerTLSEngine extends TLSEngine
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerTLSEngine.class);

    private final List<X509KeyStorePair> keyStorePairs = new ArrayList<>();
    private final ServerTLSConfiguration tlsConfiguration;
    private State state = State.NEED_CLIENT_HELLO;
    private SecretKey sharedSecret;

    public ServerTLSEngine(PacketProtector packetProtector, ServerTLSConfiguration tlsConfiguration)
    {
        super(packetProtector, false);
        this.tlsConfiguration = tlsConfiguration;
    }

    public void initialize(QuicVersion quicVersion)
    {
        try
        {
            tlsConfiguration.setQuicVersion(quicVersion);
            SslContextFactory.Server sslContextFactory = tlsConfiguration.getSslContextFactory();
            KeyStore keyStore = sslContextFactory.getKeyStore();
            char[] chars = sslContextFactory.getKeyStorePassword().toCharArray();
            KeyStore.PasswordProtection password = new KeyStore.PasswordProtection(chars);
            Arrays.fill(chars, ' ');
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements())
            {
                String alias = aliases.nextElement();
                KeyStore.Entry entry = keyStore.getEntry(alias, password);
                if (entry instanceof KeyStore.PrivateKeyEntry pke)
                {
                    PrivateKey privateKey = pke.getPrivateKey();
                    X509KeyStorePair keyStorePair = new X509KeyStorePair(alias, privateKey, Arrays.stream(pke.getCertificateChain())
                        .map(X509Certificate.class::cast)
                        .toList());
                    keyStorePairs.add(keyStorePair);
                }
            }
            password.destroy();
        }
        catch (Throwable x)
        {
            throw new TLSException(TLSException.Alert.INTERNAL_ERROR, x);
        }
    }

    public ServerTLSConfiguration getTLSConfiguration()
    {
        return tlsConfiguration;
    }

    @Override
    public void onMessageParsed(Message message)
    {
        try
        {
            switch (message)
            {
                case ClientHelloMessage chm -> processClientHello(chm);
                case CertificateMessage cm -> processCertificate(cm);
                case CertificateVerifyMessage cvm -> processCertificateVerify(cvm);
                case FinishedMessage fm -> processFinished(fm);
                default -> throw new IllegalStateException("unexpected_tls_message_" + message.type().name().toLowerCase(Locale.ROOT));
            }
        }
        catch (Throwable x)
        {
            // A catch-all for the processing of incoming
            // messages, notifying that the handshake failed.
            fail(x);
        }
    }

    private void processClientHello(ClientHelloMessage clientHello) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", clientHello, this);

        if (state != State.NEED_CLIENT_HELLO)
            throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));

        getPacketProtector().getTranscriptHash().offer(clientHello, true);

        List<Extension> clientExtensions = clientHello.extensions();

        if (clientExtensions.size() != clientExtensions.stream().map(Object::getClass).distinct().count())
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "duplicate_extension");

        List<String> clientProtocols = List.of();
        List<KeyShare> clientKeyShares = List.of();
        List<SignatureAlgorithm> clientSignatureAlgorithms = List.of();
        List<TLSVersion> clientVersions = List.of();
        for (Extension extension : clientExtensions)
        {
            switch (extension)
            {
                case ALPNExtension ae -> clientProtocols = ae.protocols();
                case KeyShareExtension kse -> clientKeyShares = kse.keyShares();
                case QuicTransportParametersExtension qtpe -> setTransportParameters(qtpe.transportParameters());
                case ServerNameExtension sne -> setServerName(sne.serverName());
                case SignatureAlgorithmsExtension sae -> clientSignatureAlgorithms = sae.signatureAlgorithms();
                case SupportedVersionsExtension sve -> clientVersions = sve.versions();
                default ->
                {
                }
            }
        }

        // RFC-8446[4.1.2,4.2.1]: SupportedVersionsExtension must be present.
        if (clientVersions.isEmpty())
            throw new TLSException(TLSException.Alert.MISSING_EXTENSION, "missing_supported_versions_extension");
        if (!clientVersions.contains(TLSVersion.TLS_1_3))
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "unsupported_tls_version");
        // Only TLS 1.3 is supported for now.
        TLSVersion tlsVersion = TLSVersion.TLS_1_3;
        setTLSVersion(tlsVersion);
        if (LOG.isDebugEnabled())
            LOG.debug("negotiated TLS version {} on {}", tlsVersion, this);

        SslContextFactory.Server sslContextFactory = tlsConfiguration.getSslContextFactory();
        String serverName = getServerName();
        boolean sniRequired = sslContextFactory.isSniRequired();
        if (serverName == null && sniRequired)
            throw new TLSException(TLSException.Alert.MISSING_EXTENSION, "missing_server_name_extension");

        // Prefer server cipher suites.
        List<CipherSuite> negotiatedCipherSuites = new ArrayList<>(tlsConfiguration.getServerQuicConfiguration().getCipherSuites());
        List<CipherSuite> clientCipherSuites = clientHello.cipherSuites();
        negotiatedCipherSuites.retainAll(clientCipherSuites);
        if (negotiatedCipherSuites.isEmpty())
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "no_common_cipher_suite");
        CipherSuite cipherSuite = negotiatedCipherSuites.getFirst();
        setCipherSuite(cipherSuite);
        if (LOG.isDebugEnabled())
            LOG.debug("negotiated CipherSuite {} on {}", cipherSuite, this);

        // Prefer server application protocols.
        List<String> negotiatedProtocols = new ArrayList<>(tlsConfiguration.getApplicationProtocols());
        negotiatedProtocols.retainAll(clientProtocols);
        if (negotiatedProtocols.isEmpty())
            throw new TLSException(TLSException.Alert.NO_APPLICATION_PROTOCOL, "no_common_application_protocol");
        String protocol = negotiatedProtocols.getFirst();
        setApplicationProtocol(protocol);
        if (LOG.isDebugEnabled())
            LOG.debug("negotiated alpn protocol {} on {}", protocol, this);

        if (clientSignatureAlgorithms.isEmpty())
            throw new TLSException(TLSException.Alert.MISSING_EXTENSION, "missing_signature_algorithms_extension");
        List<SignatureAlgorithm> serverSignatureAlgorithms = tlsConfiguration.getServerQuicConfiguration().getSignatureAlgorithms();
        // Prefer server signature algorithms.
        List<SignatureAlgorithm> negotiatedSignatureAlgorithms = new ArrayList<>(serverSignatureAlgorithms);
        negotiatedSignatureAlgorithms.retainAll(clientSignatureAlgorithms);
        if (negotiatedSignatureAlgorithms.isEmpty())
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "no_common_signature_algorithm");
        if (LOG.isDebugEnabled())
            LOG.debug("negotiated signature algorithms {} on {}", negotiatedSignatureAlgorithms, this);

        List<SignatureWithKeyStorePair> negotiatedKeyStorePairs = new ArrayList<>();
        for (X509KeyStorePair keyStorePair : keyStorePairs)
        {
            PublicKey publicKey = keyStorePair.certificates().getFirst().getPublicKey();
            for (SignatureAlgorithm signatureAlgorithm : negotiatedSignatureAlgorithms)
            {
                if (signatureAlgorithm.supports(publicKey))
                {
                    negotiatedKeyStorePairs.add(new SignatureWithKeyStorePair(signatureAlgorithm, keyStorePair));
                    break;
                }
            }
        }
        if (negotiatedKeyStorePairs.isEmpty())
            throw new TLSException(TLSException.Alert.UNSUPPORTED_CERTIFICATE, "unsupported_certificate");
        if (LOG.isDebugEnabled())
            LOG.debug("supported certificates at aliases {} on {}", negotiatedKeyStorePairs.stream().map(p -> p.keyStorePair().alias()).toList(), this);

        if (clientKeyShares.isEmpty())
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "missing_key_shares");
        KeyShare clientKeyShare = null;
        for (KeyShare keyShare : clientKeyShares)
        {
            if (tlsConfiguration.getServerQuicConfiguration().getNamedGroups().contains(keyShare.namedGroup()))
            {
                clientKeyShare = keyShare;
                break;
            }
        }
        if (clientKeyShare == null)
        {
            // TODO: send a HelloRetryRequest
            return;
        }
        GroupKeyPair groupKeyPair = GroupKeyPair.from(clientKeyShare.namedGroup());
        KeyShare serverKeyShare = groupKeyPair.toKeyShare();
        // Creating the shared secret also verifies the client KeyShare.
        sharedSecret = groupKeyPair.generateSharedSecret(clientKeyShare);
        if (LOG.isDebugEnabled())
            LOG.debug("negotiated KeyShare in NamedGroup {} on {}", clientKeyShare.namedGroup(), this);

        List<Extension> serverExtensions = new ArrayList<>();
        serverExtensions.add(new SupportedVersionsExtension(List.of(tlsVersion)));
        serverExtensions.add(new KeyShareExtension(List.of(serverKeyShare)));
        ServerHelloMessage serverHello = new ServerHelloMessage(newRandomBytes(32), cipherSuite, serverExtensions);
        if (LOG.isDebugEnabled())
            LOG.debug("produced {} on {}", serverHello, this);

        getPacketProtector().getTranscriptHash().offer(serverHello, false);
        QuicVersion quicVersion = tlsConfiguration.getQuicVersion();
        getPacketProtector().allocateHandshakeKeys(quicVersion, cipherSuite, sharedSecret);

        List<Message> handshakeMessages = new ArrayList<>();

        ALPNExtension alpnExtension = new ALPNExtension(List.of(protocol));
        QuicTransportParametersExtension quicTransportParametersExtension = new QuicTransportParametersExtension(tlsConfiguration.getTransportParameters());
        EncryptedExtensionsMessage encryptedExtensions = new EncryptedExtensionsMessage(List.of(alpnExtension, quicTransportParametersExtension));
        handshakeMessages.add(encryptedExtensions);
        getPacketProtector().getTranscriptHash().offer(encryptedExtensions, false);
        if (LOG.isDebugEnabled())
            LOG.debug("produced {} on {}", encryptedExtensions, this);

        boolean clientAuthentication = sslContextFactory.getWantClientAuth() || sslContextFactory.getNeedClientAuth();
        if (clientAuthentication)
        {
            List<Extension> extensions = List.of(new SignatureAlgorithmsExtension(serverSignatureAlgorithms));
            CertificateRequestMessage certificateRequest = new CertificateRequestMessage(BufferUtil.EMPTY_BYTES, extensions);
            handshakeMessages.add(certificateRequest);
        }

        SignatureWithKeyStorePair match = null;
        if (serverName != null)
            match = selectCertificate(negotiatedKeyStorePairs, serverName);
        SignatureWithKeyStorePair candidate = serverName == null ? negotiatedKeyStorePairs.getFirst() : match;
        if (candidate == null)
        {
            if (sniRequired)
                throw new TLSException(TLSException.Alert.UNRECOGNIZED_NAME, "no_matching_certificate");
            else
                candidate = negotiatedKeyStorePairs.getFirst();
        }
        // TODO: also default certificates must be checked for validity: split the check out of selectCertificate().
        if (LOG.isDebugEnabled())
            LOG.debug("certificate {} at alias {} on {}", match != null ? "match" : "default", candidate.keyStorePair().alias(), this);

        List<CertificateMessage.Entry> entries = candidate.keyStorePair().certificates().stream()
            .map(c -> new CertificateMessage.Entry(c, List.of()))
            .toList();
        CertificateMessage certificate = new CertificateMessage(BufferUtil.EMPTY_BYTES, entries);
        handshakeMessages.add(certificate);
        if (LOG.isDebugEnabled())
            LOG.debug("produced {} on {}", certificate, this);

        // Add CertificateMessage to the TranscriptHash to
        // calculate the signature for CertificateVerifyMessage.
        getPacketProtector().getTranscriptHash().offer(certificate, false);
        CertificateVerifyMessage certificateVerify = createCertificateVerifyMessage(candidate.signatureAlgorithm(), candidate.keyStorePair().privateKey(), false);
        handshakeMessages.add(certificateVerify);
        if (LOG.isDebugEnabled())
            LOG.debug("produced {} on {}", certificateVerify, this);

        // Add CertificateVerifyMessage to calculate
        // the verifyData for FinishedMessage.
        getPacketProtector().getTranscriptHash().offer(certificateVerify, false);
        FinishedMessage finished = createFinishedMessage(cipherSuite);
        handshakeMessages.add(finished);
        if (LOG.isDebugEnabled())
            LOG.debug("produced {} on {}", finished, this);

        getPacketProtector().getTranscriptHash().offer(finished, false);
        getPacketProtector().allocateApplicationKeys(quicVersion, cipherSuite);

        state = clientAuthentication ? State.NEED_CERTIFICATE : State.NEED_FINISHED;

        if (LOG.isDebugEnabled())
            LOG.debug("handshake completed on {}", this);

        notifyMessages(EncryptionLevel.INITIAL, List.of(serverHello), Callback.from(Invocable.InvocationType.NON_BLOCKING,
            () -> notifyMessages(EncryptionLevel.HANDSHAKE, handshakeMessages, Callback.from(Callback.NOOP, this::fail)),
            this::fail
        ));
    }

    private SignatureWithKeyStorePair selectCertificate(List<SignatureWithKeyStorePair> pairs, String serverName) throws Exception
    {
        SignatureWithKeyStorePair candidate = null;
        for (SignatureWithKeyStorePair pair : pairs)
        {
            X509Certificate leaf = pair.keyStorePair().certificates().getFirst();

            // First, try to match the SubjectAlternativeNames (SAN).
            Collection<List<?>> subjectAlternativeNames = leaf.getSubjectAlternativeNames();
            if (subjectAlternativeNames != null)
            {
                for (List<?> entry : subjectAlternativeNames)
                {
                    // See getSubjectAlternativeNames() javadocs for the structure of the entry.
                    int entryType = (int)entry.getFirst();
                    // EntryType is DNSName.
                    if (entryType == 2 && matches((String)entry.get(1), serverName))
                    {
                        candidate = pair;
                        break;
                    }
                }
            }

            if (candidate == null)
            {
                // Second, try the CommonName (CN).
                LdapName ldapName = new LdapName(leaf.getSubjectX500Principal().getName());
                for (Rdn rdn : ldapName.getRdns())
                {
                    if ("CN".equalsIgnoreCase(rdn.getType()) && matches((String)rdn.getValue(), serverName))
                    {
                        candidate = pair;
                        break;
                    }
                }
            }

            if (candidate == null)
                continue;

            // Verify date validity.
            for (X509Certificate certificate : candidate.keyStorePair().certificates())
            {
                try
                {
                    certificate.checkValidity();
                }
                catch (Throwable x)
                {
                    if (LOG.isDebugEnabled())
                        LOG.atDebug().setCause(x).log("invalid certificate {} on {}", certificate, this);
                    candidate = null;
                    break;
                }
            }

            if (candidate == null)
                continue;

            if (LOG.isDebugEnabled())
                LOG.debug("matched certificate {} at alias {} on {}", candidate.keyStorePair().certificates().getFirst(), candidate.keyStorePair().alias(), this);
            return candidate;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("no certificate matched on {}", this);
        return null;
    }

    private boolean matches(String namePattern, String name)
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

    private void processCertificate(CertificateMessage certificate)
    {
        // TODO:
    }

    private void processCertificateVerify(CertificateVerifyMessage certificateVerify)
    {
        // TODO:
    }

    private void processFinished(FinishedMessage finished) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", finished, this);

        if (state != State.NEED_FINISHED)
            throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));

        CipherSuite cipherSuite = getCipherSuite();
        if (!verifyFinishedMessage(cipherSuite, finished))
            throw new TLSException(TLSException.Alert.DECRYPT_ERROR, "invalid_verify_data");

        getPacketProtector().getTranscriptHash().offer(finished, true);
        SecretKey resumptionMasterSecret = getPacketProtector().createResumptionMasterSecret(cipherSuite);

        state = State.HANDSHAKE_SUCCESSFUL;

        if (LOG.isDebugEnabled())
            LOG.debug("handshake completed on {}", this);

        // RFC-9001[4.1.1]: handshake is complete when the Finished message
        // is sent, and the peer's Finished message has been verified.
        HandshakeData handshakeData = new HandshakeData(tlsConfiguration.getQuicVersion(), getTLSVersion(), getServerName(), cipherSuite, getApplicationProtocol(), getTransportParameters());
        notifyHandshakeCompleted(handshakeData, null);

        QuicServerQuicConfiguration serverQuicConfiguration = getTLSConfiguration().getServerQuicConfiguration();
        int lifetime = (int)serverQuicConfiguration.getSessionTicketFactory().getSessionTicketLifetime().toMillis();
        int ageAdd = ByteBuffer.wrap(newRandomBytes(4)).getInt();

        SessionTicket.Configuration configuration = new SessionTicket.Configuration(lifetime, ageAdd, newRandomBytes(8));
        SessionTicket sessionTicket = new SessionTicket(configuration, handshakeData, resumptionMasterSecret);

        byte[] ticket = serverQuicConfiguration.getSessionTicketFactory().generateSessionTicket(sessionTicket);
        List<Extension> extensions = List.of(new EarlyDataExtension(-1));
        NewSessionTicketMessage newSessionTicket = new NewSessionTicketMessage(lifetime, ageAdd, newRandomBytes(8), ticket, extensions);
        notifyMessages(EncryptionLevel.ONE_RTT, List.of(newSessionTicket), Callback.from(Callback.NOOP, this::fail));
    }

    @Override
    protected void dispose(Throwable failure)
    {
        destroy(sharedSecret);
        state = State.HANDSHAKE_FAILED;
        super.dispose(failure);
    }

    @Override
    public String toString()
    {
        return "%s[%s]".formatted(super.toString(), state);
    }

    private enum State
    {
        NEED_CLIENT_HELLO,
        NEED_CERTIFICATE,
        NEED_CERTIFICATE_VERIFY,
        NEED_FINISHED,
        HANDSHAKE_SUCCESSFUL,
        HANDSHAKE_FAILED
    }

    private record SignatureWithKeyStorePair(SignatureAlgorithm signatureAlgorithm, X509KeyStorePair keyStorePair)
    {
    }
}
