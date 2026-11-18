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
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.KDF;
import javax.crypto.SecretKey;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.HKDFParameterSpec;
import javax.crypto.spec.IvParameterSpec;

import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.internal.Decrypter;
import org.eclipse.jetty.quic.common.internal.Encrypter;
import org.eclipse.jetty.quic.common.internal.packets.QuicCrypto;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.TLSException;
import org.eclipse.jetty.tls.common.HKDF;
import org.eclipse.jetty.tls.common.TranscriptHash;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.tls.CipherSuite.TLS_AES_128_GCM_SHA256_CODE;
import static org.eclipse.jetty.tls.CipherSuite.TLS_AES_256_GCM_SHA384_CODE;
import static org.eclipse.jetty.tls.CipherSuite.TLS_CHACHA20_POLY1305_SHA256_CODE;

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
            CipherSuite cipherSuite = CipherSuite.TLS_AES_128_GCM_SHA256;
            // RFC-9001 #5.2: initial keys may be regenerated in case of Retry packets.
            KeyManager keyManager = keyManagers.computeIfAbsent(EncryptionLevel.INITIAL, k -> new KeyManager(k, cipherSuite));

            // RFC 9001, 5.2: initial secrets use SHA256.
            KDF kdf = KDF.getInstance("HKDF-SHA256");
            HKDFParameterSpec.Extract spec = HKDFParameterSpec.ofExtract().addSalt(QuicCrypto.initialSalt(quicVersion)).addIKM(inputKeyMaterial).extractOnly();
            SecretKey prk = kdf.deriveKey("Generic", spec);

            SecretKey clientTraffic = kdf.deriveKey("Generic", HKDF.expandLabel(prk, "client in", 32));
            SecretKey clientEncryption = kdf.deriveKey(cipherSuite.algorithm(), HKDF.expandLabel(clientTraffic, QuicCrypto.encryptionLabel(quicVersion), 16));
            SecretKey clientInitialization = kdf.deriveKey("Generic", HKDF.expandLabel(clientTraffic, QuicCrypto.initializationVectorLabel(quicVersion), 12));
            SecretKey clientProtection = kdf.deriveKey(cipherSuite.algorithm(), HKDF.expandLabel(clientTraffic, QuicCrypto.headerProtectionLabel(quicVersion), 16));

            SecretKey serverTraffic = kdf.deriveKey("Generic", HKDF.expandLabel(prk, "server in", 32));
            SecretKey serverEncryption = kdf.deriveKey(cipherSuite.algorithm(), HKDF.expandLabel(serverTraffic, QuicCrypto.encryptionLabel(quicVersion), 16));
            SecretKey serverInitialization = kdf.deriveKey("Generic", HKDF.expandLabel(serverTraffic, QuicCrypto.initializationVectorLabel(quicVersion), 12));
            SecretKey serverProtection = kdf.deriveKey(cipherSuite.algorithm(), HKDF.expandLabel(serverTraffic, QuicCrypto.headerProtectionLabel(quicVersion), 16));

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
                LOG.debug("generated {} keys on {}", EncryptionLevel.INITIAL, this);
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
            KeyManager keyManager = new KeyManager(EncryptionLevel.HANDSHAKE, cipherSuite);
            if (keyManagers.put(EncryptionLevel.HANDSHAKE, keyManager) != null)
                throw new IllegalStateException("KeyManager already exists at encryption level " + EncryptionLevel.HANDSHAKE);

            // RFC-8446 #7.1.
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
            SecretKey clientEncryption = kdf.deriveKey(cipherSuite.algorithm(), HKDF.expandLabel(clientTraffic, QuicCrypto.encryptionLabel(quicVersion), keyLength));
            SecretKey clientInitialization = kdf.deriveKey("Generic", HKDF.expandLabel(clientTraffic, QuicCrypto.initializationVectorLabel(quicVersion), 12));
            SecretKey clientProtection = kdf.deriveKey(cipherSuite.algorithm(), HKDF.expandLabel(clientTraffic, QuicCrypto.headerProtectionLabel(quicVersion), keyLength));

            SecretKey serverTraffic = kdf.deriveKey("Generic", HKDF.expandLabel(handshakeSecret, "s hs traffic", tlsHash, hashLength));
            SecretKey serverEncryption = kdf.deriveKey(cipherSuite.algorithm(), HKDF.expandLabel(serverTraffic, QuicCrypto.encryptionLabel(quicVersion), keyLength));
            SecretKey serverInitialization = kdf.deriveKey("Generic", HKDF.expandLabel(serverTraffic, QuicCrypto.initializationVectorLabel(quicVersion), 12));
            SecretKey serverProtection = kdf.deriveKey(cipherSuite.algorithm(), HKDF.expandLabel(serverTraffic, QuicCrypto.headerProtectionLabel(quicVersion), keyLength));

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
                LOG.debug("generated {} keys on {}", EncryptionLevel.HANDSHAKE, this);
        }
        catch (Throwable x)
        {
            throw TLSException.wrap(x);
        }
    }

    public void generateOneRTTKeys(QuicVersion quicVersion, CipherSuite cipherSuite)
    {
        try
        {
            KeyManager keyManager = new KeyManager(EncryptionLevel.ONE_RTT, cipherSuite);
            if (keyManagers.put(EncryptionLevel.ONE_RTT, keyManager) != null)
                throw new IllegalStateException("KeyManager already exists at encryption level " + EncryptionLevel.ONE_RTT);

            // RFC 8446, 7.1.
            int hashLength = cipherSuite.hashLength();
            KDF kdf = KDF.getInstance("HKDF-SHA" + (hashLength * 8));

            SecretKey derivedSecret = kdf.deriveKey("Generic", HKDF.expandLabel(handshakeSecret, "derived", transcriptHash.getEmptyHash(), hashLength));
            HKDFParameterSpec.Extract extract = HKDFParameterSpec.ofExtract().addSalt(derivedSecret).addIKM(new byte[hashLength]).extractOnly();
            masterSecret = kdf.deriveKey("Generic", extract);

            byte[] tlsHash = transcriptHash.getHash();
            int keyLength = cipherSuite.keyLength();

            SecretKey clientTraffic = kdf.deriveKey("Generic", HKDF.expandLabel(masterSecret, "c ap traffic", tlsHash, hashLength));
            SecretKey clientEncryption = kdf.deriveKey(cipherSuite.algorithm(), HKDF.expandLabel(clientTraffic, QuicCrypto.encryptionLabel(quicVersion), keyLength));
            SecretKey clientInitialization = kdf.deriveKey("Generic", HKDF.expandLabel(clientTraffic, QuicCrypto.initializationVectorLabel(quicVersion), 12));
            SecretKey clientProtection = kdf.deriveKey(cipherSuite.algorithm(), HKDF.expandLabel(clientTraffic, QuicCrypto.headerProtectionLabel(quicVersion), keyLength));

            SecretKey serverTraffic = kdf.deriveKey("Generic", HKDF.expandLabel(masterSecret, "s ap traffic", tlsHash, hashLength));
            SecretKey serverEncryption = kdf.deriveKey(cipherSuite.algorithm(), HKDF.expandLabel(serverTraffic, QuicCrypto.encryptionLabel(quicVersion), keyLength));
            SecretKey serverInitialization = kdf.deriveKey("Generic", HKDF.expandLabel(serverTraffic, QuicCrypto.initializationVectorLabel(quicVersion), 12));
            SecretKey serverProtection = kdf.deriveKey(cipherSuite.algorithm(), HKDF.expandLabel(serverTraffic, QuicCrypto.headerProtectionLabel(quicVersion), keyLength));

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
                LOG.debug("generated {} keys on {}", EncryptionLevel.ONE_RTT, this);
        }
        catch (Throwable x)
        {
            throw TLSException.wrap(x);
        }
    }

    public SecretKey createResumptionMasterSecret(CipherSuite cipherSuite) throws Exception
    {
        // RFC-8446 #7.1.
        int hashLength = cipherSuite.hashLength();
        KDF kdf = KDF.getInstance("HKDF-SHA" + (hashLength * 8));
        byte[] tlsHash = transcriptHash.getHash();
        SecretKey resumptionMasterSecret = kdf.deriveKey("Generic", HKDF.expandLabel(masterSecret, "res master", tlsHash, hashLength));

        // The master secret is not needed anymore, as both the application
        // keys and the resumption master secret have been generated.
        KeyManager.destroy(masterSecret);
        masterSecret = null;

        // The resumption master secret does not need to be stored here:
        // it is either stored externally or in the session ticket.
        return resumptionMasterSecret;
    }

    public void discardKeys(EncryptionLevel encryptionLevel)
    {
        // RFC-9001 #4.9.
        KeyManager removed = keyManagers.remove(encryptionLevel);
        if (removed == null)
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("discarded {} keys on {}", encryptionLevel, this);
        removed.destroy();

        if (encryptionLevel == EncryptionLevel.HANDSHAKE)
        {
            KeyManager.destroy(handshakeSecret);
            handshakeSecret = null;
        }
    }

    @Override
    public void encrypt(EncryptionLevel encryptionLevel, long packetNumber, RetainableByteBuffer framesBuffer, RetainableByteBuffer.Mutable packetBuffer) throws Exception
    {
        KeyManager keyManager = keyManagers.get(encryptionLevel);
        if (keyManager == null)
            throw new IllegalStateException("no KeyManager for encryption level " + encryptionLevel);
        keyManager.encrypt(packetNumber, framesBuffer, packetBuffer);
    }

    @Override
    public RetainableByteBuffer decryptLongHeaderPacket(EncryptionLevel encryptionLevel, RetainableByteBuffer encrypted) throws Exception
    {
        // We might receive a Packet for an encryption level that has already been discarded.
        KeyManager keyManager = keyManagers.get(encryptionLevel);
        if (keyManager == null)
            return null;
        return keyManager.decryptLongHeaderPacket(encrypted);
    }

    @Override
    public RetainableByteBuffer decryptShortHeaderPacket(byte[] dstConnectionId, RetainableByteBuffer encrypted) throws Exception
    {
        KeyManager keyManager = keyManagers.get(EncryptionLevel.ONE_RTT);
        if (keyManager == null)
            return null;
        return keyManager.decryptShortHeaderPacket(dstConnectionId, encrypted);
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
        private final EncryptionLevel encryptionLevel;
        private final CipherSuite cipherSuite;
        private final ReadKeys readKeys;
        private final WriteKeys writeKeys;

        private KeyManager(EncryptionLevel encryptionLevel, CipherSuite cipherSuite)
        {
            this.encryptionLevel = encryptionLevel;
            this.cipherSuite = cipherSuite;
            this.readKeys = new ReadKeys(cipherSuite);
            this.writeKeys = new WriteKeys(cipherSuite);
        }

        public SecretKey getTrafficSecretKey(boolean input)
        {
            return input ? readKeys.traffic : writeKeys.traffic;
        }

        private void encrypt(long packetNumber, RetainableByteBuffer framesBuffer, RetainableByteBuffer.Mutable packetBuffer) throws Exception
        {
            writeKeys.encrypt(packetNumber, framesBuffer, packetBuffer);
        }

        private RetainableByteBuffer decryptLongHeaderPacket(RetainableByteBuffer encrypted) throws Exception
        {
            return readKeys.decryptLongHeaderPacket(encrypted);
        }

        private RetainableByteBuffer decryptShortHeaderPacket(byte[] dstConnectionId, RetainableByteBuffer encrypted) throws Exception
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
                if (LOG.isTraceEnabled())
                    LOG.trace("failed to destroy {}", secretKey, x);
            }
        }

        private void initPayloadCipher(Cipher cipher, int cipherMode, SecretKey secretKey, byte[] nonce) throws GeneralSecurityException
        {
//            AlgorithmParameterSpec params = switch (cipherSuite)
            AlgorithmParameterSpec params = switch (cipherSuite.code())
            {
                case TLS_AES_128_GCM_SHA256_CODE, TLS_AES_256_GCM_SHA384_CODE -> new GCMParameterSpec(cipherSuite.tagLength() * 8, nonce);
                case TLS_CHACHA20_POLY1305_SHA256_CODE -> new IvParameterSpec(nonce);
                default -> throw new UnsupportedOperationException("unsupported " + cipherSuite);
            };
            cipher.init(cipherMode, secretKey, params);
        }

        private byte[] newHeaderCipherMask(Cipher cipher, SecretKey secretKey, byte[] sample) throws GeneralSecurityException
        {
            return switch (cipherSuite.code())
            {
                case TLS_AES_128_GCM_SHA256_CODE, TLS_AES_256_GCM_SHA384_CODE ->
                {
                    // RFC-9001 #5.4.3.
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey);
                    yield cipher.doFinal(sample);
                }
                case TLS_CHACHA20_POLY1305_SHA256_CODE ->
                {
                    // RFC-9001 #5.4.4.
                    int blockCounter = ByteBuffer.wrap(sample, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    byte[] nonce = Arrays.copyOfRange(sample, 4, sample.length);
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey, new ChaCha20ParameterSpec(nonce, blockCounter));
                    yield cipher.doFinal(new byte[5]);
                }
                default -> throw new UnsupportedOperationException("unsupported " + cipherSuite);
            };
        }

        /// QUIC requires at least two generations of read keys,
        /// since a key update may arrive before a packet that
        /// was sent before the key update, encrypted with previous
        /// keys, but that was delayed by the network or retransmitted.
        ///
        /// @see WriteKeys
        private class ReadKeys
        {
            private final Cipher payloadCipher;
            private final Cipher headerCipher;
            private SecretKey traffic;
            private SecretKey encryption;
            private SecretKey initialization;
            private SecretKey protection;

            private ReadKeys(CipherSuite cipherSuite)
            {
                try
                {
                    this.payloadCipher = Cipher.getInstance(cipherSuite.payloadCipherName());
                    this.headerCipher = Cipher.getInstance(cipherSuite.headerCipherName());
                }
                catch (GeneralSecurityException x)
                {
                    throw TLSException.wrap(x);
                }
            }

            public void updateKeys(SecretKey initial, SecretKey encryption, SecretKey initialization, SecretKey protection)
            {
                this.traffic = initial;
                this.encryption = encryption;
                this.initialization = initialization;
                this.protection = protection;
            }

            private RetainableByteBuffer decryptLongHeaderPacket(RetainableByteBuffer encrypted) throws Exception
            {
                try (RetainableByteBuffer.Mutable decrypted = getByteBufferPool().acquire(encrypted.remaining(), true))
                {
                    // To remove header protection, we need a sample of the payload.
                    // RFC 9001 #5.4.2: compute the offset of the sample.
                    long position = encrypted.readPosition();
                    int firstByte = encrypted.getByteAsInt();

                    int type = (firstByte & 0b00110000) >>> 4;
                    QuicVersion version = QuicVersion.from(encrypted.getInt());
                    LongHeaderPacket.PacketType packetType = LongHeaderPacket.PacketType.from(type, version);
                    int dstConnectionIdLength = encrypted.getByteAsInt();
                    encrypted.consume(dstConnectionIdLength);
                    int srcConnectionIdLength = encrypted.getByteAsInt();
                    encrypted.consume(srcConnectionIdLength);
                    if (packetType == LongHeaderPacket.PacketType.INITIAL)
                    {
                        int tokenLength = VarLenInt.decodeInt(encrypted);
                        encrypted.consume(tokenLength);
                    }
                    // The payload length at this point also includes
                    // the packet number length and the AEAD tag.
                    int payloadLength = VarLenInt.decodeInt(encrypted);
                    long packetNumberPosition = encrypted.readPosition();
                    // Packet number length is at most 4 bytes.
                    encrypted.consume(4);
                    byte[] sample = new byte[16];
                    encrypted.get(sample);

                    // RFC 9001 #5.4.1: long header packets mask 4 bits.
                    byte[] mask = newHeaderCipherMask(headerCipher, protection, sample);
                    int form = firstByte ^ (mask[0] & 0b00001111);
                    int encodedPacketNumberLength = (form & 0x03) + 1;
                    payloadLength -= encodedPacketNumberLength;

                    long headerLength = packetNumberPosition + encodedPacketNumberLength - position;
                    try (RetainableByteBuffer encryptedHeaderSlice = encrypted.slice(position, headerLength))
                    {
                        decrypted.put(0, encryptedHeaderSlice);
                    }
                    // Replace the first byte with its unprotected value.
                    decrypted.put((byte)form);
                    // Header bytes up to the packet number are
                    // not protected, no need to unprotect them.
                    encrypted.readPosition(packetNumberPosition);
                    decrypted.writePosition(packetNumberPosition - position);
                    // Unprotect the packet number.
                    int encodedPacketNumber = 0;
                    for (int i = 0; i < encodedPacketNumberLength; ++i)
                    {
                        int unmasked = encrypted.getByteAsInt() ^ (mask[i + 1] & 0xFF);
                        decrypted.put((byte)unmasked);
                        encodedPacketNumber = (encodedPacketNumber << 8) | unmasked;
                    }

                    EncodedPacketNumber encoded = new EncodedPacketNumber(encodedPacketNumber, encodedPacketNumberLength);
                    long packetNumber = packetNumbers.decode(encryptionLevel, encoded);

                    byte[] nonce = nonce(initialization, packetNumber);
                    initPayloadCipher(payloadCipher, Cipher.DECRYPT_MODE, encryption, nonce);

                    // Supply AAD as the plaintext packet header.
                    decrypted.read(b ->
                    {
                        payloadCipher.updateAAD(b.slice());
                        return 0;
                    });

                    // The encrypted buffer must be at the start of the payload bytes.
                    assert encrypted.readPosition() == packetNumberPosition + encodedPacketNumberLength;
                    // The decrypted buffer must be at the start of the payload bytes.
                    assert decrypted.writePosition() == packetNumberPosition + encodedPacketNumberLength - position;

                    // Decrypt the payload.
                    try (RetainableByteBuffer encryptedPayloadSlice = encrypted.sliceAndConsume(payloadLength))
                    {
                        encryptedPayloadSlice.read(input ->
                        {
                            int r = input.remaining();
                            decrypted.write(output -> payloadCipher.doFinal(input, output));
                            return r - input.remaining();
                        });
                    }

                    decrypted.retain();
                    return decrypted;
                }
            }

            private RetainableByteBuffer decryptShortHeaderPacket(byte[] dstConnectionId, RetainableByteBuffer encrypted) throws Exception
            {
                try (RetainableByteBuffer.Mutable decrypted = getByteBufferPool().acquire(encrypted.remaining(), true))
                {
                    // To remove header protection, we need a sample of the payload.
                    // RFC-9001 #5.4.2: compute the offset of the sample.
                    long position = encrypted.readPosition();
                    int firstByte = encrypted.getByteAsInt();

                    // Skip form byte and destination connection ID bytes.
                    encrypted.consume(1 + dstConnectionId.length);
                    long packetNumberPosition = encrypted.readPosition();
                    // Packet number length is at most 4 bytes.
                    encrypted.consume(4);
                    byte[] sample = new byte[16];
                    encrypted.get(sample);

                    // RFC 9001 #5.4.1: long header packets mask 5 bits.
                    byte[] mask = newHeaderCipherMask(headerCipher, protection, sample);
                    int form = firstByte ^ (mask[0] & 0b00011111);
                    int encodedPacketNumberLength = (form & 0x03) + 1;

                    long headerLength = packetNumberPosition + encodedPacketNumberLength - position;
                    try (RetainableByteBuffer encryptedHeaderSlice = encrypted.slice(position, headerLength))
                    {
                        decrypted.put(0, encryptedHeaderSlice);
                    }
                    // Replace the first byte with its unprotected value.
                    decrypted.put((byte)form);
                    // Header bytes up to the packet number are
                    // not protected, no need to unprotect them.
                    encrypted.readPosition(packetNumberPosition);
                    decrypted.writePosition(packetNumberPosition - position);
                    // Unprotect the packet number.
                    int encodedPacketNumber = 0;
                    for (int i = 0; i < encodedPacketNumberLength; ++i)
                    {
                        int unmasked = encrypted.getByteAsInt() ^ (mask[i + 1] & 0xFF);
                        decrypted.put((byte)unmasked);
                        encodedPacketNumber = (encodedPacketNumber << 8) | unmasked;
                    }

                    EncodedPacketNumber encoded = new EncodedPacketNumber(encodedPacketNumber, encodedPacketNumberLength);
                    long packetNumber = packetNumbers.decode(encryptionLevel, encoded);

                    byte[] nonce = nonce(initialization, packetNumber);
                    initPayloadCipher(payloadCipher, Cipher.DECRYPT_MODE, encryption, nonce);

                    // Supply AAD as the plaintext packet header.
                    decrypted.read(b ->
                    {
                        payloadCipher.updateAAD(b.slice());
                        return 0;
                    });

                    // The encrypted buffer must be at the start of the payload bytes.
                    assert encrypted.readPosition() == packetNumberPosition + encodedPacketNumberLength;
                    // The decrypted buffer must be at the start of the payload bytes.
                    assert decrypted.writePosition() == packetNumberPosition + encodedPacketNumberLength - position;

                    // Decrypt the payload.
                    // Short header packets are always the only or the last in the datagram.
                    encrypted.read(input ->
                    {
                        int r = input.remaining();
                        decrypted.write(output -> payloadCipher.doFinal(input, output));
                        return r;
                    });

                    decrypted.retain();
                    return decrypted;
                }
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
            private final Cipher payloadCipher;
            private final Cipher headerCipher;
            private SecretKey traffic;
            private SecretKey encryption;
            private SecretKey initialization;
            private SecretKey protection;

            private WriteKeys(CipherSuite cipherSuite)
            {
                try
                {
                    this.payloadCipher = Cipher.getInstance(cipherSuite.payloadCipherName());
                    this.headerCipher = Cipher.getInstance(cipherSuite.headerCipherName());
                }
                catch (GeneralSecurityException x)
                {
                    throw TLSException.wrap(x);
                }
            }

            private void updateKeys(SecretKey initial, SecretKey encryption, SecretKey initialization, SecretKey protection)
            {
                this.traffic = initial;
                this.encryption = encryption;
                this.initialization = initialization;
                this.protection = protection;
            }

            private void encrypt(long packetNumber, RetainableByteBuffer framesBuffer, RetainableByteBuffer.Mutable packetBuffer) throws Exception
            {
                byte[] nonce = nonce(initialization, packetNumber);
                initPayloadCipher(payloadCipher, Cipher.ENCRYPT_MODE, encryption, nonce);

                // Supply AAD as the plaintext packet header.
                packetBuffer.read(b ->
                {
                    payloadCipher.updateAAD(b.slice());
                    return 0;
                });
                long headerLength = packetBuffer.remaining();

                // Encrypt the payload.
                // AEAD encryption produces additional tag bytes.
                framesBuffer.read(input ->
                {
                    int r = input.remaining();
                    packetBuffer.write(output -> payloadCipher.doFinal(input, output));
                    return r;
                });

                // RFC 9001 #5.4.2: header protection sample.
                int firstByte = packetBuffer.getByteAsInt(0);
                // The packet number length is encoded in the last 2 bits.
                int pktNumLen = (firstByte & 0x03) + 1;
                long sampleOffset = headerLength - pktNumLen + 4;
                byte[] sample = packetBuffer.getArray(sampleOffset, 16);

                byte[] mask = newHeaderCipherMask(headerCipher, protection, sample);

                // Long header packets mask 4 bits, short header packets mask 5 bits.
                int bits = (firstByte & 0x80) == 0x80 ? 0x0F : 0x1F;
                packetBuffer.put(0, (byte)(firstByte ^ (mask[0] & bits)));

                // Mask the packet number.
                long start = headerLength - pktNumLen;
                for (int i = 0; i < pktNumLen; ++i)
                {
                    int pktNumByte = packetBuffer.getByteAsInt(start + i);
                    packetBuffer.put(start + i, (byte)(pktNumByte ^ (mask[i + 1] & 0xFF)));
                }
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
