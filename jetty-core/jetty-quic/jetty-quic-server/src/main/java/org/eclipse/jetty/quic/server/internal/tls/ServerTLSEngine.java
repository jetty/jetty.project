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
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.HKDFParameterSpec;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
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
import org.eclipse.jetty.tls.common.HKDF;
import org.eclipse.jetty.tls.ext.ALPNExtension;
import org.eclipse.jetty.tls.ext.ClientPreSharedKeyExtension;
import org.eclipse.jetty.tls.ext.EarlyDataExtension;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.KeyShareExtension;
import org.eclipse.jetty.tls.ext.PreSharedKeyIdentity;
import org.eclipse.jetty.tls.ext.ServerNameExtension;
import org.eclipse.jetty.tls.ext.ServerPreSharedKeyExtension;
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

    public void initialize()
    {
        try
        {
            SslContextFactory.Server sslContextFactory = tlsConfiguration.getSslContextFactory();
            keyStorePairs.addAll(loadKeyStore(sslContextFactory.getKeyStore(), sslContextFactory.getKeyStorePassword()));
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
    public void onMessage(EncryptionLevel encryptionLevel, Message message)
    {
        try
        {
            super.onMessage(encryptionLevel, message);
            switch (message)
            {
                case ClientHelloMessage chm -> processClientHelloMessage(chm);
                case CertificateMessage cm -> processCertificateMessage(cm);
                case CertificateVerifyMessage cvm -> processCertificateVerifyMessage(cvm);
                case FinishedMessage fm -> processFinishedMessage(fm);
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

    private void processClientHelloMessage(ClientHelloMessage message) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", message, this);

        if (state != State.NEED_CLIENT_HELLO)
            throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));

        List<Extension> clientExtensions = message.extensions();

        if (clientExtensions.size() != clientExtensions.stream().mapToInt(Extension::code).distinct().count())
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "duplicate_extension");

        List<String> clientProtocols = List.of();
        List<PreSharedKeyIdentity> clientIdentities = List.of();
        List<KeyShare> clientKeyShares = List.of();
        TransportParameters clientTransportParameters = null;
        String serverName = null;
        List<SignatureAlgorithm> clientSignatureAlgorithms = List.of();
        List<TLSVersion> clientVersions = List.of();
        for (Extension extension : clientExtensions)
        {
            switch (extension)
            {
                case ALPNExtension ae -> clientProtocols = ae.protocols();
                case ClientPreSharedKeyExtension pske -> clientIdentities = pske.identities();
                case KeyShareExtension kse -> clientKeyShares = kse.keyShares();
                case QuicTransportParametersExtension qtpe -> clientTransportParameters = qtpe.transportParameters();
                case ServerNameExtension sne -> serverName = sne.serverName();
                case SignatureAlgorithmsExtension sae -> clientSignatureAlgorithms = sae.signatureAlgorithms();
                case SupportedVersionsExtension sve -> clientVersions = sve.versions();
                default ->
                {
                }
            }
        }

        SessionTicket sessionTicket = null;
        if (!clientIdentities.isEmpty())
        {
            PreSharedKeyIdentity clientIdentity = clientIdentities.getFirst();
            sessionTicket = getTLSConfiguration().getServerQuicConfiguration().getSessionTicketFactory().parseSessionTicket(clientIdentity.identity());
            if (sessionTicket != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("attempting resumption with {} on {}", sessionTicket, this);

                // RFC-8446[4.2.11]: the pre-shared key extension must be the last.
                // RFC-8446[4.2.11.2]: truncate the extension to verify the binder.
                List<Extension> truncatedExtensions = new ArrayList<>(clientExtensions);
                ClientPreSharedKeyExtension pske = (ClientPreSharedKeyExtension)truncatedExtensions.removeLast();
                List<PreSharedKeyIdentity> truncatedIdentities = new ArrayList<>();
                for (PreSharedKeyIdentity identity : pske.identities())
                {
                    truncatedIdentities.add(new PreSharedKeyIdentity(identity.identity(), identity.obfuscatedTicketAge(), new byte[identity.binder().length]));
                }
                truncatedExtensions.add(new ClientPreSharedKeyExtension(truncatedIdentities, false));
                ClientHelloMessage truncatedClientHello = new ClientHelloMessage(message.random(), message.cipherSuites(), truncatedExtensions);
                getPacketProtector().getTranscriptHash().offer(truncatedClientHello, true);

                CipherSuite cipherSuite = sessionTicket.handshakeData().cipherSuite();
                getPacketProtector().getTranscriptHash().initialize(cipherSuite);
                byte[] binder = createPreSharedKeyIdentityBinder(cipherSuite, sessionTicket.resumptionMasterSecret(), sessionTicket.configuration().nonce());
                getPacketProtector().getTranscriptHash().clear();
                boolean binderValid = MessageDigest.isEqual(clientIdentity.binder(), binder);
                if (LOG.isDebugEnabled())
                    LOG.debug("resumption identity {} for {} on {}", binderValid ? "valid" : "invalid", sessionTicket, this);
                if (!binderValid)
                    sessionTicket = null;
                // TODO: early data?
            }
        }

        // RFC-8446[4.1.2,4.2.1]: SupportedVersionsExtension must be present.
        if (clientVersions.isEmpty())
            throw new TLSException(TLSException.Alert.MISSING_EXTENSION, "missing_supported_versions_extension");
        if (!clientVersions.contains(TLSVersion.TLS_1_3))
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "unsupported_tls_version");
        // Only TLS 1.3 is supported for now.
        TLSVersion tlsVersion = sessionTicket != null ? sessionTicket.handshakeData().tlsVersion() : TLSVersion.TLS_1_3;
        setTLSVersion(tlsVersion);
        if (LOG.isDebugEnabled())
            LOG.debug("negotiated TLS version {} on {}", tlsVersion, this);

        if (sessionTicket != null && !Objects.equals(serverName, sessionTicket.handshakeData().serverName()))
            sessionTicket = null;
        SslContextFactory.Server sslContextFactory = tlsConfiguration.getSslContextFactory();
        boolean sniRequired = sslContextFactory.isSniRequired();
        if (serverName == null && sniRequired)
            throw new TLSException(TLSException.Alert.MISSING_EXTENSION, "missing_server_name_extension");
        setServerName(serverName);

        setTransportParameters(clientTransportParameters);

        // Prefer server cipher suites.
        List<CipherSuite> serverCipherSuites = sessionTicket != null
            ? List.of(sessionTicket.handshakeData().cipherSuite())
            : tlsConfiguration.getServerQuicConfiguration().getCipherSuites();
        List<CipherSuite> negotiatedCipherSuites = new ArrayList<>(serverCipherSuites);
        List<CipherSuite> clientCipherSuites = message.cipherSuites();
        negotiatedCipherSuites.retainAll(clientCipherSuites);
        if (negotiatedCipherSuites.isEmpty())
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "no_common_cipher_suite");
        CipherSuite cipherSuite = negotiatedCipherSuites.getFirst();
        setCipherSuite(cipherSuite);
        if (sessionTicket == null)
            getPacketProtector().getTranscriptHash().initialize(cipherSuite);
        if (LOG.isDebugEnabled())
            LOG.debug("negotiated CipherSuite {} on {}", cipherSuite, this);

        // Prefer server application protocols.
        List<String> serverProtocols = sessionTicket != null
            ? List.of(sessionTicket.handshakeData().applicationProtocol())
            : tlsConfiguration.getApplicationProtocols();
        serverProtocols = Objects.requireNonNullElse(serverProtocols, List.of());
        List<String> negotiatedProtocols = new ArrayList<>(serverProtocols);
        negotiatedProtocols.retainAll(clientProtocols);
        String protocol = negotiatedProtocols.isEmpty() ? null : negotiatedProtocols.getFirst();
        setApplicationProtocol(protocol);
        if (LOG.isDebugEnabled())
            LOG.debug("negotiated application protocol {} on {}", protocol, this);

        List<SignatureAlgorithm> serverSignatureAlgorithms = tlsConfiguration.getServerQuicConfiguration().getSignatureAlgorithms();
        List<SignatureWithKeyStorePair> negotiatedKeyStorePairs = new ArrayList<>();
        if (sessionTicket == null)
        {
            if (clientSignatureAlgorithms.isEmpty())
                throw new TLSException(TLSException.Alert.MISSING_EXTENSION, "missing_signature_algorithms_extension");
            // Prefer server signature algorithms.
            List<SignatureAlgorithm> negotiatedSignatureAlgorithms = new ArrayList<>(serverSignatureAlgorithms);
            negotiatedSignatureAlgorithms.retainAll(clientSignatureAlgorithms);
            if (negotiatedSignatureAlgorithms.isEmpty())
                throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "no_common_signature_algorithm");
            if (LOG.isDebugEnabled())
                LOG.debug("negotiated signature algorithms {} on {}", negotiatedSignatureAlgorithms, this);

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
        }

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

        getPacketProtector().getTranscriptHash().offer(message, true);

        List<Extension> serverExtensions = new ArrayList<>();
        serverExtensions.add(new SupportedVersionsExtension(List.of(tlsVersion)));
        serverExtensions.add(new KeyShareExtension(List.of(serverKeyShare)));
        if (sessionTicket != null)
            serverExtensions.add(new ServerPreSharedKeyExtension(0));
        ServerHelloMessage serverHello = new ServerHelloMessage(newRandomBytes(32), cipherSuite, serverExtensions);
        getPacketProtector().getTranscriptHash().offer(serverHello, false);
        if (LOG.isDebugEnabled())
            LOG.debug("produced {} on {}", serverHello, this);

        QuicVersion quicVersion = tlsConfiguration.getQuicVersion();
        HKDFParameterSpec pskSpec = null;
        if (sessionTicket != null)
            pskSpec = HKDF.expandLabel(sessionTicket.resumptionMasterSecret(), "resumption", sessionTicket.configuration().nonce(), sessionTicket.handshakeData().cipherSuite().hashLength());
        getPacketProtector().generateHandshakeKeys(quicVersion, cipherSuite, sharedSecret, pskSpec);

        List<Message> handshakeMessages = new ArrayList<>();

        List<Extension> encryptedExtensions = new ArrayList<>();
        if (protocol != null)
        {
            ALPNExtension alpnExtension = new ALPNExtension(List.of(protocol));
            encryptedExtensions.add(alpnExtension);
        }
        QuicTransportParametersExtension quicTransportParametersExtension = new QuicTransportParametersExtension(tlsConfiguration.getTransportParameters());
        encryptedExtensions.add(quicTransportParametersExtension);
        EncryptedExtensionsMessage encryptedExtensionsMessage = new EncryptedExtensionsMessage(encryptedExtensions);
        handshakeMessages.add(encryptedExtensionsMessage);
        getPacketProtector().getTranscriptHash().offer(encryptedExtensionsMessage, false);
        if (LOG.isDebugEnabled())
            LOG.debug("produced {} on {}", encryptedExtensionsMessage, this);

        boolean clientAuthentication = false;
        if (sessionTicket == null)
        {
            clientAuthentication = sslContextFactory.getWantClientAuth() || sslContextFactory.getNeedClientAuth();
            if (clientAuthentication)
            {
                // RFC-8446[4.3.2]: signature algorithms extension is mandatory.
                List<Extension> crExtensions = List.of(new SignatureAlgorithmsExtension(serverSignatureAlgorithms));
                CertificateRequestMessage certificateRequestMessage = new CertificateRequestMessage(BufferUtil.EMPTY_BYTES, crExtensions);
                handshakeMessages.add(certificateRequestMessage);
                getPacketProtector().getTranscriptHash().offer(certificateRequestMessage, false);
                if (LOG.isDebugEnabled())
                    LOG.debug("produced {} on {}", certificateRequestMessage, this);
            }

            SignatureWithKeyStorePair match = selectCertificate(negotiatedKeyStorePairs, serverName, sniRequired);
            if (match == null)
                throw new TLSException(TLSException.Alert.UNRECOGNIZED_NAME, "no_matching_certificate");
            if (LOG.isDebugEnabled())
                LOG.debug("certificate {} at alias {} on {}", serverName != null ? "match" : "default", match.keyStorePair().alias(), this);

            List<CertificateMessage.Entry> entries = match.keyStorePair().certificates().stream()
                .map(c -> new CertificateMessage.Entry(c, List.of()))
                .toList();
            CertificateMessage certificateMessage = new CertificateMessage(BufferUtil.EMPTY_BYTES, entries);
            handshakeMessages.add(certificateMessage);
            getPacketProtector().getTranscriptHash().offer(certificateMessage, false);
            if (LOG.isDebugEnabled())
                LOG.debug("produced {} on {}", certificateMessage, this);

            CertificateVerifyMessage certificateVerifyMessage = createCertificateVerifyMessage(match.signatureAlgorithm(), match.keyStorePair().privateKey(), false);
            handshakeMessages.add(certificateVerifyMessage);
            getPacketProtector().getTranscriptHash().offer(certificateVerifyMessage, false);
            if (LOG.isDebugEnabled())
                LOG.debug("produced {} on {}", certificateVerifyMessage, this);
        }

        FinishedMessage finishedMessage = createFinishedMessage(cipherSuite);
        handshakeMessages.add(finishedMessage);
        getPacketProtector().getTranscriptHash().offer(finishedMessage, false);
        if (LOG.isDebugEnabled())
            LOG.debug("produced {} on {}", finishedMessage, this);

        getPacketProtector().generateOneRTTKeys(quicVersion, cipherSuite);

        state = clientAuthentication ? State.NEED_CERTIFICATE : State.NEED_FINISHED;

        if (LOG.isDebugEnabled())
            LOG.debug("handshake completed on {}", this);

        notifyOutgoingMessages(EncryptionLevel.INITIAL, List.of(serverHello), Callback.from(Invocable.InvocationType.NON_BLOCKING,
            () -> notifyOutgoingMessages(EncryptionLevel.HANDSHAKE, handshakeMessages, Callback.from(Callback.NOOP, this::fail)),
            this::fail
        ));
    }

    private SignatureWithKeyStorePair selectCertificate(List<SignatureWithKeyStorePair> pairs, String serverName, boolean sniRequired) throws Exception
    {
        if (serverName == null && sniRequired)
            return null;

        SignatureWithKeyStorePair candidate;
        for (SignatureWithKeyStorePair pair : pairs)
        {
            // Try to match by server name.
            if (serverName != null)
            {
                X509Certificate leaf = pair.keyStorePair().certificates().getFirst();
                if (!identityMatches(leaf, serverName))
                    continue;
            }

            candidate = pair;

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
                        LOG.debug("invalid certificate {} on {}", certificate, this, x);
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

    private void processCertificateMessage(CertificateMessage message)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", message, this);

        if (state == State.HANDSHAKE_FAILED)
            return;
        if (state != State.NEED_CERTIFICATE)
            throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));

        SslContextFactory sslContextFactory = tlsConfiguration.getSslContextFactory();
        List<X509Certificate> certificateChain = message.entries().stream()
            .map(CertificateMessage.Entry::certificate)
            .toList();

        if (!certificateChain.isEmpty())
        {
            // RFC-8446[4.4.2.4]: MD5 and SHA1 are forbidden.
            for (X509Certificate x509 : certificateChain)
            {
                String certificateSignatureAlgorithm = x509.getSigAlgName();
                if (certificateSignatureAlgorithm.startsWith("MD5") || certificateSignatureAlgorithm.startsWith("SHA1"))
                    throw new TLSException(TLSException.Alert.BAD_CERTIFICATE, "forbidden_certificate_signature_algorithm");
            }

            verifyCertificateChain(sslContextFactory, certificateChain);
        }

        getPacketProtector().getTranscriptHash().offer(message, true);

        if (certificateChain.isEmpty())
        {
            state = State.NEED_FINISHED;
        }
        else
        {
            setPeerCertificate(message);
            state = State.NEED_CERTIFICATE_VERIFY;
        }
    }

    private void processCertificateVerifyMessage(CertificateVerifyMessage certificateVerify) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", certificateVerify, this);

        if (state == State.HANDSHAKE_FAILED)
            return;
        if (state != State.NEED_CERTIFICATE_VERIFY)
            throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));

        processCertificateVerifyMessage(tlsConfiguration.getServerQuicConfiguration().getSignatureAlgorithms(), certificateVerify, true);

        state = State.NEED_FINISHED;
    }

    private void processFinishedMessage(FinishedMessage finished) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", finished, this);

        if (state == State.HANDSHAKE_FAILED)
            return;
        SslContextFactory.Server sslContextFactory = tlsConfiguration.getSslContextFactory();
        // Certificate was mandatory, and the client did not send it.
        if (sslContextFactory.getNeedClientAuth() && state != State.NEED_FINISHED)
            throw new TLSException(TLSException.Alert.CERTIFICATE_REQUIRED, "missing_certificate");
        // Certificate was optional, and the client did not send it.
        if (sslContextFactory.getWantClientAuth() && state == State.NEED_CERTIFICATE)
            state = State.NEED_FINISHED;

        if (state != State.NEED_FINISHED)
            throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));

        CipherSuite cipherSuite = getCipherSuite();
        if (!verifyFinishedMessage(cipherSuite, finished))
            throw new TLSException(TLSException.Alert.DECRYPT_ERROR, "invalid_finished_verify_data");

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
        if (ticket != null)
        {
            List<Extension> extensions = List.of(new EarlyDataExtension(serverQuicConfiguration.getEarlyMaxData()));
            NewSessionTicketMessage newSessionTicket = new NewSessionTicketMessage(lifetime, ageAdd, configuration.nonce(), ticket, extensions);
            notifyOutgoingMessages(EncryptionLevel.ONE_RTT, List.of(newSessionTicket), Callback.from(Callback.NOOP, this::fail));
        }
    }

    public byte[] createRetryIntegrity(RetainableByteBuffer retryPacketBuffer, byte[] originalDestinationConnectionId) throws Exception
    {
        // RFC-9001[5.8]: build a retry pseudo-packet.
        // The buffer contains up to the token bytes but no integrity bytes.
        return createRetryIntegrity(retryPacketBuffer, originalDestinationConnectionId, false);
    }

    @Override
    public void tryFail(Throwable failure)
    {
        boolean fail = switch (state)
        {
            case HANDSHAKE_SUCCESSFUL, HANDSHAKE_FAILED -> false;
            default -> true;
        };
        if (fail)
            fail(failure);
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
