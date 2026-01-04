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

package org.eclipse.jetty.quic.common.internal;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.KDF;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.HKDFParameterSpec;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Version;
import org.eclipse.jetty.quic.common.internal.crypto.HKDF;
import org.eclipse.jetty.quic.common.internal.packets.EncodedPacketNumber;
import org.eclipse.jetty.quic.common.internal.packets.PacketNumbers;
import org.eclipse.jetty.quic.util.VarLenInt;

public class TLSEngine implements Encrypter, Decrypter
{
    private final Map<EncryptionLevel, KeyManager> keyManagers = new ConcurrentHashMap<>();
    private final ByteBufferPool byteBufferPool;
    private final PacketNumbers packetNumbers;
    private final boolean clientMode;

    public TLSEngine(ByteBufferPool byteBufferPool, PacketNumbers packetNumbers, boolean clientMode)
    {
        this.byteBufferPool = byteBufferPool;
        this.packetNumbers = packetNumbers;
        this.clientMode = clientMode;
    }

    public boolean isClientMode()
    {
        return clientMode;
    }

    @Override
    public void allocateInitialKeys(Version version, byte[] input) throws Exception
    {
        KeyManager keyManager = keyManagers.computeIfAbsent(EncryptionLevel.INITIAL, KeyManager::new);
        keyManager.allocateInitialKeys(version, input);
    }

