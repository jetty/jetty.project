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

package org.eclipse.jetty.quic.client.internal.tls;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.crypto.SecretKey;
import javax.crypto.spec.HKDFParameterSpec;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.ZeroRTTStore;
import org.eclipse.jetty.quic.common.packets.PacketProtector;
import org.eclipse.jetty.quic.common.tls.HandshakeData;
import org.eclipse.jetty.quic.common.tls.TLSEngine;
import org.eclipse.jetty.quic.common.tls.X509KeyStorePair;
import org.eclipse.jetty.quic.common.tls.ext.QuicTransportParametersExtension;
import org.eclipse.jetty.tls.CertificateMessage;
import org.eclipse.jetty.tls.CertificateRequestMessage;
import org.eclipse.jetty.tls.CertificateVerifyMessage;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.ClientHelloMessage;
import org.eclipse.jetty.tls.EncryptedExtensionsMessage;
import org.eclipse.jetty.tls.FinishedMessage;
import org.eclipse.jetty.tls.KeyShare;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.NamedGroup;
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
import org.eclipse.jetty.tls.ext.SupportedGroupsExtension;
import org.eclipse.jetty.tls.ext.SupportedVersionsExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// The client-side implementation of QUIC TLS state machine for QUIC.
public class ClientTLSEngine extends TLSEngine
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientTLSEngine.class);

    private State state = State.INITIAL;
    private ClientTLSConfiguration tlsConfiguration;
    private List<GroupKeyPair> groupKeyPairs;
    private ClientHelloMessage clientHello;
    private CertificateRequestMessage certificateRequest;
    private ZeroRTTStore.Entry zeroRTTEntry;
    private boolean resuming;
    private List<X509KeyStorePair> keyStorePairs;

    public ClientTLSEngine(PacketProtector protector)
    {
        super(protector, true);
    }

    public void startHandshake(ClientTLSConfiguration configuration, Callback callback)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("starting handshake with {} on {}", configuration, this);

            if (state != State.INITIAL)
                throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));
            state = State.NEED_SERVER_HELLO;

            tlsConfiguration = configuration;

            List<Extension> extensions = new ArrayList<>();
            String serverName = configuration.getServerName();
            if (serverName != null)
                extensions.add(new ServerNameExtension(serverName));
            setServerName(serverName);
            List<String> applicationProtocols = configuration.getApplicationProtocols();
            if (applicationProtocols != null && !applicationProtocols.isEmpty())
                extensions.add(new ALPNExtension(applicationProtocols));
            extensions.add(new QuicTransportParametersExtension(configuration.getTransportParameters()));

            List<CipherSuite> cipherSuites = configuration.getClientQuicConfiguration().getCipherSuites();

            List<NamedGroup> namedGroups = configuration.getClientQuicConfiguration().getNamedGroups();
            extensions.add(new SupportedGroupsExtension(namedGroups));

            // KeyPairs and KeyShares.
            groupKeyPairs = new ArrayList<>();
            List<KeyShare> keyShares = new ArrayList<>();
            for (NamedGroup namedGroup : namedGroups)
            {
                GroupKeyPair groupKeyPair = GroupKeyPair.from(namedGroup);
                groupKeyPairs.add(groupKeyPair);
                keyShares.add(groupKeyPair.toKeyShare());
            }
            extensions.add(new KeyShareExtension(keyShares));

            extensions.add(new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3)));

            List<SignatureAlgorithm> signatureAlgorithms = configuration.getClientQuicConfiguration().getSignatureAlgorithms();
            extensions.add(new SignatureAlgorithmsExtension(signatureAlgorithms));

            // Check whether the application wants to resume a connection or send early data.
            RetainableByteBuffer earlyDataByteBuffer = configuration.getEarlyData();
            if (earlyDataByteBuffer != null)
            {
                zeroRTTEntry = configuration.getZeroRTTStore().match(entry ->
                {
                    if (!serverName.equals(entry.handshakeData().serverName()))
                        return false;
                    if (!cipherSuites.contains(entry.handshakeData().cipherSuite()))
                        return false;
                    return true;
                });

                if (zeroRTTEntry != null)
                {
                    long earlyMaxData = zeroRTTEntry.newSessionTicket().extensions().stream()
                        .filter(e -> e instanceof EarlyDataExtension)
                        .map(EarlyDataExtension.class::cast)
                        .map(EarlyDataExtension::maxData)
                        .findFirst()
                        .orElse(0L);

                    int earlyData = earlyDataByteBuffer.remaining();
                    if (earlyData > 0 && earlyData <= earlyMaxData)
                    {
                        // Just add the presence of the early data extension.
                        extensions.add(new EarlyDataExtension(0));
                    }

                    CipherSuite cipherSuite = zeroRTTEntry.handshakeData().cipherSuite();
                    int hashLength = cipherSuite.hashLength();
                    PreSharedKeyIdentity truncated = new PreSharedKeyIdentity(zeroRTTEntry.newSessionTicket().ticket(), zeroRTTEntry.obfuscatedTicketAge(), new byte[hashLength]);

                    // RFC-8446 #4.2.11: the pre-shared key extension must be the last.
                    // RFC-8446 #4.2.11.2: truncate the extension to compute the binders.
                    extensions.add(new ClientPreSharedKeyExtension(List.of(truncated), false));

                    ClientHelloMessage truncatedClientHello = new ClientHelloMessage(newRandomBytes(32), cipherSuites, extensions);
                    getPacketProtector().getTranscriptHash().offer(truncatedClientHello, false);

                    getPacketProtector().getTranscriptHash().initialize(cipherSuite);
                    byte[] binder = createPreSharedKeyIdentityBinder(cipherSuite, zeroRTTEntry.resumptionMasterSecret(), zeroRTTEntry.newSessionTicket().nonce());
                    PreSharedKeyIdentity identity = truncated.withBinder(binder);

                    getPacketProtector().getTranscriptHash().clear();
                    extensions.removeLast();
                    extensions.add(new ClientPreSharedKeyExtension(List.of(identity), true));
                    clientHello = new ClientHelloMessage(truncatedClientHello.random(), truncatedClientHello.cipherSuites(), extensions);
                }
            }

            if (clientHello == null)
                clientHello = new ClientHelloMessage(newRandomBytes(32), cipherSuites, extensions);
            if (LOG.isDebugEnabled())
                LOG.debug("produced {} on {}", clientHello, this);

            getPacketProtector().getTranscriptHash().offer(clientHello, false);

            byte[] inputKeyMaterial = configuration.getInputKeyMaterial();
            getPacketProtector().generateInitialKeys(configuration.getQuicVersion(), inputKeyMaterial);

            // Notifies back the QuicSession to send this message in a CRYPTO frame.
            notifyOutgoingMessages(EncryptionLevel.INITIAL, List.of(clientHello), Callback.from(callback, this::fail));
        }
        catch (Throwable x)
        {
            callback.failed(x);
            fail(x);
        }
    }

    public void retryHandshake()
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("retrying handshake on {}", this);

            if (state == State.HANDSHAKE_FAILED)
                return;
            if (state != State.NEED_SERVER_HELLO)
                throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));

            // RetryPacket is a QUIC mechanism, and as such
            // the ClientHello sent in the first InitialPacket
            // must be discarded from the TranscriptHash.
            getPacketProtector().getTranscriptHash().clear();
            getPacketProtector().getTranscriptHash().offer(clientHello, false);
            notifyOutgoingMessages(EncryptionLevel.INITIAL, List.of(clientHello), Callback.from(Callback.NOOP, this::fail));
        }
        catch (Throwable x)
        {
            fail(x);
        }
    }

    /// Entry point to receive and process TLS messages.
    ///
    /// @param message the TLS message to process
    @Override
    public void onMessage(EncryptionLevel encryptionLevel, Message message)
    {
        try
        {
            super.onMessage(encryptionLevel, message);
            switch (message)
            {
                case ServerHelloMessage serverHello -> processServerHelloMessage(serverHello);
                case EncryptedExtensionsMessage encryptedExtensions -> processEncryptedExtensionsMessage(encryptedExtensions);
                case CertificateRequestMessage certificateRequest -> processCertificateRequestMessage(certificateRequest);
                case CertificateMessage certificate -> processCertificateMessage(certificate);
                case CertificateVerifyMessage certificateVerify -> processCertificateVerifyMessage(certificateVerify);
                case FinishedMessage finished -> processFinishedMessage(finished);
                case NewSessionTicketMessage newSessionTicket -> processNewSessionTicketMessage(newSessionTicket);
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

    private void processServerHelloMessage(ServerHelloMessage message)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", message, this);

        if (state == State.HANDSHAKE_FAILED)
            return;
        if (state != State.NEED_SERVER_HELLO)
            throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));
        if (message.sessionId().length != 0)
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid_legacy_session_id");

        // TODO
//        if (Arrays.equals(serverHello.random(), HelloRetryRequest.RANDOM))
//        {
//            processHelloRetryRequest(serverHello);
//            return;
//        }

        List<Extension> extensions = message.extensions();
        List<TLSVersion> serverVersions = List.of();
        List<KeyShare> keyShares = List.of();
        int pskIndex = -1;
        for (Extension extension : extensions)
        {
            switch (extension)
            {
                case SupportedVersionsExtension sve -> serverVersions = sve.versions();
                case KeyShareExtension kse -> keyShares = kse.keyShares();
                case ServerPreSharedKeyExtension pske -> pskIndex = pske.identityIndex();
                default ->
                {
                }
            }
        }

        // Only one PSK identity was sent to the server.
        resuming = zeroRTTEntry != null && pskIndex == 0;
        if (LOG.isDebugEnabled())
            LOG.debug("{} handshake on {}", resuming ? "resuming" : "new", this);

        // RFC-8446 #4.1.3: SupportedVersionsExtension must be present.
        if (serverVersions.isEmpty())
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "missing_supported_versions_extension");

        // RFC-8446 #4.2.1: negotiate TLS version.
        List<TLSVersion> clientVersions = clientHello.extensions().stream()
            .filter(e -> e instanceof SupportedVersionsExtension)
            .map(SupportedVersionsExtension.class::cast)
            .map(SupportedVersionsExtension::versions)
            .findFirst()
            .orElse(List.of());
        List<TLSVersion> negotiatedVersions = new ArrayList<>(serverVersions);
        negotiatedVersions.retainAll(clientVersions);
        if (negotiatedVersions.isEmpty())
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "no_common_tls_version");
        TLSVersion tlsVersion = negotiatedVersions.getFirst();
        setTLSVersion(tlsVersion);
        if (LOG.isDebugEnabled())
            LOG.debug("negotiated TLS version {} on {}", tlsVersion, this);

        // RFC-8446 #4.1.3: the client must have offered the CipherSuite.
        CipherSuite cipherSuite = message.cipherSuite();
        clientHello.cipherSuites().stream()
            .filter(cipherSuite::equals)
            .findFirst()
            .orElseThrow(() -> new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "no_common_cipher_suite"));
        setCipherSuite(cipherSuite);
        getPacketProtector().getTranscriptHash().initialize(cipherSuite);
        if (LOG.isDebugEnabled())
            LOG.debug("negotiated CipherSuite {} on {}", cipherSuite, this);

        // RFC-8446 #4.1.3: Either KeyShareExtension or PreSharedKeyExtension or both must be present.
        // In this implementation, always require the KeyShareExtension to support forward secrecy.
        if (keyShares.isEmpty())
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "missing_key_shares");

        // RFC-8446 #4.2.11: negotiated cipher and PSK cipher
        // may be different, but must have the same hash length.
        if (resuming && zeroRTTEntry.handshakeData().cipherSuite().hashLength() != cipherSuite.hashLength())
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "cipher_suite_hash_mismatch");

        KeyShare serverKeyShare = keyShares.getFirst();
        // RFC 8446, 4.2.8: the client must have offered the named group.
        List<KeyShare> clientKeyShares = clientHello.extensions().stream()
            .filter(e -> e instanceof KeyShareExtension)
            .map(KeyShareExtension.class::cast)
            .map(KeyShareExtension::keyShares)
            .findFirst()
            .orElse(List.of());
        clientKeyShares.stream()
            .map(KeyShare::namedGroup)
            .filter(serverKeyShare.namedGroup()::equals)
            .findFirst()
            .orElseThrow(() -> new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "no_common_named_group"));
        // Find the corresponding GroupKeyPair whose KeyShare is in the ClientHello.
        GroupKeyPair groupKeyPair = groupKeyPairs.stream()
            .filter(gkp -> gkp.group() == serverKeyShare.namedGroup())
            .findFirst()
            .orElseThrow(() -> new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "no_common_named_group"));
        SecretKey sharedSecret = groupKeyPair.generateSharedSecret(serverKeyShare);
        if (LOG.isDebugEnabled())
            LOG.debug("negotiated KeyPair in NamedGroup {} on {}", serverKeyShare.namedGroup(), this);

        getPacketProtector().getTranscriptHash().offer(message, true);
        HKDFParameterSpec pskSpec = null;
        if (resuming)
            pskSpec = HKDF.expandLabel(zeroRTTEntry.resumptionMasterSecret(), "resumption", zeroRTTEntry.newSessionTicket().nonce(), cipherSuite.hashLength());
        getPacketProtector().generateHandshakeKeys(tlsConfiguration.getQuicVersion(), cipherSuite, sharedSecret, pskSpec);

        state = State.NEED_ENCRYPTED_EXTENSIONS;
    }

    private void processEncryptedExtensionsMessage(EncryptedExtensionsMessage message)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", message, this);

        if (state == State.HANDSHAKE_FAILED)
            return;
        if (state != State.NEED_ENCRYPTED_EXTENSIONS)
            throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));

        List<Extension> extensions = message.extensions();
        List<String> serverProtocols = List.of();
        for (Extension extension : extensions)
        {
            switch (extension)
            {
                case ALPNExtension alpn -> serverProtocols = alpn.protocols();
                case KeyShareExtension _, ClientPreSharedKeyExtension _, SupportedVersionsExtension _ ->
                    throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "forbidden_extension");
                case QuicTransportParametersExtension qtpe -> setTransportParameters(qtpe.transportParameters());
                default ->
                {
                }
            }
        }

        List<String> clientProtocols = clientHello.extensions().stream()
            .filter(e -> e instanceof ALPNExtension)
            .map(ALPNExtension.class::cast)
            .findFirst()
            .map(ALPNExtension::protocols)
            .orElse(List.of());

        if (clientProtocols.isEmpty() && serverProtocols.isEmpty())
        {
            // No application protocol, just QUIC.
        }
        else
        {
            List<String> negotiatedProtocols = new ArrayList<>(serverProtocols);
            negotiatedProtocols.retainAll(clientProtocols);
            if (negotiatedProtocols.isEmpty())
                throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "no_common_application_protocol");
            setApplicationProtocol(negotiatedProtocols.getFirst());
        }

        getPacketProtector().getTranscriptHash().offer(message, true);

        state = resuming ? State.NEED_FINISHED : State.NEED_CERTIFICATE;
    }

    private void processCertificateRequestMessage(CertificateRequestMessage message) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", message, this);

        if (state == State.HANDSHAKE_FAILED)
            return;
        if (state != State.NEED_CERTIFICATE)
            throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));

        if (message.context().length != 0)
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "unexpected_certificate_request_context");

        // Process the CertificateRequestMessage after receiving the FinishedMessage
        // from the server, to keep the TranscriptHash of client and server in sync.
        certificateRequest = message;

        getPacketProtector().getTranscriptHash().offer(message, true);
    }

    private void processCertificateMessage(CertificateMessage message)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", message, this);

        if (state == State.HANDSHAKE_FAILED)
            return;
        if (state != State.NEED_CERTIFICATE)
            throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));

        List<X509Certificate> certificateChain = message.entries().stream()
            .map(CertificateMessage.Entry::certificate)
            .toList();

        // RFC-8446 #4.4.2.4: the server cannot send an empty certificate message.
        if (certificateChain.isEmpty())
            throw new TLSException(TLSException.Alert.DECODE_ERROR, "missing_certificate");

        // RFC-8446 #4.4.2.4: MD5 and SHA1 are forbidden.
        for (X509Certificate x509 : certificateChain)
        {
            String certificateSignatureAlgorithm = x509.getSigAlgName();
            if (certificateSignatureAlgorithm.startsWith("MD5") || certificateSignatureAlgorithm.startsWith("SHA1"))
                throw new TLSException(TLSException.Alert.BAD_CERTIFICATE, "forbidden_certificate_signature_algorithm");
        }

        // RFC-2818 #3: verify server identity.
        SslContextFactory sslContextFactory = tlsConfiguration.getSslContextFactory();
        verifyCertificateChain(sslContextFactory, certificateChain);
        String endpointIdentificationAlgorithm = sslContextFactory.getEndpointIdentificationAlgorithm();
        if (StringUtil.isNotBlank(endpointIdentificationAlgorithm))
        {
            if (!identityMatches(certificateChain.getFirst(), getServerName()))
                throw new TLSException(TLSException.Alert.BAD_CERTIFICATE, "certificate_identity_mismatch");
        }

        getPacketProtector().getTranscriptHash().offer(message, true);
        setPeerCertificate(message);
        state = State.NEED_CERTIFICATE_VERIFY;
    }

    private void processCertificateVerifyMessage(CertificateVerifyMessage message) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", message, this);

        if (state == State.HANDSHAKE_FAILED)
            return;
        if (state != State.NEED_CERTIFICATE_VERIFY)
            throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));

        processCertificateVerifyMessage(tlsConfiguration.getClientQuicConfiguration().getSignatureAlgorithms(), message, false);

        state = State.NEED_FINISHED;
    }

    private void processFinishedMessage(FinishedMessage message) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", message, this);

        if (state == State.HANDSHAKE_FAILED)
            return;
        if (state != State.NEED_FINISHED)
            throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));
        CipherSuite cipherSuite = getCipherSuite();
        if (!verifyFinishedMessage(cipherSuite, message))
            throw new TLSException(TLSException.Alert.DECRYPT_ERROR, "invalid_finished_verify_data");

        if (LOG.isDebugEnabled())
            LOG.debug("verified {} on {}", message, this);

        getPacketProtector().getTranscriptHash().offer(message, true);
        getPacketProtector().generateOneRTTKeys(tlsConfiguration.getQuicVersion(), cipherSuite);

        List<Message> messages = new ArrayList<>();

        if (certificateRequest != null)
            messages.addAll(postProcessCertificateRequestMessage(certificateRequest));

        FinishedMessage finished = createFinishedMessage(cipherSuite);
        messages.add(finished);
        getPacketProtector().getTranscriptHash().offer(finished, false);

        state = State.HANDSHAKE_SUCCESSFUL;

        if (LOG.isDebugEnabled())
            LOG.debug("handshake completed on {}", this);

        // RFC-9001 #4.1.1: handshake is complete when the Finished message
        // is sent, and the peer's Finished message has been verified.
        Callback callback = Callback.from(Invocable.InvocationType.NON_BLOCKING, this::handshakeSuccessful, this::handshakeFailed);
        notifyOutgoingMessages(EncryptionLevel.HANDSHAKE, messages, callback);
    }

    private List<Message> postProcessCertificateRequestMessage(CertificateRequestMessage message) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("post-processing {} on {}", certificateRequest, this);

        List<SignatureAlgorithm> signatureAlgorithms = message.extensions().stream()
            .filter(e -> e instanceof SignatureAlgorithmsExtension)
            .map(SignatureAlgorithmsExtension.class::cast)
            .findFirst()
            .map(SignatureAlgorithmsExtension::signatureAlgorithms)
            .orElse(List.of());
        if (signatureAlgorithms.isEmpty())
            throw new TLSException(TLSException.Alert.MISSING_EXTENSION, "missing_signature_algorithms_extension");

        SslContextFactory.Client sslContextFactory = tlsConfiguration.getSslContextFactory();

        if (keyStorePairs == null)
            keyStorePairs = loadKeyStore(sslContextFactory.getKeyStore(), sslContextFactory.getKeyStorePassword());

        SignatureAlgorithm algorithmMatch = null;
        X509KeyStorePair match = null;
        if (!keyStorePairs.isEmpty())
        {
            String alias = sslContextFactory.getCertAlias();
            for (SignatureAlgorithm signatureAlgorithm : signatureAlgorithms)
            {
                for (X509KeyStorePair keyStorePair : keyStorePairs)
                {
                    if (signatureAlgorithm.supports(keyStorePair.certificates().getFirst().getPublicKey()))
                    {
                        if (alias != null && !alias.equals(keyStorePair.alias()))
                            continue;
                        algorithmMatch = signatureAlgorithm;
                        match = keyStorePair;
                        break;
                    }
                }
                if (match != null)
                    break;
            }
        }

        List<Message> messages = new ArrayList<>();

        // RFC-8446 #4.4.2: if no match, send an empty Certificate message.
        List<CertificateMessage.Entry> entries = match == null ? List.of() : match.certificates().stream()
            .map(c -> new CertificateMessage.Entry(c, List.of()))
            .toList();
        CertificateMessage certificate = new CertificateMessage(BufferUtil.EMPTY_BYTES, entries);
        messages.add(certificate);
        getPacketProtector().getTranscriptHash().offer(certificate, false);
        if (LOG.isDebugEnabled())
            LOG.debug("produced {} on {}", certificate, this);

        if (match != null)
        {
            CertificateVerifyMessage certificateVerify = createCertificateVerifyMessage(algorithmMatch, match.privateKey(), true);
            messages.add(certificateVerify);
            getPacketProtector().getTranscriptHash().offer(certificateVerify, false);
            if (LOG.isDebugEnabled())
                LOG.debug("produced {} on {}", certificateVerify, this);
        }

        return messages;
    }

    private void handshakeSuccessful()
    {
        HandshakeData handshakeData = new HandshakeData(tlsConfiguration.getQuicVersion(), getTLSVersion(), getServerName(), getCipherSuite(), getApplicationProtocol(), getTransportParameters());
        notifyHandshakeCompleted(handshakeData, null);
    }

    private void handshakeFailed(Throwable failure)
    {
        notifyHandshakeCompleted(null, failure);
    }

    public boolean verifyRetryIntegrity(RetainableByteBuffer.Mutable retryPacketBuffer, byte[] originalDestinationConnectionId) throws Exception
    {
        // RFC-9001 #5.8: build a retry pseudo-packet.
        // The buffer contains up to the integrity bytes (16 bytes).
        byte[] expected = createRetryIntegrity(retryPacketBuffer, originalDestinationConnectionId, true);
        byte[] integrity = new byte[16];
        ByteBuffer byteBuffer = retryPacketBuffer.getByteBuffer();
        byteBuffer.get(integrity);
        // Verify the integrity.
        return MessageDigest.isEqual(integrity, expected);
    }

    private void processNewSessionTicketMessage(NewSessionTicketMessage newSessionTicket) throws Exception
    {
        HandshakeData handshakeData = new HandshakeData(tlsConfiguration.getQuicVersion(), getTLSVersion(), getServerName(), getCipherSuite(), getApplicationProtocol(), getTransportParameters());
        tlsConfiguration.getZeroRTTStore().store(new ZeroRTTStore.Entry(handshakeData, newSessionTicket, getPacketProtector().createResumptionMasterSecret(getCipherSuite())));
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
        INITIAL,
        NEED_SERVER_HELLO,
        NEED_ENCRYPTED_EXTENSIONS,
        NEED_CERTIFICATE,
        NEED_CERTIFICATE_VERIFY,
        NEED_FINISHED,
        HANDSHAKE_SUCCESSFUL,
        HANDSHAKE_FAILED
    }
}
