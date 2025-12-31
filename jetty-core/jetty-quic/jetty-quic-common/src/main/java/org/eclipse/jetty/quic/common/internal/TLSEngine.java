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

import org.eclipse.jetty.quic.api.Version;
import org.eclipse.jetty.quic.common.internal.crypto.HKDF;
import org.eclipse.jetty.quic.common.internal.packets.PacketNumber;

public class TLSEngine implements Encrypter
{
    private final Map<EncryptionLevel, KeyManager> keyManagers = new ConcurrentHashMap<>();
    private final boolean clientMode;

    public TLSEngine(boolean clientMode)
    {
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
    public void encrypt(EncryptionLevel encryptionLevel, PacketNumber packetNumber, ByteBuffer header, ByteBuffer headerOutput, ByteBuffer payload, ByteBuffer payloadOutput) throws Exception
    {
        KeyManager keyManager = keyManagers.get(encryptionLevel);
        if (keyManager == null)
            throw new IllegalStateException("no KeyManager for encryption level " + encryptionLevel);
        keyManager.encrypt(packetNumber, header, headerOutput, payload, payloadOutput);
    }

    /// A manager for [read][ReadKeys] and [write][WriteKeys] keys required by QUIC.
    ///
    /// There is one key manager for each [EncryptionLevel].
    private class KeyManager
    {
        private final ReadKeys readKeys = new ReadKeys();
        private final WriteKeys writeKeys = new WriteKeys();
        private final EncryptionLevel level;

        private KeyManager(EncryptionLevel level)
        {
            this.level = level;
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

        public void encrypt(PacketNumber packetNumber, ByteBuffer header, ByteBuffer headerOutput, ByteBuffer payload, ByteBuffer payloadOutput) throws Exception
        {
            writeKeys.encrypt(packetNumber, header, headerOutput, payload, payloadOutput);
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

            public void updateKeys(SecretKey initial, SecretKey encryption, SecretKey initialization, SecretKey protection)
            {
                this.initial = initial;
                this.encryption = encryption;
                this.initialization = initialization;
                this.protection = protection;
            }

            public void encrypt(PacketNumber packetNumber, ByteBuffer header, ByteBuffer headerOutput, ByteBuffer payload, ByteBuffer payloadOutput) throws Exception
            {
                // RFC 9001, 5.3.
                // Nonce is IV ^ packetNumber.
                byte[] nonce = initialization.getEncoded();
                long pktNum = packetNumber.packetNumber();
                for (int i = 0; i < 4; ++i)
                {
                    nonce[nonce.length - 1 - i] ^= (byte)(pktNum & 0xFF);
                    pktNum = pktNum >>> 8;
                }

                Cipher gcmCipher = Cipher.getInstance("AES/GCM/NoPadding");
                gcmCipher.init(Cipher.ENCRYPT_MODE, encryption, new GCMParameterSpec(128, nonce));

                // Supply AAD as the plaintext packet header.
                gcmCipher.updateAAD(header);
                header.flip();
                // Encrypt payload.
                gcmCipher.doFinal(payload, payloadOutput);
                payloadOutput.flip();

                // RFC 9001, 5.4.2: header protection sample.
                int pktNumLen = packetNumber.encodedPacketNumberLength();
                int sampleOffset = 4 - pktNumLen;
                byte[] sample = new byte[16];
                payloadOutput.get(sampleOffset, sample);

                Cipher ecbCipher = Cipher.getInstance("AES/ECB/NoPadding");
                ecbCipher.init(Cipher.ENCRYPT_MODE, protection);
                byte[] mask = ecbCipher.doFinal(sample);

                headerOutput.put(header).flip();
                // Long header packets mask 4 bits, short header packets mask 5 bits.
                int bits = (header.get(0) & 0x80) == 0x80 ? 0x0F : 0x1F;
                headerOutput.put(0, (byte)(header.get(0) ^ (mask[0] & bits)));
                // Mask the packet number.
                int start = headerOutput.limit() - pktNumLen;
                for (int i = 0; i < pktNumLen; ++i)
                {
                    byte pktNumByte = header.get(start + i);
                    headerOutput.put(start + i, (byte)(pktNumByte ^ mask[i + 1]));
                }
            }
        }
    }
}
