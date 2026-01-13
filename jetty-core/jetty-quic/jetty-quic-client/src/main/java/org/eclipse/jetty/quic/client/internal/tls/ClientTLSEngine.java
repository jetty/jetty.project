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

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.common.packets.PacketProtector;
import org.eclipse.jetty.quic.common.tls.SharedSecretGenerator;
import org.eclipse.jetty.quic.common.tls.TLSEngine;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.ClientHelloMessage;
import org.eclipse.jetty.tls.KeyShare;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.NamedGroup;
import org.eclipse.jetty.tls.ServerHelloMessage;
import org.eclipse.jetty.tls.SignatureAlgorithm;
import org.eclipse.jetty.tls.TLSException;
import org.eclipse.jetty.tls.TLSVersion;
import org.eclipse.jetty.tls.common.GroupKeyPair;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.KeyShareExtension;
import org.eclipse.jetty.tls.ext.SignatureAlgorithmsExtension;
import org.eclipse.jetty.tls.ext.SupportedGroupsExtension;
import org.eclipse.jetty.tls.ext.SupportedVersionsExtension;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// The client-side implementation of QUIC encryption/decryption,
/// and the client-side TLS state machine necessary for QUIC.
public class ClientTLSEngine extends TLSEngine
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientTLSEngine.class);

    private List<GroupKeyPair> groupKeyPairs;
    private ClientHelloMessage clientHello;
    private TLSVersion tlsVersion;
    private CipherSuite cipherSuite;

    public ClientTLSEngine(PacketProtector protector)
    {
        super(protector, true);
    }

    public void startHandshake(Configuration configuration, Callback callback)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("starting handshake with {} on {}", configuration, this);

            assert clientHello == null;

            List<CipherSuite> cipherSuites = configuration.cipherSuites();

            List<NamedGroup> namedGroups = configuration.namedGroups();
            configuration.extension(new SupportedGroupsExtension(namedGroups));

            // KeyPairs and KeyShares.
            groupKeyPairs = new ArrayList<>();
            List<KeyShare> keyShares = new ArrayList<>();
            for (NamedGroup namedGroup : namedGroups)
            {
                GroupKeyPair groupKeyPair = GroupKeyPair.from(namedGroup);
                groupKeyPairs.add(groupKeyPair);
                keyShares.add(groupKeyPair.toKeyShare());
            }
            configuration.extension(new KeyShareExtension(keyShares));

            configuration.extension(new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3)));

            List<SignatureAlgorithm> signatureAlgorithms = configuration.signatureAlgorithms();
            configuration.extension(new SignatureAlgorithmsExtension(signatureAlgorithms));

            clientHello = new ClientHelloMessage(newRandomBytes(32), cipherSuites, configuration.extensions());
            if (LOG.isDebugEnabled())
                LOG.debug("produced {} on {}", clientHello, this);

            getPacketProtector().allocateInitialKeys(configuration.quicVersion(), configuration.inputKeyMaterial());

            // Notifies back the QuicSession to send this message in a CRYPTO frame.
            notifyMessages(List.of(clientHello), callback);
        }
        catch (Throwable x)
        {
            callback.failed(x);
        }
    }

    public void retryHandshake(Callback callback)
    {
        assert clientHello != null;
        notifyMessages(List.of(clientHello), callback);
    }

    @Override
    public void onMessageGenerated(Message message, RetainableByteBuffer buffer)
    {
        // TODO: add buffer to TranscriptHash
    }

    @Override
    public void onMessageParsed(Message message)
    {
        // TODO: add buffer to TranscriptHash
        //  Perhaps it's faster to re-serialize again the message, rather than
        //  trying to keep around the buffers the message has been parsed from,
        //  as they can be split in nasty ways (e.g. buffer1=half serverhello,
        //  buffer2=half serverhello + half encryptedextensions, etc.) and we
        //  cannot trust the order of the buffers (must verify messages are sent
        //  in the proper order required by TLS).
        //  Note that it must be a different MessagesGenerator to avoid concurrency
        //  with the one we use for writing (as here we are on the read side).

        switch (message)
        {
            case ServerHelloMessage serverHello -> processServerHello(serverHello);
            default -> throw new IllegalStateException("unexpected message " + message);
        }
    }

    private void processServerHello(ServerHelloMessage serverHello)
    {
        if (serverHello.sessionId().length != 0)
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid legacy session id");

        // TODO
//        if (Arrays.equals(serverHello.random(), HelloRetryRequest.RANDOM))
//        {
//            processHelloRetryRequest(serverHello);
//            return;
//        }

        List<Extension> extensions = serverHello.extensions();
        List<TLSVersion> serverVersions = List.of();
        List<KeyShare> keyShares = List.of();
        List<KeyShare> preSharedKeys = List.of();
        for (Extension extension : extensions)
        {
            switch (extension)
            {
                case SupportedVersionsExtension sve -> serverVersions = sve.versions();
                case KeyShareExtension kse -> keyShares = kse.keyShares();
                // TODO
//                case PSK pske -> preSharedKeys = pske.preSharedKeys();
                default -> throw new TLSException(TLSException.Alert.UNSUPPORTED_EXTENSION, "unexpected extension " + extension);
            }
        }

        // RFC 8446, 4.1.3: SupportedVersionsExtension must be present.
        if (serverVersions.isEmpty())
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "missing SupportedVersionsExtension");

        // RFC 8446, 4.1.3: Either KeyShareExtension or PreSharedKeyExtension or both must be present.
        if (keyShares.isEmpty() && preSharedKeys.isEmpty())
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "missing KeyShareExtension or PreSharedKeyExtension");

        // RFC 8446, 4.2.1: negotiate versions with ClientHello.
        List<TLSVersion> clientVersions = clientHello.extensions().stream()
            .filter(e -> e instanceof SupportedVersionsExtension)
            .map(SupportedVersionsExtension.class::cast)
            .map(SupportedVersionsExtension::versions)
            .findFirst()
            .orElse(List.of());
        List<TLSVersion> negotiatedVersions = new ArrayList<>(serverVersions);
        negotiatedVersions.retainAll(clientVersions);
        if (negotiatedVersions.isEmpty())
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "no common TLSVersion");
        tlsVersion = negotiatedVersions.getFirst();

        // RFC 8446, 4.1.3: the client must have offered the CipherSuite.
        CipherSuite serverCipherSuite = serverHello.cipherSuite();
        clientHello.cipherSuites().stream()
            .filter(serverCipherSuite::equals)
            .findFirst()
            .orElseThrow(() -> new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "no common CipherSuite"));
        cipherSuite = serverCipherSuite;

        if (!keyShares.isEmpty())
        {
            KeyShare serverKeyShare = keyShares.getFirst();
            // RFC 8446, 4.2.8: the client must have offered the group.
            List<KeyShare> clientKeyShares = clientHello.extensions().stream()
                .filter(e -> e instanceof KeyShareExtension)
                .map(KeyShareExtension.class::cast)
                .map(KeyShareExtension::keyShares)
                .findFirst()
                .orElse(List.of());
            clientKeyShares.stream()
                .map(KeyShare::group)
                .filter(serverKeyShare.group()::equals)
                .findFirst()
                .orElseThrow(() -> new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "no common NamedGroup"));
            // Find the corresponding GroupKeyPair whose KeyShare is in the ClientHello.
            GroupKeyPair groupKeyPair = groupKeyPairs.stream()
                .filter(gkp -> gkp.group() == serverKeyShare.group())
                .findFirst()
                .orElseThrow(() -> new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "no common NamedGroup"));
            byte[] sharedSecret = SharedSecretGenerator.verifyAndGenerate(groupKeyPair, serverKeyShare);
