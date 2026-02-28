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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class MessagesGenerateParseTest
{
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testClientHelloMessage(boolean client) throws Exception
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        MessagesGenerator generator = new MessagesGenerator(byteBufferPool, client);
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
        SignatureAlgorithmsExtension signatureAlgorithmsExtension = new SignatureAlgorithmsExtension(List.of(SignatureAlgorithm.RSA_PSS_RSAE_SHA256, SignatureAlgorithm.ECDSA_SECP256R1_SHA256));
        List<Extension> extensions = List.of(supportedGroupsExtension, keyShareExtension, supportedVersionsExtension, signatureAlgorithmsExtension);
        ClientHelloMessage generated = new ClientHelloMessage(random, cipherSuites, extensions);
        generator.generate(accumulator, generated);

        MessagesParser parser = new MessagesParser(!client);
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testServerHelloMessage(boolean client) throws Exception
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        MessagesGenerator generator = new MessagesGenerator(byteBufferPool, client);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);
        byte[] random = new byte[32];
        ThreadLocalRandom.current().nextBytes(random);
        byte[] sessionId = new byte[13];
        ThreadLocalRandom.current().nextBytes(sessionId);
        ServerHelloMessage generated = new ServerHelloMessage(random, sessionId, CipherSuite.TLS_AES_128_GCM_SHA256, List.of(new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3))));
        generator.generate(accumulator, generated);

        MessagesParser parser = new MessagesParser(!client);
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testEncryptedExtensionsMessage(boolean client) throws Exception
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        MessagesGenerator generator = new MessagesGenerator(byteBufferPool, client);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);
        EncryptedExtensionsMessage generated = new EncryptedExtensionsMessage(List.of(new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3))));
        generator.generate(accumulator, generated);

        MessagesParser parser = new MessagesParser(!client);
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testCertificateRequestMessage(boolean client) throws Exception
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        MessagesGenerator generator = new MessagesGenerator(byteBufferPool, client);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);
        byte[] context = new byte[13];
        ThreadLocalRandom.current().nextBytes(context);
        CertificateRequestMessage generated = new CertificateRequestMessage(context, List.of(new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3))));
        generator.generate(accumulator, generated);

        MessagesParser parser = new MessagesParser(!client);
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testCertificateMessage(boolean client) throws Exception
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(MavenPaths.findTestResourceFile("server_keystore.p12"));
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.start();

        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        MessagesGenerator generator = new MessagesGenerator(byteBufferPool, client);
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

        MessagesParser parser = new MessagesParser(!client);
        Message message = parser.parse(accumulator);

        assertInstanceOf(CertificateMessage.class, message);
        CertificateMessage parsed = (CertificateMessage)message;
        assertArrayEquals(generated.requestContext(), parsed.requestContext());
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
        assertArrayEquals(generated.requestContext(), parsed.requestContext());
        assertEquals(generated.entries(), parsed.entries());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testCertificateVerifyMessage(boolean client) throws Exception
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        MessagesGenerator generator = new MessagesGenerator(byteBufferPool, client);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);
        byte[] signature = new byte[13];
        ThreadLocalRandom.current().nextBytes(signature);
        CertificateVerifyMessage generated = new CertificateVerifyMessage(SignatureAlgorithm.RSA_PSS_RSAE_SHA256, signature);
        generator.generate(accumulator, generated);

        MessagesParser parser = new MessagesParser(!client);
        Message message = parser.parse(accumulator);

        assertInstanceOf(CertificateVerifyMessage.class, message);
        CertificateVerifyMessage parsed = (CertificateVerifyMessage)message;
        assertEquals(generated.signatureAlgorithm(), parsed.signatureAlgorithm());
        assertArrayEquals(generated.signature(), parsed.signature());

        // Parse again one byte at a time.
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            message = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertInstanceOf(CertificateVerifyMessage.class, message);
        parsed = (CertificateVerifyMessage)message;
        assertEquals(generated.signatureAlgorithm(), parsed.signatureAlgorithm());
        assertArrayEquals(generated.signature(), parsed.signature());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testFinishedMessage(boolean client) throws Exception
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        MessagesGenerator generator = new MessagesGenerator(byteBufferPool, client);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);
        byte[] verifyData = new byte[13];
        ThreadLocalRandom.current().nextBytes(verifyData);
        FinishedMessage generated = new FinishedMessage(verifyData);
        generator.generate(accumulator, generated);

        MessagesParser parser = new MessagesParser(!client);
        Message message = parser.parse(accumulator);

        assertInstanceOf(FinishedMessage.class, message);
        FinishedMessage parsed = (FinishedMessage)message;
        assertArrayEquals(generated.verifyData(), parsed.verifyData());

        // Parse again one byte at a time.
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            message = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertInstanceOf(FinishedMessage.class, message);
        parsed = (FinishedMessage)message;
        assertArrayEquals(generated.verifyData(), parsed.verifyData());
    }
}
