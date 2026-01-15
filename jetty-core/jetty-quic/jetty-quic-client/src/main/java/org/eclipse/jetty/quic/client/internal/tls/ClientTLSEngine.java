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

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.KDF;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.common.packets.PacketProtector;
import org.eclipse.jetty.quic.common.tls.HKDF;
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

    private Configuration configuration;
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

            this.configuration = configuration;

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

        // RetryPacket is a QUIC mechanism, and as such
        // the ClientHello sent in the first InitialPacket
        // must be discarded from the TranscriptHash.
        getPacketProtector().getTranscriptHash().clear();
        notifyMessages(List.of(clientHello), callback);
    }

    @Override
    public void onMessageGenerated(Message message)
    {
        getPacketProtector().getTranscriptHash().offer(message, false);
    }

    @Override
    public void onMessageParsed(Message message)
    {
        switch (message)
        {
            case ServerHelloMessage serverHello -> processServerHello(serverHello);
            case EncryptedExtensionsMessage encryptedExtensions -> processEncryptedExtensions(encryptedExtensions);
            case CertificateMessage certificate -> processCertificate(certificate);
            case CertificateVerifyMessage certificateVerify -> processCertificateVerify(certificateVerify);
            case FinishedMessage finished -> processFinished(finished);
            default -> throw new IllegalStateException("unexpected message " + message);
        }
    }

    private void processServerHello(ServerHelloMessage serverHello)// throws Exception
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
        if (LOG.isDebugEnabled())
            LOG.debug("negotiated TLSVersion {}", tlsVersion);

        // RFC 8446, 4.1.3: the client must have offered the CipherSuite.
        CipherSuite serverCipherSuite = serverHello.cipherSuite();
        clientHello.cipherSuites().stream()
            .filter(serverCipherSuite::equals)
            .findFirst()
            .orElseThrow(() -> new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "no common CipherSuite"));
        cipherSuite = serverCipherSuite;
        if (LOG.isDebugEnabled())
            LOG.debug("negotiated CipherSuite {}", cipherSuite);

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
            SecretKey sharedSecret = groupKeyPair.generateSharedSecret(serverKeyShare);
            if (LOG.isDebugEnabled())
                LOG.debug("negotiated KeyPair in NamedGroup {}", serverKeyShare.group());

            getPacketProtector().getTranscriptHash().offer(serverHello, true);
            getPacketProtector().allocateHandshakeKeys(configuration.quicVersion(), cipherSuite, sharedSecret);
        }
        else
        {
            // TODO: PSK
        }
    }

    private void processEncryptedExtensions(EncryptedExtensionsMessage encryptedExtensions)
    {
        // TODO: validate extensions are allowed (e.g. key_share_extension not allowed in EncryptedExtensionsMessage).

        // TODO: ALPN must be present and match what offered by in the CLientHello.

        // TODO: QuicTransports must be present and validated:
        //  * No forbidden parameters are present
        //  * No duplicates
        //  * Values are within allowed ranges
        //  Apply Quic transport params to the various components.
        //   This would require reach back to the session, must use a listener.

        getPacketProtector().getTranscriptHash().offer(encryptedExtensions, true);
    }

    private void processCertificate(CertificateMessage certificate)
    {
        // RFC 8446, 4.4.2.4.
        if (certificate.entries().isEmpty())
            throw new TLSException(TLSException.Alert.DECODE_ERROR, "no certificate");

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
    }

    private void processCertificateVerify(CertificateVerifyMessage certificateVerify)
    {
        // TODO: verify the signature using the public key from the Certificate
        getPacketProtector().getTranscriptHash().offer(certificateVerify, true);
    }

    private void processFinished(FinishedMessage finished)
    {
        try
        {
            // RFC 8446, 4.4.4.
            byte[] verifyData = finished.verifyData();
            int hashLength = cipherSuite.hashLength();
            if (verifyData.length != hashLength)
                throw new TLSException(TLSException.Alert.DECODE_ERROR, "invalid verify data length");

            int shaLength = hashLength * 8;
            KDF kdf = KDF.getInstance("HKDF-SHA" + shaLength);
            SecretKey trafficKey = getPacketProtector().getTrafficSecretKey();
            SecretKey finishedKey = kdf.deriveKey("Generic", HKDF.expandLabel(trafficKey, "finished", hashLength));

            Mac mac = Mac.getInstance("HmacSHA" + shaLength);
            mac.init(finishedKey);
            byte[] expected = mac.doFinal(getPacketProtector().getTranscriptHash().getHash());

            // Differently from Arrays.equals(), MessageDigest.isEqual() implements constant-time comparison.
            if (!MessageDigest.isEqual(verifyData, expected))
                throw new TLSException(TLSException.Alert.DECODE_ERROR, "invalid verify data");

            getPacketProtector().getTranscriptHash().offer(finished, true);

//            getPacketProtector().allocateApplicationKeys();
        }
        catch (TLSException x)
        {
            throw x;
        }
        catch (Throwable x)
        {
            throw new TLSException(TLSException.Alert.INTERNAL_ERROR, x);
        }
    }

    public static class Configuration
    {
        private List<SignatureAlgorithm> signatureAlgorithms = List.of(SignatureAlgorithm.RSA_PKCS1_SHA256, SignatureAlgorithm.ECDSA_SECP256R1_SHA256, SignatureAlgorithm.RSA_PSS_RSAE_SHA256);
        private List<NamedGroup> namedGroups = List.of(NamedGroup.x25519/*, NamedGroup.secp256r1, NamedGroup.ffdhe2048*/);
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