    @Override
    public PacketBuffers encrypt(EncryptionLevel encryptionLevel, long packetNumber, ByteBuffer header, ByteBuffer payload) throws Exception
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
    public PacketBuffers decryptShortHeaderPacket(byte[] dstConnectionId, ByteBuffer encrypted) throws Exception
    {
        KeyManager keyManager = keyManagers.get(EncryptionLevel.ONE_RTT);
        if (keyManager == null)
            throw new IllegalStateException("no KeyManager for encryption level " + EncryptionLevel.ONE_RTT);
        return keyManager.decryptShortHeaderPacket(dstConnectionId, encrypted);
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

        private void allocateInitialKeys(Version version, byte[] input) throws Exception
        {
            HKDFParameterSpec.Extract spec = HKDFParameterSpec.ofExtract().addSalt(version.initialSalt()).addIKM(input).extractOnly();
            KDF kdf = KDF.getInstance("HKDF-SHA256");
            SecretKey prk = kdf.deriveKey("InitialPseudoRandomKey", spec);

            SecretKey clientInitial = kdf.deriveKey("InitialSecretKey", HKDF.expandLabel(prk, "client in", 32));
            SecretKey clientEncryption = kdf.deriveKey("AES", HKDF.expandLabel(clientInitial, version.encryptionLabel(), 16));
            SecretKey clientInitialization = kdf.deriveKey("AES", HKDF.expandLabel(clientInitial, version.initializationVectorLabel(), 12));
            SecretKey clientProtection = kdf.deriveKey("AES", HKDF.expandLabel(clientInitial, version.headerProtectionLabel(), 16));

            SecretKey serverInitial = kdf.deriveKey("InitialSecretKey", HKDF.expandLabel(prk, "server in", 32));
            SecretKey serverEncryption = kdf.deriveKey("AES", HKDF.expandLabel(serverInitial, version.encryptionLabel(), 16));
            SecretKey serverInitialization = kdf.deriveKey("AES", HKDF.expandLabel(serverInitial, version.initializationVectorLabel(), 12));
            SecretKey serverProtection = kdf.deriveKey("AES", HKDF.expandLabel(serverInitial, version.headerProtectionLabel(), 16));

            if (isClientMode())
            {
                readKeys.updateKeys(serverInitial, serverEncryption, serverInitialization, serverProtection);
                writeKeys.updateKeys(clientInitial, clientEncryption, clientInitialization, clientProtection);
            }
            else
            {
                readKeys.updateKeys(clientInitial, clientEncryption, clientInitialization, clientProtection);
                writeKeys.updateKeys(serverInitial, serverEncryption, serverInitialization, serverProtection);
            }
        }

        private PacketBuffers encrypt(long packetNumber, ByteBuffer header, ByteBuffer payload) throws Exception
        {
            return writeKeys.encrypt(packetNumber, header, payload);
        }

        private PacketBuffers decryptLongHeaderPacket(RetainableByteBuffer encrypted) throws Exception
        {
            return readKeys.decryptLongHeaderPacket(encrypted);
        }

        public PacketBuffers decryptShortHeaderPacket(byte[] dstConnectionId, ByteBuffer encrypted) throws Exception
        {
            return readKeys.decryptShortHeaderPacket(dstConnectionId, encrypted);
        }

        private byte[] nonce(SecretKey initialization, long packetNumber)
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

        /// QUIC requires at least two generations of read keys,
        /// since a key update may arrive before a packet that
        /// was sent before the key update, encrypted with previous
        /// keys, but that was delayed by the network or retransmitted.
        ///
        /// @see WriteKeys
        private class ReadKeys
        {
            private SecretKey initial;
            private SecretKey encryption;
            private SecretKey initialization;
            private SecretKey protection;

            public void updateKeys(SecretKey initial, SecretKey encryption, SecretKey initialization, SecretKey protection)
            {
                this.initial = initial;
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

                // Skip form byte + 4 bytes for the version.
                byteBuffer.position(position + 1 + 4);
                int dstConnectionIdLength = byteBuffer.get();
                byteBuffer.position(byteBuffer.position() + dstConnectionIdLength);
                int srcConnectionIdLength = byteBuffer.get();
                byteBuffer.position(byteBuffer.position() + srcConnectionIdLength);
                if (encryptionLevel == EncryptionLevel.INITIAL)
                {
                    int tokenLength = VarLenInt.decodeInt(byteBuffer);
                    byteBuffer.position(byteBuffer.position() + tokenLength);
                }
                // The payload length at this point also includes
                // the packet number length and the 16-bytes AEAD tag.
                int payloadLength = VarLenInt.decodeInt(byteBuffer);
                int packetNumberOffset = byteBuffer.position();
                // Packet number length is at most 4 bytes.
                byteBuffer.position(packetNumberOffset + 4);
                byte[] sample = new byte[16];
                byteBuffer.get(sample);

                Cipher ecbCipher = Cipher.getInstance("AES/ECB/NoPadding");
                ecbCipher.init(Cipher.ENCRYPT_MODE, protection);
                byte[] mask = ecbCipher.doFinal(sample);

                int firstByte = byteBuffer.get(position) & 0xFF;
                // Long header packets mask 4 bits.
                byte form = (byte)(firstByte ^ (mask[0] & 0xF));
                int encodedPacketNumberLength = (form & 0x03) + 1;
                payloadLength -= encodedPacketNumberLength;

                // Prepare the decrypted header buffer.
                int headerLength = packetNumberOffset + encodedPacketNumberLength - position;
                RetainableByteBuffer decryptedHeaderBuffer = byteBufferPool.acquire(headerLength, true);
                ByteBuffer decryptedHeader = decryptedHeaderBuffer.getByteBuffer();
                decryptedHeader.clear();
                decryptedHeader.put(byteBuffer.slice(position, headerLength)).flip();
                decryptedHeader.put(form);

                // Unmask the packet number.
                byteBuffer.position(packetNumberOffset);
                decryptedHeader.position(packetNumberOffset);
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
                ByteBuffer encryptedPayload = byteBuffer.slice(byteBuffer.position(), payloadLength);
                RetainableByteBuffer decryptedPayloadBuffer = byteBufferPool.acquire(payloadLength, true);
                ByteBuffer decryptedPayload = decryptedPayloadBuffer.getByteBuffer();
                decryptedPayload.clear();
                gcmCipher.doFinal(encryptedPayload, decryptedPayload);
                decryptedPayload.flip();

                return new PacketBuffers(decryptedHeaderBuffer, decryptedPayloadBuffer);
            }

            private PacketBuffers decryptShortHeaderPacket(byte[] dstConnectionId, ByteBuffer byteBuffer) throws Exception
            {
                // To remove header protection, we need a sample of the payload.
                // RFC 9001, 5.4.2: compute the offset of the sample.
                int position = byteBuffer.position();

                // Skip form byte and destination connection ID bytes.
                byteBuffer.position(position + 1 + dstConnectionId.length);
                int packetNumberOffset = byteBuffer.position();
                // Packet number length is at most 4 bytes.
                byteBuffer.position(packetNumberOffset + 4);
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
                int headerLength = packetNumberOffset + encodedPacketNumberLength - position;
                RetainableByteBuffer decryptedHeaderBuffer = byteBufferPool.acquire(headerLength, true);
                ByteBuffer decryptedHeader = decryptedHeaderBuffer.getByteBuffer();
                decryptedHeader.clear();
                decryptedHeader.put(byteBuffer.slice(position, headerLength)).flip();
                decryptedHeader.put(form);

                // Unmask the packet number.
                byteBuffer.position(packetNumberOffset);
                decryptedHeader.position(packetNumberOffset);
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
                byteBuffer.position(packetNumberOffset + encodedPacketNumberLength);
                // Short header packets are always the only or the last in the datagram.
                int payloadLength = byteBuffer.remaining();
                ByteBuffer encryptedPayload = byteBuffer.slice(byteBuffer.position(), payloadLength);
                RetainableByteBuffer decryptedPayloadBuffer = byteBufferPool.acquire(payloadLength, true);
                ByteBuffer decryptedPayload = decryptedPayloadBuffer.getByteBuffer();
                decryptedPayload.clear();
                gcmCipher.doFinal(encryptedPayload, decryptedPayload);
                decryptedPayload.flip();

                return new PacketBuffers(decryptedHeaderBuffer, decryptedPayloadBuffer);
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
            private SecretKey initial;
            private SecretKey encryption;
            private SecretKey initialization;
            private SecretKey protection;

            private void updateKeys(SecretKey initial, SecretKey encryption, SecretKey initialization, SecretKey protection)
            {
                this.initial = initial;
                this.encryption = encryption;
                this.initialization = initialization;
                this.protection = protection;
            }

            private PacketBuffers encrypt(long packetNumber, ByteBuffer header, ByteBuffer payload) throws Exception
            {
                byte[] nonce = nonce(initialization, packetNumber);

                Cipher gcmCipher = Cipher.getInstance("AES/GCM/NoPadding");
                gcmCipher.init(Cipher.ENCRYPT_MODE, encryption, new GCMParameterSpec(128, nonce));

                // Supply AAD as the plaintext packet header.
                gcmCipher.updateAAD(header);
                header.flip();
                // Encrypt the payload.
                // AEAD encryption produces 16 additional bytes.
                RetainableByteBuffer.Mutable encryptedPayloadBuffer = byteBufferPool.acquire(payload.remaining() + 16, true);
                ByteBuffer encryptedPayload = encryptedPayloadBuffer.getByteBuffer();
                encryptedPayload.clear();
                gcmCipher.doFinal(payload, encryptedPayload);
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

                int position = header.position();
                RetainableByteBuffer.Mutable encryptedHeaderBuffer = byteBufferPool.acquire(header.remaining(), true);
                ByteBuffer encryptedHeader = encryptedHeaderBuffer.getByteBuffer();
                encryptedHeader.clear();
                encryptedHeader.put(header).flip();
                // Long header packets mask 4 bits, short header packets mask 5 bits.
                int firstByte = header.get(position) & 0xFF;
                int bits = (firstByte & 0x80) == 0x80 ? 0x0F : 0x1F;
                encryptedHeader.put(0, (byte)(firstByte ^ (mask[0] & bits)));

                // Mask the packet number.
                int start = encryptedHeader.limit() - pktNumLen;
                for (int i = 0; i < pktNumLen; ++i)
                {
                    int pktNumByte = header.get(start + i) & 0xFF;
                    encryptedHeader.put(start + i, (byte)(pktNumByte ^ mask[i + 1]));
                }

                return new PacketBuffers(encryptedHeaderBuffer, encryptedPayloadBuffer);
            }
        }
    }
}