//            getPacketProtector().allocateHandshakeKeys(sharedSecret);
        }
        else
        {
            // TODO: PSK
        }
    }

    public static class Configuration
    {
        private List<SignatureAlgorithm> signatureAlgorithms = List.of(SignatureAlgorithm.RSA_PKCS1_SHA256, SignatureAlgorithm.ECDSA_SECP256R1_SHA256, SignatureAlgorithm.RSA_PSS_RSAE_SHA256);
        private List<NamedGroup> namedGroups = List.of(NamedGroup.x25519, NamedGroup.secp256r1, NamedGroup.ffdhe2048);
        private List<CipherSuite> cipherSuites = List.of(CipherSuite.values());
        private final List<Extension> extensions = new ArrayList<>();
        private QuicVersion quicVersion = QuicVersion.V1;
        private byte[] inputKeyMaterial;

        public Configuration signatureAlgorithms(List<SignatureAlgorithm> signatureAlgorithms)
        {
            this.signatureAlgorithms = signatureAlgorithms;
            return this;
        }

        public List<SignatureAlgorithm> signatureAlgorithms()
        {
            return signatureAlgorithms;
        }

        public Configuration namedGroups(List<NamedGroup> namedGroups)
        {
            this.namedGroups = namedGroups;
            return this;
        }

        public List<NamedGroup> namedGroups()
        {
            return namedGroups;
        }

        public Configuration cipherSuites(List<CipherSuite> cipherSuites)
        {
            this.cipherSuites = cipherSuites;
            return this;
        }

        public List<CipherSuite> cipherSuites()
        {
            return cipherSuites;
        }

        public Configuration extension(Extension extension)
        {
            extensions.add(extension);
            return this;
        }

        public List<Extension> extensions()
        {
            return extensions;
        }

        public Configuration quicVersion(QuicVersion quicVersion)
        {
            this.quicVersion = quicVersion;
            return this;
        }

        public QuicVersion quicVersion()
        {
            return quicVersion;
        }

        public Configuration inputKeyMaterial(byte[] inputKeyMaterial)
        {
            this.inputKeyMaterial = inputKeyMaterial;
            return this;
        }

        public byte[] inputKeyMaterial()
        {
            return inputKeyMaterial;
        }
    }
}
