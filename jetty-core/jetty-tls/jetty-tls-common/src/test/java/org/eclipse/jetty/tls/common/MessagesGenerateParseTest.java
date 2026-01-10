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

package org.eclipse.jetty.tls.common;

import java.nio.ByteBuffer;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.CertificateMessage;
import org.eclipse.jetty.tls.CertificateRequestMessage;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.ClientHelloMessage;
import org.eclipse.jetty.tls.EncryptedExtensionsMessage;
import org.eclipse.jetty.tls.KeyShare;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.NamedGroup;
import org.eclipse.jetty.tls.ServerHelloMessage;
import org.eclipse.jetty.tls.SignatureAlgorithm;
import org.eclipse.jetty.tls.TLSVersion;
import org.eclipse.jetty.tls.common.generator.MessagesGenerator;
import org.eclipse.jetty.tls.common.parser.MessagesParser;
import org.eclipse.jetty.tls.ext.ALPNExtension;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.KeyShareExtension;
import org.eclipse.jetty.tls.ext.SignatureAlgorithmsExtension;
import org.eclipse.jetty.tls.ext.SupportedGroupsExtension;
import org.eclipse.jetty.tls.ext.SupportedVersionsExtension;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class MessagesGenerateParseTest
{
    @Test
    public void testClientHelloMessage() throws Exception
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        MessagesGenerator generator = new MessagesGenerator(byteBufferPool);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);

        byte[] random = new byte[32];
        ThreadLocalRandom.current().nextBytes(random);
        List<CipherSuite> cipherSuites = List.of(CipherSuite.TLS_AES_128_GCM_SHA256);
        List<NamedGroup> groups = List.of(NamedGroup.x25519);
        SupportedGroupsExtension supportedGroupsExtension = new SupportedGroupsExtension(groups);
        List<KeyShare> keyShares = new ArrayList<>();
        for (NamedGroup group : groups)
        {
            GroupKeyPair groupKeyPair = GroupKeyPair.from(group);
            keyShares.add(groupKeyPair.toKeyShare());
        }
        KeyShareExtension keyShareExtension = new KeyShareExtension(keyShares);
        SupportedVersionsExtension supportedVersionsExtension = new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3));
        SignatureAlgorithmsExtension signatureAlgorithmsExtension = new SignatureAlgorithmsExtension(List.of(SignatureAlgorithm.RSA_PKCS1_SHA256, SignatureAlgorithm.ECDSA_SECP256R1_SHA256));
        List<Extension> extensions = List.of(supportedGroupsExtension, keyShareExtension, supportedVersionsExtension, signatureAlgorithmsExtension);
        ClientHelloMessage generated = new ClientHelloMessage(random, cipherSuites, extensions);
        generator.generate(accumulator, generated);

        MessagesParser parser = new MessagesParser();
        Message message = parser.parse(accumulator);

        assertInstanceOf(ClientHelloMessage.class, message);
        ClientHelloMessage parsed = (ClientHelloMessage)message;
        assertArrayEquals(generated.random(), parsed.random());
        assertEquals(generated.cipherSuites(), parsed.cipherSuites());
        assertEquals(generated.extensions(), parsed.extensions());

        // Parse again one byte at a time.
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            message = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertInstanceOf(ClientHelloMessage.class, message);
        parsed = (ClientHelloMessage)message;
        assertArrayEquals(generated.random(), parsed.random());
        assertEquals(generated.cipherSuites(), parsed.cipherSuites());
        assertEquals(generated.extensions(), parsed.extensions());
    }

    @Test
    public void testServerHelloMessage() throws Exception
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        MessagesGenerator generator = new MessagesGenerator(byteBufferPool);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);
        byte[] random = new byte[32];
        ThreadLocalRandom.current().nextBytes(random);
        byte[] sessionId = new byte[13];
        ThreadLocalRandom.current().nextBytes(sessionId);
        ServerHelloMessage generated = new ServerHelloMessage(random, sessionId, CipherSuite.TLS_AES_128_GCM_SHA256, List.of(new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3))));
        generator.generate(accumulator, generated);

        MessagesParser parser = new MessagesParser();
        Message message = parser.parse(accumulator);

        assertInstanceOf(ServerHelloMessage.class, message);
        ServerHelloMessage parsed = (ServerHelloMessage)message;
        assertArrayEquals(generated.random(), parsed.random());
        assertEquals(generated.cipherSuite(), parsed.cipherSuite());
        assertEquals(generated.extensions(), parsed.extensions());

        // Parse again one byte at a time.
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            message = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertInstanceOf(ServerHelloMessage.class, message);
        parsed = (ServerHelloMessage)message;
        assertArrayEquals(generated.random(), parsed.random());
        assertEquals(generated.cipherSuite(), parsed.cipherSuite());
        assertEquals(generated.extensions(), parsed.extensions());
    }

    @Test
    public void testEncryptedExtensionsMessage() throws Exception
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        MessagesGenerator generator = new MessagesGenerator(byteBufferPool);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);
        EncryptedExtensionsMessage generated = new EncryptedExtensionsMessage(List.of(new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3))));
        generator.generate(accumulator, generated);

        MessagesParser parser = new MessagesParser();
        Message message = parser.parse(accumulator);

        assertInstanceOf(EncryptedExtensionsMessage.class, message);
        EncryptedExtensionsMessage parsed = (EncryptedExtensionsMessage)message;
        assertEquals(generated.extensions(), parsed.extensions());

        // Parse again one byte at a time.
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            message = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertInstanceOf(EncryptedExtensionsMessage.class, message);
        parsed = (EncryptedExtensionsMessage)message;
        assertEquals(generated.extensions(), parsed.extensions());
    }

    @Test
    public void testCertificateRequestMessage() throws Exception
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        MessagesGenerator generator = new MessagesGenerator(byteBufferPool);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);
        byte[] context = new byte[13];
        ThreadLocalRandom.current().nextBytes(context);
        CertificateRequestMessage generated = new CertificateRequestMessage(context, List.of(new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3))));
        generator.generate(accumulator, generated);

        MessagesParser parser = new MessagesParser();
        Message message = parser.parse(accumulator);

        assertInstanceOf(CertificateRequestMessage.class, message);
        CertificateRequestMessage parsed = (CertificateRequestMessage)message;
        assertArrayEquals(generated.context(), parsed.context());
        assertEquals(generated.extensions(), parsed.extensions());

        // Parse again one byte at a time.
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            message = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertInstanceOf(CertificateRequestMessage.class, message);
        parsed = (CertificateRequestMessage)message;
        assertArrayEquals(generated.context(), parsed.context());
        assertEquals(generated.extensions(), parsed.extensions());
    }

    @Test
    public void testCertificateMessage() throws Exception
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(MavenPaths.findTestResourceFile("server_keystore.p12"));
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.start();

        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        MessagesGenerator generator = new MessagesGenerator(byteBufferPool);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);
        byte[] context = new byte[13];
        ThreadLocalRandom.current().nextBytes(context);
        List<X509Certificate> certificates = Arrays.stream(sslContextFactory.getKeyStore().getCertificateChain("mykey"))
            .map(X509Certificate.class::cast)
            .toList();
        // Only the StatusRequest extension may be present in a CertificateMessage.Entry,
        // but here we just want to test that the generation and parsing are correct.
        List<Extension> extensions = List.of(new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3)), new ALPNExtension(List.of("http/1.1")));
        List<CertificateMessage.Entry> entries = certificates.stream()
            .map(certificate -> new CertificateMessage.Entry(certificate, extensions))
            .toList();
        CertificateMessage generated = new CertificateMessage(context, entries);
        generator.generate(accumulator, generated);

        MessagesParser parser = new MessagesParser();
        Message message = parser.parse(accumulator);

        assertInstanceOf(CertificateMessage.class, message);
        CertificateMessage parsed = (CertificateMessage)message;
        assertArrayEquals(generated.context(), parsed.context());
        assertEquals(generated.entries(), parsed.entries());

        // Parse again one byte at a time.
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            message = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertInstanceOf(CertificateMessage.class, message);
        parsed = (CertificateMessage)message;
        assertArrayEquals(generated.context(), parsed.context());
        assertEquals(generated.entries(), parsed.entries());
    }
}

