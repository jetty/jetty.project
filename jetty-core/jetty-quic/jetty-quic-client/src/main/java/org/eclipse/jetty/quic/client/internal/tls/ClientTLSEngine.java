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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.crypto.SecretKey;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.tls.ext.QuicTransportParametersExtension;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.ZeroRTTStore;
import org.eclipse.jetty.quic.common.packets.PacketProtector;
import org.eclipse.jetty.quic.common.tls.HandshakeData;
import org.eclipse.jetty.quic.common.tls.TLSEngine;
import org.eclipse.jetty.tls.CertificateMessage;
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
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// The client-side implementation of QUIC TLS state machine for QUIC.
public class ClientTLSEngine extends TLSEngine
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientTLSEngine.class);

    private State state = State.INITIAL;
    private ClientTLSConfiguration configuration;
    private List<GroupKeyPair> groupKeyPairs;
    private ClientHelloMessage clientHello;

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

            this.configuration = configuration;

            List<Extension> extensions = new ArrayList<>();
            String serverName = configuration.getServerName();
            extensions.add(new ServerNameExtension(serverName));
            setServerName(serverName);
            extensions.add(new ALPNExtension(configuration.getApplicationProtocols()));
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
                ZeroRTTStore.Entry zeroRTTEntry = configuration.getZeroRTTStore().match(entry ->
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

                    // RFC-8446[4.2.11]: the pre-shared key extension must be the last.
                    // RFC-8446[4.2.11.2]: truncate the extension to compute the binders.
                    extensions.add(new ClientPreSharedKeyExtension(List.of(truncated), false));

                    ClientHelloMessage truncatedClientHello = new ClientHelloMessage(newRandomBytes(32), cipherSuites, extensions);
                    getPacketProtector().getTranscriptHash().offer(truncatedClientHello, false);

                    byte[] binder = getPacketProtector().createPreSharedKeyIdentityBinder(cipherSuite, zeroRTTEntry.secretKey(), zeroRTTEntry.newSessionTicket().nonce());
                    PreSharedKeyIdentity identity = new PreSharedKeyIdentity(zeroRTTEntry.newSessionTicket().ticket(), zeroRTTEntry.obfuscatedTicketAge(), binder);

                    getPacketProtector().getTranscriptHash().clear();
                    extensions.removeLast();
                    extensions.add(new ClientPreSharedKeyExtension(List.of(identity), true));
                    clientHello = new ClientHelloMessage(clientHello.random(), clientHello.cipherSuites(), extensions);
                }
            }

            if (clientHello == null)
                clientHello = new ClientHelloMessage(newRandomBytes(32), cipherSuites, extensions);
            if (LOG.isDebugEnabled())
                LOG.debug("produced {} on {}", clientHello, this);

            getPacketProtector().getTranscriptHash().offer(clientHello, false);

            PacketProtector packetProtector = getPacketProtector();
            byte[] inputKeyMaterial = configuration.getInputKeyMaterial();
            packetProtector.generateInitialKeys(configuration.getQuicVersion(), inputKeyMaterial);

            // Notifies back the QuicSession to send this message in a CRYPTO frame.
            notifyMessages(EncryptionLevel.INITIAL, List.of(clientHello), Callback.from(callback, this::fail));
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

            if (state != State.NEED_SERVER_HELLO)
                throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));

            // RetryPacket is a QUIC mechanism, and as such
            // the ClientHello sent in the first InitialPacket
            // must be discarded from the TranscriptHash.
            getPacketProtector().getTranscriptHash().clear();
            getPacketProtector().getTranscriptHash().offer(clientHello, false);
            notifyMessages(EncryptionLevel.INITIAL, List.of(clientHello), Callback.from(Callback.NOOP, this::fail));
        }
        catch (Throwable x)
        {
            fail(x);
        }
    }

    @Override
    public void onMessageParsed(Message message)
    {
        try
        {
            switch (message)
            {
                case ServerHelloMessage serverHello -> processServerHello(serverHello);
                case EncryptedExtensionsMessage encryptedExtensions -> processEncryptedExtensions(encryptedExtensions);
                case CertificateMessage certificate -> processCertificate(certificate);
                case CertificateVerifyMessage certificateVerify -> processCertificateVerify(certificateVerify);
                case FinishedMessage finished -> processFinished(finished);
                case NewSessionTicketMessage newSessionTicket -> processNewSessionTicket(newSessionTicket);
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

    private void processServerHello(ServerHelloMessage serverHello)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", serverHello, this);

        if (state != State.NEED_SERVER_HELLO)
            throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));
        if (serverHello.sessionId().length != 0)
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid_legacy_session_id");

        // TODO
//        if (Arrays.equals(serverHello.random(), HelloRetryRequest.RANDOM))
//        {
//            processHelloRetryRequest(serverHello);
//            return;
//        }

        List<Extension> extensions = serverHello.extensions();
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

        // RFC-8446[4.1.3]: SupportedVersionsExtension must be present.
        if (serverVersions.isEmpty())
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "missing_supported_versions_extension");

        // RFC-8446[4.2.1]: negotiate TLS version.
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

        // RFC-8446[4.1.3]: the client must have offered the CipherSuite.
        CipherSuite cipherSuite = serverHello.cipherSuite();
        clientHello.cipherSuites().stream()
            .filter(cipherSuite::equals)
            .findFirst()
            .orElseThrow(() -> new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "no_common_cipher_suite"));
        setCipherSuite(cipherSuite);
        getPacketProtector().getTranscriptHash().initialize(cipherSuite);
        if (LOG.isDebugEnabled())
            LOG.debug("negotiated CipherSuite {} on {}", cipherSuite, this);

        // RFC-8446[4.1.3]: Either KeyShareExtension or PreSharedKeyExtension or both must be present.
        if (keyShares.isEmpty() && pskIndex < 0)
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "missing_key_shares");

        if (pskIndex >= 0)
        {
        }
        else
        {
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

            getPacketProtector().getTranscriptHash().offer(serverHello, true);
            getPacketProtector().generateHandshakeKeys(configuration.getQuicVersion(), cipherSuite, sharedSecret);

            state = State.NEED_ENCRYPTED_EXTENSIONS;
        }
    }

    private void processEncryptedExtensions(EncryptedExtensionsMessage encryptedExtensions)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", encryptedExtensions, this);

        if (state != State.NEED_ENCRYPTED_EXTENSIONS)
            throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));

        List<Extension> extensions = encryptedExtensions.extensions();
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

        getPacketProtector().getTranscriptHash().offer(encryptedExtensions, true);

        state = State.NEED_CERTIFICATE;
    }

    private void processCertificate(CertificateMessage certificate)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", certificate, this);

        if (state != State.NEED_CERTIFICATE)
            throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));
        // RFC-8446[4.4.2.4].
        if (certificate.entries().isEmpty())
            throw new TLSException(TLSException.Alert.DECODE_ERROR, "missing_certificate");

        // TODO: if verification required MD5/SHA1 signatures -> bad_certificate

        // TODO: Verify the certificate chain (JDK utility should be present).
        //  Verify Signature chains
        //  Verify Validity periods
        //  Verify Key usage / extended key usage
        //  Verify Name constraints
        //  Verify Server identity (SNI / DNS name / IP)
        //  Verify that The server certificate’s public key type Matches the negotiated signature_algorithms

        // TODO: save the certificate, as it must be verified later.
        getPacketProtector().getTranscriptHash().offer(certificate, true);

        state = State.NEED_CERTIFICATE_VERIFY;
    }

    private void processCertificateVerify(CertificateVerifyMessage certificateVerify)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", certificateVerify, this);

        if (state != State.NEED_CERTIFICATE_VERIFY)
            throw new IllegalStateException("invalid_tls_state_" + state.name().toLowerCase(Locale.ROOT));

        // TODO: verify the signature using the public key from the Certificate
        getPacketProtector().getTranscriptHash().offer(certificateVerify, true);

        state = State.NEED_FINISHED;
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

        if (LOG.isDebugEnabled())
            LOG.debug("verified {} on {}", finished, this);

        getPacketProtector().getTranscriptHash().offer(finished, true);
        getPacketProtector().generateApplicationKeys(configuration.getQuicVersion(), cipherSuite);

        FinishedMessage message = createFinishedMessage(cipherSuite);
        getPacketProtector().getTranscriptHash().offer(message, false);

        state = State.HANDSHAKE_SUCCESSFUL;

        if (LOG.isDebugEnabled())
            LOG.debug("handshake completed on {}", this);

        // RFC-9001[4.1.1]: handshake is complete when the Finished message
        // is sent, and the peer's Finished message has been verified.
        Callback callback = Callback.from(Invocable.InvocationType.NON_BLOCKING, this::handshakeSuccessful, this::handshakeFailed);
        notifyMessages(EncryptionLevel.HANDSHAKE, List.of(message), callback);
    }

    private void handshakeSuccessful()
    {
        HandshakeData handshakeData = new HandshakeData(configuration.getQuicVersion(), getTLSVersion(), getServerName(), getCipherSuite(), getApplicationProtocol(), getTransportParameters());
        notifyHandshakeCompleted(handshakeData, null);
    }

    private void handshakeFailed(Throwable failure)
    {
        notifyHandshakeCompleted(null, failure);
    }

    private void processNewSessionTicket(NewSessionTicketMessage newSessionTicket) throws Exception
    {
        HandshakeData handshakeData = new HandshakeData(configuration.getQuicVersion(), getTLSVersion(), getServerName(), getCipherSuite(), getApplicationProtocol(), getTransportParameters());
        configuration.getZeroRTTStore().store(new ZeroRTTStore.Entry(handshakeData, newSessionTicket, getPacketProtector().createResumptionMasterSecret(getCipherSuite())));
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
