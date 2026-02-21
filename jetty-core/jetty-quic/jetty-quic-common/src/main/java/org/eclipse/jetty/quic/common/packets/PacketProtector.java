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

package org.eclipse.jetty.quic.common.packets;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.KDF;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.HKDFParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.PacketBuffers;
import org.eclipse.jetty.quic.common.internal.Decrypter;
import org.eclipse.jetty.quic.common.internal.Encrypter;
import org.eclipse.jetty.quic.common.internal.packets.QuicCrypto;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.TLSException;
import org.eclipse.jetty.tls.common.HKDF;
import org.eclipse.jetty.tls.common.TranscriptHash;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Performs QUIC packet protection and unprotection as per
/// [RFC 9001](https://datatracker.ietf.org/doc/html/rfc9001).
public class PacketProtector implements Encrypter, Decrypter
{
    private static final Logger LOG = LoggerFactory.getLogger(PacketProtector.class);

    private final Map<EncryptionLevel, KeyManager> keyManagers = new ConcurrentHashMap<>();
    private final ByteBufferPool byteBufferPool;
    private final PacketNumbers packetNumbers;
    private final TranscriptHash transcriptHash;
    private final boolean client;
    private SecretKey handshakeSecret;
    private SecretKey masterSecret;

    public PacketProtector(ByteBufferPool byteBufferPool, PacketNumbers packetNumbers, TranscriptHash transcriptHash, boolean client)
    {
        this.byteBufferPool = byteBufferPool;
        this.packetNumbers = packetNumbers;
        this.transcriptHash = transcriptHash;
        this.client = client;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    public TranscriptHash getTranscriptHash()
    {
        return transcriptHash;
    }

    public SecretKey getTrafficSecretKey(boolean input)
    {
        return keyManagers.get(EncryptionLevel.HANDSHAKE).getTrafficSecretKey(input);
    }

    public void generateInitialKeys(QuicVersion quicVersion, byte[] inputKeyMaterial)
    {
        try
        {
            KeyManager keyManager = new KeyManager(EncryptionLevel.INITIAL);
            if (keyManagers.put(EncryptionLevel.INITIAL, keyManager) != null)
                throw new IllegalStateException("KeyManager already exists at encryption level " + EncryptionLevel.INITIAL);

            // RFC 9001, 5.2: initial secrets use SHA256.
            KDF kdf = KDF.getInstance("HKDF-SHA256");
            HKDFParameterSpec.Extract spec = HKDFParameterSpec.ofExtract().addSalt(QuicCrypto.initialSalt(quicVersion)).addIKM(inputKeyMaterial).extractOnly();
            SecretKey prk = kdf.deriveKey("InitialPseudoRandom", spec);

            SecretKey clientTraffic = kdf.deriveKey("InitialClientTraffic", HKDF.expandLabel(prk, "client in", 32));
            SecretKey clientEncryption = kdf.deriveKey("AES", HKDF.expandLabel(clientTraffic, QuicCrypto.encryptionLabel(quicVersion), 16));
            SecretKey clientInitialization = kdf.deriveKey("AES", HKDF.expandLabel(clientTraffic, QuicCrypto.initializationVectorLabel(quicVersion), 12));
            SecretKey clientProtection = kdf.deriveKey("AES", HKDF.expandLabel(clientTraffic, QuicCrypto.headerProtectionLabel(quicVersion), 16));

            SecretKey serverTraffic = kdf.deriveKey("InitialServerTraffic", HKDF.expandLabel(prk, "server in", 32));
            SecretKey serverEncryption = kdf.deriveKey("AES", HKDF.expandLabel(serverTraffic, QuicCrypto.encryptionLabel(quicVersion), 16));
            SecretKey serverInitialization = kdf.deriveKey("AES", HKDF.expandLabel(serverTraffic, QuicCrypto.initializationVectorLabel(quicVersion), 12));
            SecretKey serverProtection = kdf.deriveKey("AES", HKDF.expandLabel(serverTraffic, QuicCrypto.headerProtectionLabel(quicVersion), 16));

            if (client)
            {
                keyManager.readKeys.updateKeys(serverTraffic, serverEncryption, serverInitialization, serverProtection);
                keyManager.writeKeys.updateKeys(clientTraffic, clientEncryption, clientInitialization, clientProtection);
            }
            else
            {
                keyManager.readKeys.updateKeys(clientTraffic, clientEncryption, clientInitialization, clientProtection);
                keyManager.writeKeys.updateKeys(serverTraffic, serverEncryption, serverInitialization, serverProtection);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("allocated QUIC initial keys on {}", this);
        }
        catch (Throwable x)
        {
            throw TLSException.wrap(x);
        }
    }

    public void generateHandshakeKeys(QuicVersion quicVersion, CipherSuite cipherSuite, SecretKey sharedSecret, HKDFParameterSpec pskSpec)
    {
        try
        {
            KeyManager keyManager = new KeyManager(EncryptionLevel.INITIAL);
            if (keyManagers.put(EncryptionLevel.HANDSHAKE, keyManager) != null)
                throw new IllegalStateException("KeyManager already exists at encryption level " + EncryptionLevel.HANDSHAKE);

            // RFC-8446[7.1].
            int hashLength = cipherSuite.hashLength();
            KDF kdf = KDF.getInstance("HKDF-SHA" + (hashLength * 8));

            byte[] salt = new byte[hashLength];
            byte[] inputKeyMaterial = pskSpec == null ? new byte[hashLength] : kdf.deriveKey("Generic", pskSpec).getEncoded();
            HKDFParameterSpec.Extract extract = HKDFParameterSpec.ofExtract().addSalt(salt).addIKM(inputKeyMaterial).extractOnly();
            SecretKey earlySecret = kdf.deriveKey("Generic", extract);

            SecretKey derivedSecret = kdf.deriveKey("Generic", HKDF.expandLabel(earlySecret, "derived", transcriptHash.getEmptyHash(), hashLength));
            extract = HKDFParameterSpec.ofExtract().addSalt(derivedSecret).addIKM(sharedSecret).extractOnly();
            handshakeSecret = kdf.deriveKey("Generic", extract);

            byte[] tlsHash = transcriptHash.getHash();
            int keyLength = cipherSuite.keyLength();

            SecretKey clientTraffic = kdf.deriveKey("Generic", HKDF.expandLabel(handshakeSecret, "c hs traffic", tlsHash, hashLength));
            SecretKey clientEncryption = kdf.deriveKey("AES", HKDF.expandLabel(clientTraffic, QuicCrypto.encryptionLabel(quicVersion), keyLength));
            SecretKey clientInitialization = kdf.deriveKey("AES", HKDF.expandLabel(clientTraffic, QuicCrypto.initializationVectorLabel(quicVersion), 12));
            SecretKey clientProtection = kdf.deriveKey("AES", HKDF.expandLabel(clientTraffic, QuicCrypto.headerProtectionLabel(quicVersion), keyLength));

            SecretKey serverTraffic = kdf.deriveKey("Generic", HKDF.expandLabel(handshakeSecret, "s hs traffic", tlsHash, hashLength));
            SecretKey serverEncryption = kdf.deriveKey("AES", HKDF.expandLabel(serverTraffic, QuicCrypto.encryptionLabel(quicVersion), keyLength));
            SecretKey serverInitialization = kdf.deriveKey("AES", HKDF.expandLabel(serverTraffic, QuicCrypto.initializationVectorLabel(quicVersion), 12));
            SecretKey serverProtection = kdf.deriveKey("AES", HKDF.expandLabel(serverTraffic, QuicCrypto.headerProtectionLabel(quicVersion), keyLength));

            if (client)
            {
                keyManager.readKeys.updateKeys(serverTraffic, serverEncryption, serverInitialization, serverProtection);
                keyManager.writeKeys.updateKeys(clientTraffic, clientEncryption, clientInitialization, clientProtection);
            }
            else
            {
                keyManager.readKeys.updateKeys(clientTraffic, clientEncryption, clientInitialization, clientProtection);
                keyManager.writeKeys.updateKeys(serverTraffic, serverEncryption, serverInitialization, serverProtection);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("allocated QUIC handshake keys on {}", this);
        }
        catch (Throwable x)
        {
            throw new TLSException(TLSException.Alert.INTERNAL_ERROR, x);
        }
    }

    public void generateApplicationKeys(QuicVersion quicVersion, CipherSuite cipherSuite)
    {
        try
        {
            KeyManager keyManager = new KeyManager(EncryptionLevel.ONE_RTT);
            if (keyManagers.put(EncryptionLevel.ONE_RTT, keyManager) != null)
                throw new IllegalStateException("KeyManager already exists at encryption level " + EncryptionLevel.ONE_RTT);

            // RFC 8446, 7.1.
            int hashLength = cipherSuite.hashLength();
            KDF kdf = KDF.getInstance("HKDF-SHA" + (hashLength * 8));

            SecretKey derivedSecret = kdf.deriveKey("ApplicationDerived", HKDF.expandLabel(handshakeSecret, "derived", transcriptHash.getEmptyHash(), hashLength));
            HKDFParameterSpec.Extract extract = HKDFParameterSpec.ofExtract().addSalt(derivedSecret).addIKM(new byte[hashLength]).extractOnly();
            masterSecret = kdf.deriveKey("ApplicationMaster", extract);

            byte[] tlsHash = transcriptHash.getHash();
            int keyLength = cipherSuite.keyLength();

            SecretKey clientTraffic = kdf.deriveKey("ApplicationClientTraffic", HKDF.expandLabel(masterSecret, "c ap traffic", tlsHash, hashLength));
            SecretKey clientEncryption = kdf.deriveKey("AES", HKDF.expandLabel(clientTraffic, QuicCrypto.encryptionLabel(quicVersion), keyLength));
            SecretKey clientInitialization = kdf.deriveKey("AES", HKDF.expandLabel(clientTraffic, QuicCrypto.initializationVectorLabel(quicVersion), 12));
            SecretKey clientProtection = kdf.deriveKey("AES", HKDF.expandLabel(clientTraffic, QuicCrypto.headerProtectionLabel(quicVersion), keyLength));

            SecretKey serverTraffic = kdf.deriveKey("ApplicationServerTraffic", HKDF.expandLabel(masterSecret, "s ap traffic", tlsHash, hashLength));
            SecretKey serverEncryption = kdf.deriveKey("AES", HKDF.expandLabel(serverTraffic, QuicCrypto.encryptionLabel(quicVersion), keyLength));
            SecretKey serverInitialization = kdf.deriveKey("AES", HKDF.expandLabel(serverTraffic, QuicCrypto.initializationVectorLabel(quicVersion), 12));
            SecretKey serverProtection = kdf.deriveKey("AES", HKDF.expandLabel(serverTraffic, QuicCrypto.headerProtectionLabel(quicVersion), keyLength));

            if (client)
            {
                keyManager.readKeys.updateKeys(serverTraffic, serverEncryption, serverInitialization, serverProtection);
                keyManager.writeKeys.updateKeys(clientTraffic, clientEncryption, clientInitialization, clientProtection);
            }
            else
            {
                keyManager.readKeys.updateKeys(clientTraffic, clientEncryption, clientInitialization, clientProtection);
                keyManager.writeKeys.updateKeys(serverTraffic, serverEncryption, serverInitialization, serverProtection);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("allocated QUIC application keys on {}", this);
        }
        catch (Throwable x)
        {
            throw new TLSException(TLSException.Alert.INTERNAL_ERROR, x);
        }
    }

    public SecretKey createResumptionMasterSecret(CipherSuite cipherSuite) throws Exception
    {
        // RFC-8446[7.1].
        int hashLength = cipherSuite.hashLength();
        KDF kdf = KDF.getInstance("HKDF-SHA" + (hashLength * 8));
        byte[] tlsHash = transcriptHash.getHash();
        SecretKey resumptionMasterSecret = kdf.deriveKey("AES", HKDF.expandLabel(masterSecret, "res master", tlsHash, hashLength));

        // The master secret is not needed anymore, as both the application
        // keys and the resumption master secret have been generated.
        KeyManager.destroy(masterSecret);

        // The resumption master secret does not need to be stored here:
        // it is either stored externally or in the session ticket.
        return resumptionMasterSecret;
    }

    public void discardKeys(EncryptionLevel encryptionLevel)
    {
        // RFC-9001[4.9].
        KeyManager removed = keyManagers.remove(encryptionLevel);
        if (removed != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("discarded {} keys on {}", encryptionLevel, this);
            removed.destroy();
        }
        if (encryptionLevel == EncryptionLevel.HANDSHAKE)
            KeyManager.destroy(handshakeSecret);
    }

    @Override
    public PacketBuffers encrypt(EncryptionLevel encryptionLevel, long packetNumber, RetainableByteBuffer header, RetainableByteBuffer.Mutable payload) throws Exception
    {
        KeyManager keyManager = keyManagers.get(encryptionLevel);
        if (keyManager == null)
            throw new IllegalStateException("no KeyManager for encryption level " + encryptionLevel);
        return keyManager.encrypt(packetNumber, header, payload);
    }

    @Override
    public PacketBuffers decryptLongHeaderPacket(EncryptionLevel encryptionLevel, RetainableByteBuffer encrypted) throws Exception
    {
        KeyManager keyManager = keyManagers.get(encryptionLevel);
        if (keyManager == null)
            throw new IllegalStateException("no KeyManager for encryption level " + encryptionLevel);
        return keyManager.decryptLongHeaderPacket(encrypted);
    }

    @Override
    public PacketBuffers decryptShortHeaderPacket(byte[] dstConnectionId, RetainableByteBuffer encrypted) throws Exception
    {
        KeyManager keyManager = keyManagers.get(EncryptionLevel.ONE_RTT);
        if (keyManager == null)
            throw new IllegalStateException("no KeyManager for encryption level " + EncryptionLevel.ONE_RTT);
        return keyManager.decryptShortHeaderPacket(dstConnectionId, encrypted);
    }

    public byte[] createRetryIntegrity(RetainableByteBuffer retryPacketBuffer, byte[] originalDestinationConnectionId) throws Exception
    {
        // RFC-9001[5.8]: build a retry pseudo-packet.
        // The buffer contains up to the token bytes but no integrity bytes.
        return createRetryIntegrity(retryPacketBuffer, originalDestinationConnectionId, false);
    }

    private byte[] createRetryIntegrity(RetainableByteBuffer retryPacketBuffer, byte[] originalDestinationConnectionId, boolean integrityPresent) throws Exception
    {
        ByteBuffer byteBuffer = retryPacketBuffer.getByteBuffer();
        QuicVersion quicVersion = QuicVersion.from(byteBuffer.getInt(1));
        int capacity = 1 + originalDestinationConnectionId.length + byteBuffer.remaining();
        if (integrityPresent)
            capacity -= 16;
        byte[] pseudoPacket = new byte[capacity];
        int offset = 0;
        pseudoPacket[offset] = (byte)originalDestinationConnectionId.length;
        ++offset;
        System.arraycopy(originalDestinationConnectionId, 0, pseudoPacket, offset, originalDestinationConnectionId.length);
        offset += originalDestinationConnectionId.length;
        byteBuffer.get(pseudoPacket, offset, capacity - offset);

        // RFC-9001[5.8]: compute the integrity.
        KDF kdf = KDF.getInstance("HKDF-SHA256");
        byte[] retryIntegritySecret = QuicCrypto.retryIntegritySecret(quicVersion);
        // Derive the key and the nonce from the secret.
        HKDFParameterSpec spec = HKDF.expandLabel(new SecretKeySpec(retryIntegritySecret, "AES"), QuicCrypto.encryptionLabel(quicVersion), 16);
        SecretKey integrityKey = kdf.deriveKey("AES", spec);
        spec = HKDF.expandLabel(new SecretKeySpec(retryIntegritySecret, "AES"), QuicCrypto.initializationVectorLabel(quicVersion), 12);
        SecretKey integrityNonce = kdf.deriveKey("IntegrityNonce", spec);
        // Generate the integrity.
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, integrityKey, new GCMParameterSpec(128, integrityNonce.getEncoded()));
        cipher.updateAAD(pseudoPacket);
        byte[] bytes = cipher.doFinal();
        LOG.info("generated retry integrity {}", StringUtil.toHexString(bytes));
        return bytes;
    }

    public boolean verifyRetryIntegrity(RetainableByteBuffer.Mutable retryPacketBuffer, byte[] originalDestinationConnectionId) throws Exception
    {
        // RFC-9001[5.8]: build a retry pseudo-packet.
        // The buffer contains up to the integrity bytes (16 bytes).
        byte[] expected = createRetryIntegrity(retryPacketBuffer, originalDestinationConnectionId, true);
        byte[] integrity = new byte[16];
        ByteBuffer byteBuffer = retryPacketBuffer.getByteBuffer();
        byteBuffer.get(integrity);
        // Verify the integrity.
        return MessageDigest.isEqual(integrity, expected);
    }

    public byte[] createPreSharedKeyIdentityBinder(CipherSuite cipherSuite, SecretKey resumptionMasterSecret, byte[] sessionTicketNonce) throws Exception
    {
        int hashLength = cipherSuite.hashLength();
        KDF kdf = KDF.getInstance("HKDF-SHA" + (hashLength * 8));

        SecretKey preSharedKey = kdf.deriveKey("Generic", HKDF.expandLabel(resumptionMasterSecret, "resumption", sessionTicketNonce, hashLength));
        HKDFParameterSpec extract = HKDFParameterSpec.ofExtract().addSalt(new byte[hashLength]).addIKM(preSharedKey).extractOnly();
        SecretKey earlySecret = kdf.deriveKey("Generic", extract);
        SecretKey binderKey = kdf.deriveKey("Generic", HKDF.expandLabel(earlySecret, "res binder", getTranscriptHash().getEmptyHash(), hashLength));
        SecretKey finishedKey = kdf.deriveKey("Generic", HKDF.expandLabel(binderKey, "finished", hashLength));

        Mac mac = Mac.getInstance("HmacSHA" + (hashLength * 8));
        mac.init(finishedKey);
        return mac.doFinal(getTranscriptHash().getHash());
    }

    @Override
    public String toString()
    {
        return "%s@%x".formatted(TypeUtil.toShortName(getClass()), hashCode());
    }

    /// A manager for [read][ReadKeys] and [write][WriteKeys] keys
    /// required to protect, encrypt, unprotect, and decrypt QUIC packets.
    ///
    /// There is one key manager for each [EncryptionLevel].
    private class KeyManager
    {
        private final ReadKeys readKeys = new ReadKeys();
        private final WriteKeys writeKeys = new WriteKeys();
        private final EncryptionLevel encryptionLevel;

        private KeyManager(EncryptionLevel encryptionLevel)
        {
            this.encryptionLevel = encryptionLevel;
        }

        public SecretKey getTrafficSecretKey(boolean input)
        {
            return input ? readKeys.traffic : writeKeys.traffic;
        }

        public PacketBuffers encrypt(long packetNumber, RetainableByteBuffer header, RetainableByteBuffer.Mutable payload) throws Exception
        {
            return writeKeys.encrypt(packetNumber, header, payload);
        }

        public PacketBuffers decryptLongHeaderPacket(RetainableByteBuffer encrypted) throws Exception
        {
            return readKeys.decryptLongHeaderPacket(encrypted);
        }

        public PacketBuffers decryptShortHeaderPacket(byte[] dstConnectionId, RetainableByteBuffer encrypted) throws Exception
        {
            return readKeys.decryptShortHeaderPacket(dstConnectionId, encrypted);
        }

        private static byte[] nonce(SecretKey initialization, long packetNumber)
        {
            // RFC 9001, 5.3.
            // Nonce is IV ^ packetNumber.
            byte[] nonce = initialization.getEncoded();
            for (int i = 0; i < 4; ++i)
            {
                nonce[nonce.length - 1 - i] ^= (byte)(packetNumber & 0xFF);
                packetNumber = packetNumber >>> 8;
            }
            return nonce;
        }

        private void destroy()
        {
            readKeys.destroy();
            writeKeys.destroy();
        }

        private static void destroy(SecretKey secretKey)
        {
            try
            {
                secretKey.destroy();
            }
            catch (Throwable x)
            {
                LOG.atTrace().setCause(x).log("failed to destroy {}", secretKey);
            }
        }

        /// QUIC requires at least two generations of read keys,
        /// since a key update may arrive before a packet that
        /// was sent before the key update, encrypted with previous
        /// keys, but that was delayed by the network or retransmitted.
        ///
        /// @see WriteKeys
        private class ReadKeys
        {
            private SecretKey traffic;
            private SecretKey encryption;
            private SecretKey initialization;
            private SecretKey protection;

            public void updateKeys(SecretKey initial, SecretKey encryption, SecretKey initialization, SecretKey protection)
            {
                this.traffic = initial;
                this.encryption = encryption;
                this.initialization = initialization;
                this.protection = protection;
            }

            public PacketBuffers decryptLongHeaderPacket(RetainableByteBuffer encrypted) throws Exception
            {
                ByteBuffer byteBuffer = encrypted.getByteBuffer();

                // To remove header protection, we need a sample of the payload.
                // RFC 9001, 5.4.2: compute the offset of the sample.
                int position = byteBuffer.position();

                int type = (byteBuffer.get() & 0b00110000) >>> 4;
                QuicVersion version = QuicVersion.from(byteBuffer.getInt());
                LongHeaderPacket.PacketType packetType = LongHeaderPacket.PacketType.from(type, version);
                int dstConnectionIdLength = byteBuffer.get();
                byteBuffer.position(byteBuffer.position() + dstConnectionIdLength);
                int srcConnectionIdLength = byteBuffer.get();
                byteBuffer.position(byteBuffer.position() + srcConnectionIdLength);
                if (packetType == LongHeaderPacket.PacketType.INITIAL)
                {
                    int tokenLength = VarLenInt.decodeInt(byteBuffer);
                    byteBuffer.position(byteBuffer.position() + tokenLength);
                }
                // The payload length at this point also includes
                // the packet number length and the 16-bytes AEAD tag.
                int payloadLength = VarLenInt.decodeInt(byteBuffer);
                int packetNumberPosition = byteBuffer.position();
                // Packet number length is at most 4 bytes.
                byteBuffer.position(packetNumberPosition + 4);
                byte[] sample = new byte[16];
                byteBuffer.get(sample);

                // TODO: algorithm name only valid for AES ciphers, not CHACHA20.
                Cipher ecbCipher = Cipher.getInstance("AES/ECB/NoPadding");
                ecbCipher.init(Cipher.ENCRYPT_MODE, protection);
                byte[] mask = ecbCipher.doFinal(sample);

                int firstByte = byteBuffer.get(position) & 0xFF;
                // Long header packets mask 4 bits.
                byte form = (byte)(firstByte ^ (mask[0] & 0xF));
                int encodedPacketNumberLength = (form & 0x03) + 1;
                payloadLength -= encodedPacketNumberLength;

                // Prepare the decrypted header buffer.
                int headerLength = packetNumberPosition + encodedPacketNumberLength - position;
                RetainableByteBuffer decryptedHeaderBuffer = byteBufferPool.acquire(headerLength, true);
                ByteBuffer decryptedHeader = decryptedHeaderBuffer.getByteBuffer();
                decryptedHeader.clear();
                decryptedHeader.put(byteBuffer.slice(position, headerLength)).flip();
                decryptedHeader.put(form);

                // Unmask the packet number.
                byteBuffer.position(packetNumberPosition);
                decryptedHeader.position(packetNumberPosition - position);
                int encodedPacketNumber = 0;
                for (int i = 0; i < encodedPacketNumberLength; ++i)
                {
                    int unmasked = byteBuffer.get() ^ mask[i + 1];
                    decryptedHeader.put((byte)unmasked);
                    encodedPacketNumber = (encodedPacketNumber << (i * 8)) | unmasked;
                }
                decryptedHeader.position(0);

                EncodedPacketNumber encoded = new EncodedPacketNumber(encodedPacketNumber, encodedPacketNumberLength);
                long packetNumber = packetNumbers.decode(encryptionLevel, encoded);

                byte[] nonce = nonce(initialization, packetNumber);
                // TODO: algorithm name only valid for AES ciphers, not CHACHA20.
                //  Same for GCMParameterSpec, and the length depends on the cipher.
                Cipher gcmCipher = Cipher.getInstance("AES/GCM/NoPadding");
                gcmCipher.init(Cipher.DECRYPT_MODE, encryption, new GCMParameterSpec(128, nonce));

                // Supply AAD as the plaintext packet header.
                gcmCipher.updateAAD(decryptedHeader);
                decryptedHeader.flip();

                // Decrypt the payload.
                ByteBuffer encryptedPayload = byteBuffer.slice(byteBuffer.position(), payloadLength);
                byteBuffer.position(byteBuffer.position() + payloadLength);
                RetainableByteBuffer decryptedPayloadBuffer = byteBufferPool.acquire(payloadLength, true);
                ByteBuffer decryptedPayload = decryptedPayloadBuffer.getByteBuffer();
                decryptedPayload.clear();
                gcmCipher.doFinal(encryptedPayload, decryptedPayload);
                decryptedPayload.flip();

                return new PacketBuffers(decryptedHeaderBuffer, decryptedPayloadBuffer);
            }

            private PacketBuffers decryptShortHeaderPacket(byte[] dstConnectionId, RetainableByteBuffer encrypted) throws Exception
            {
                ByteBuffer byteBuffer = encrypted.getByteBuffer();

                // To remove header protection, we need a sample of the payload.
                // RFC-9001[5.4.2]: compute the offset of the sample.
                int position = byteBuffer.position();

                // Skip form byte and destination connection ID bytes.
                byteBuffer.position(position + 1 + dstConnectionId.length);
                int packetNumberPosition = byteBuffer.position();
                // Packet number length is at most 4 bytes.
                byteBuffer.position(packetNumberPosition + 4);
                byte[] sample = new byte[16];
                byteBuffer.get(sample);

                Cipher ecbCipher = Cipher.getInstance("AES/ECB/NoPadding");
                ecbCipher.init(Cipher.ENCRYPT_MODE, protection);
                byte[] mask = ecbCipher.doFinal(sample);

                int firstByte = byteBuffer.get(position) & 0xFF;
                // Short header packets mask 5 bits.
                byte form = (byte)(firstByte ^ (mask[0] & 0x1F));
                int encodedPacketNumberLength = (form & 0x03) + 1;

                // Prepare the decrypted header buffer.
                int headerLength = packetNumberPosition + encodedPacketNumberLength - position;
                RetainableByteBuffer decryptedHeaderBuffer = byteBufferPool.acquire(headerLength, true);
                ByteBuffer decryptedHeader = decryptedHeaderBuffer.getByteBuffer();
                decryptedHeader.clear();
                decryptedHeader.put(byteBuffer.slice(position, headerLength)).flip();
                decryptedHeader.put(form);

                // Unmask the packet number.
                byteBuffer.position(packetNumberPosition);
                decryptedHeader.position(packetNumberPosition - position);
                int encodedPacketNumber = 0;
                for (int i = 0; i < encodedPacketNumberLength; ++i)
                {
                    int unmasked = byteBuffer.get() ^ mask[i + 1];
                    decryptedHeader.put((byte)unmasked);
                    encodedPacketNumber = (encodedPacketNumber << (i * 8)) | unmasked;
                }
                decryptedHeader.position(0);

                EncodedPacketNumber encoded = new EncodedPacketNumber(encodedPacketNumber, encodedPacketNumberLength);
                long packetNumber = packetNumbers.decode(encryptionLevel, encoded);

                byte[] nonce = nonce(initialization, packetNumber);
                Cipher gcmCipher = Cipher.getInstance("AES/GCM/NoPadding");
                gcmCipher.init(Cipher.DECRYPT_MODE, encryption, new GCMParameterSpec(128, nonce));

                // Supply AAD as the plaintext packet header.
                gcmCipher.updateAAD(decryptedHeader);
                decryptedHeader.flip();

                // Decrypt the payload.
                byteBuffer.position(packetNumberPosition + encodedPacketNumberLength);
                // Short header packets are always the only or the last in the datagram.
                int payloadLength = byteBuffer.remaining();
                ByteBuffer encryptedPayload = byteBuffer.slice(byteBuffer.position(), payloadLength);
                byteBuffer.position(byteBuffer.position() + payloadLength);
                RetainableByteBuffer decryptedPayloadBuffer = byteBufferPool.acquire(payloadLength, true);
                ByteBuffer decryptedPayload = decryptedPayloadBuffer.getByteBuffer();
                decryptedPayload.clear();
                gcmCipher.doFinal(encryptedPayload, decryptedPayload);
                decryptedPayload.flip();

                return new PacketBuffers(decryptedHeaderBuffer, decryptedPayloadBuffer);
            }

            private void destroy()
            {
                KeyManager.destroy(traffic);
                KeyManager.destroy(encryption);
                KeyManager.destroy(initialization);
                KeyManager.destroy(protection);
            }
        }

        /// QUIC requires only one generation of write keys.
        /// Packets that need retransmissions are encrypted
        /// with the new keys, and the reader will be able
        /// to decrypt retransmitted packets since it maintains
        /// two generations of keys.
        ///
        /// @see ReadKeys
        private class WriteKeys
        {
            private SecretKey traffic;
            private SecretKey encryption;
            private SecretKey initialization;
            private SecretKey protection;

            private void updateKeys(SecretKey initial, SecretKey encryption, SecretKey initialization, SecretKey protection)
            {
                this.traffic = initial;
                this.encryption = encryption;
                this.initialization = initialization;
                this.protection = protection;
            }

            private PacketBuffers encrypt(long packetNumber, RetainableByteBuffer header, RetainableByteBuffer.Mutable payload) throws Exception
            {
                byte[] nonce = nonce(initialization, packetNumber);

                Cipher gcmCipher = Cipher.getInstance("AES/GCM/NoPadding");
                gcmCipher.init(Cipher.ENCRYPT_MODE, encryption, new GCMParameterSpec(128, nonce));

                // Supply AAD as the plaintext packet header.
                ByteBuffer headerByteBuffer = header.getByteBuffer();
                gcmCipher.updateAAD(headerByteBuffer);
                headerByteBuffer.flip();

                // RFC-9001[5.4.2]: pad payload if necessary.
                // Packet protection requires 16 bytes of sample,
                // offset by 4 bytes from the packet number,
                // so we must pad up to 4 bytes.
                if (payload.size() < 4)
                    payload.putInt(0);

                // Encrypt the payload.
                // AEAD encryption produces 16 additional bytes.
                ByteBuffer payloadByteBuffer = payload.getByteBuffer();
                RetainableByteBuffer.Mutable encryptedPayloadBuffer = byteBufferPool.acquire(payloadByteBuffer.remaining() + 16, true);
                ByteBuffer encryptedPayload = encryptedPayloadBuffer.getByteBuffer();
                encryptedPayload.clear();
                gcmCipher.doFinal(payloadByteBuffer, encryptedPayload);
                encryptedPayload.flip();

                // RFC 9001, 5.4.2: header protection sample.
                EncodedPacketNumber encodedPacketNumber = packetNumbers.encode(encryptionLevel, packetNumber);
                int pktNumLen = encodedPacketNumber.length();
                int sampleOffset = 4 - pktNumLen;
                byte[] sample = new byte[16];
                encryptedPayload.get(sampleOffset, sample);

                Cipher ecbCipher = Cipher.getInstance("AES/ECB/NoPadding");
                ecbCipher.init(Cipher.ENCRYPT_MODE, protection);
                byte[] mask = ecbCipher.doFinal(sample);

                int position = headerByteBuffer.position();
                RetainableByteBuffer.Mutable encryptedHeaderBuffer = byteBufferPool.acquire(headerByteBuffer.remaining(), true);
                ByteBuffer encryptedHeader = encryptedHeaderBuffer.getByteBuffer();
                encryptedHeader.clear();
                encryptedHeader.put(headerByteBuffer).flip();
                // Long header packets mask 4 bits, short header packets mask 5 bits.
                int firstByte = headerByteBuffer.get(position) & 0xFF;
                int bits = (firstByte & 0x80) == 0x80 ? 0x0F : 0x1F;
                encryptedHeader.put(0, (byte)(firstByte ^ (mask[0] & bits)));

                // Mask the packet number.
                int start = encryptedHeader.limit() - pktNumLen;
                for (int i = 0; i < pktNumLen; ++i)
                {
                    int pktNumByte = headerByteBuffer.get(start + i) & 0xFF;
                    encryptedHeader.put(start + i, (byte)(pktNumByte ^ mask[i + 1]));
                }

                return new PacketBuffers(encryptedHeaderBuffer, encryptedPayloadBuffer);
            }

            private void destroy()
            {
                KeyManager.destroy(traffic);
                KeyManager.destroy(encryption);
                KeyManager.destroy(initialization);
                KeyManager.destroy(protection);
            }
        }
    }
}