// 0b
// 000738
// 0d ctx_len
// 80561aEc3a57088eA950B13d85
// 000727 entries_len
// 00038f cert_len
// 3082038b30820273A0030201020214538a76C55aFeA7E280Eb1b37Fb683c7cEdFcF912300d06092a864886F70d01010b05003059310b3009060355040613025553310b3009060355040813024e45310e300c060355040713054f6d6168613110300e060355040a130757656274696465310e300c060355040b13054a65747479310b30090603550403130263613020170d3236303130343136313531385a180f32313235313231313136313531385a3060310b3009060355040613025553310b3009060355040813024e45310e300c060355040713054f6d6168613110300e060355040a130757656274696465310e300c060355040b13054a6574747931123010060355040313096c6f63616c686f737430820122300d06092a864886F70d01010105000382010f003082010a0282010100Bd43B545Af12F232Cf10820fDb1489Eb3958F938Da66BcFd997e152aE7A8C31c2eC504737513AfFbF734372c802a1e278fD143B8D12bA18a90EdAaDfF1951b3c1878D0B5A10e15D31a83E6F82581B085D16c528733EdE67c4eDd0b574bC0FbA09bB434691bE1033e5fCd92Ed643060733d9d8e6dA91a89C8211d546b0d4256E866340e73D17cDf3bE22265905657D768Ac29F7EcAe506fE25f441d41F00fEd055c84E2Da52D167FeEcF0B61073B4B8D45503C7E62982793aEd1c0f6570CbF8Ff19E8769d0f642fCd67A292997eF849846bCf1eD566Bd6a9b760b86Ee0f8a1aC37897A8F90eB52b7836C1990cC30c96438aC9B1F6B9E8692d0f26161bA29bA96d0203010001A3423040301d0603551d0e0416041496E58c46A5DcF3EcF0Ac3a982aFc9f46EcAf7fAe301f0603551d23041830168014C0C944B0694cBeC562D8C3D9625bD169C25cDbDc300d06092a864886F70d01010b050003820101005fC5E190748590CeAcF1C712A06fB677011dA37fF0423e25819821554c16Db571cB8D2F152808a96AcCeBb3aB445084fEc9b22Db2b15Ee1a9c715fB78b530cEf08B2Ce6c500d7247A304AbEe7254F4727a25CeEdE4Fd6e91973f5c94C938CcD325D93c8f9bF81eF4A4Ec6278C61e3f4fAe76AcFf2d05Ac428fF8Fa6b4dE6F3C462515a406516Ec14781c9cC38eC3A985333d3eBcC7DeC03aE46c3bE6070184B1De6827C01142Bc3dAa75F9Ce805a923a73Fc5f7cD50c90234dE523F956Ee16FcA23188CeFf6bF616Bf32Ae19A2E738740f02E5501c0eB10b60D6C5D88c5cE6840600471dFb5e68Be2aCeD36263516dC1Bc4e5839C118Da83Ee5c9c18F3Aa1aD8
// 0016 exts_len
// 002b0003020304
// 0010000b000908687474702f312e31
// 00036a cert_len
// 308203663082024eA003020102020900904f60D06072Ea10300d06092a864886F70d01010c05003059310b3009060355040613025553310b3009060355040813024e45310e300c060355040713054f6d6168613110300e060355040a130757656274696465310e300c060355040b13054a65747479310b30090603550403130263613020170d3236303130343136313235385a180f32313235313231313136313235385a3059310b3009060355040613025553310b3009060355040813024e45310e300c060355040713054f6d6168613110300e060355040a130757656274696465310e300c060355040b13054a65747479310b300906035504031302636130820122300d06092a864886F70d01010105000382010f003082010a0282010100D00cE3A8Ea11D4Ca610e613f62De28D3D5780eD2300b390b6544B402Ae357bD186F60f9f65C592005cFd443c5e4eBd61Db293e13EdD0E247BdE3D22bF8B0CeAa6aE82277A23d27382eF897C18961595e14338eC3Db2f0dF12b4cB523Fd1d9a7767Ab2225BeF1237a242fDa50C97d6cE83229Fb038299F1Cc6a5e3e150e3673CcAc7b1fB8CcF95f5789Ce69EdEdEe9913FfE31cE224073dA1Eb23D258Af174188836fC36eAf9441D01cEb4e245b86EfBe38C6780f60081a0eBc0b10C286EeD0C9785227A2686b3d0f0914719e479aF11a2866D47a7d88F555B5A32899B9C82522992eC5DaA31b230795B47e903aE01f52A911031aDb12Bc2fF0Ee32D1B9E3479b0203010001A32f302d301d0603551d0e04160414C0C944B0694cBeC562D8C3D9625bD169C25cDbDc300c0603551d13040530030101Ff300d06092a864886F70d01010c050003820101002aA93726Ee3e4512A686282aFcBbD1A53a026b06CbF8D38e711513051f07Bb9e6a2c2a71Dc462cCeAa0c3a6b51956a9dA79e471d7eF407762bA638DaFcDaBcDfD3A261998cD788E09e050a71F915341000C99f207bA739B18e0229A99570Bb94Db8dDbEd6d0dC906A117B6B48a89824b1c01B26b5e846988701dE36b796eE99033E9A0EcA1F677Ea18F543781cB4A3653b286aC1Af6c481fFd2c59C34b0a0cD1914cFf751dA7F4C6C2Cd9dF9D357DcF23050EbBaAaB39eEb5634FcD7A3940e0b69DaDdBeAeD1C19983346b8e16AbD74b12BfD2B279FcE9A15eB418C18131A39170A73937Af725cB6388059147f772c736eB133E86a29C1F5B2087a0887E5A955
// 0016 exts_len
// 002b0003020304
// 0010000b000908687474702f312e31