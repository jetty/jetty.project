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

package org.eclipse.jetty.util.ssl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItemInArray;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SslContextFactoryTest
{
    @Test
    public void testSLOTH() throws Exception
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        cf.setKeyStorePassword("storepwd");

        cf.start();

        // cf.dump(System.out, "");
        List<SslSelectionDump> dumps = cf.selectionDump();

        Optional<SslSelectionDump> cipherSuiteDumpOpt = dumps.stream()
            .filter((dump) -> dump.type.contains("Cipher Suite"))
            .findFirst();

        assertTrue(cipherSuiteDumpOpt.isPresent(), "Cipher Suite dump section should exist");

        SslSelectionDump cipherDump = cipherSuiteDumpOpt.get();

        for (String enabledCipher : cipherDump.enabled)
        {
            assertThat("Enabled Cipher Suite", enabledCipher, not(matchesRegex(".*_RSA_.*(SHA1|MD5|SHA)")));
        }
    }

    @Test
    public void testDumpExcludedProtocols() throws Exception
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        cf.setExcludeProtocols("TLSv1\\.?[01]?");
        cf.start();

        // Confirm behavior in engine
        assertThat(cf.newSSLEngine().getEnabledProtocols(), not(hasItemInArray("TLSv1.1")));
        assertThat(cf.newSSLEngine().getEnabledProtocols(), not(hasItemInArray("TLSv1")));

        // Confirm output in dump
        List<SslSelectionDump> dumps = cf.selectionDump();

        Optional<SslSelectionDump> protocolDumpOpt = dumps.stream()
            .filter((dump) -> dump.type.contains("Protocol"))
            .findFirst();

        assertTrue(protocolDumpOpt.isPresent(), "Protocol dump section should exist");

        SslSelectionDump protocolDump = protocolDumpOpt.get();

        long countTls11Enabled = protocolDump.enabled.stream().filter((t) -> t.contains("TLSv1.1")).count();
        long countTls11Disabled = protocolDump.disabled.stream().filter((t) -> t.contains("TLSv1.1")).count();

        assertThat("Enabled Protocols TLSv1.1 count", countTls11Enabled, is(0L));
        assertThat("Disabled Protocols TLSv1.1 count", countTls11Disabled, is(1L));

        // Uncomment to show dump in console.
        // cf.dump(System.out, "");
    }

    @Test
    public void testDumpIncludeTlsRsa() throws Exception
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        cf.setKeyStorePassword("storepwd");
        cf.setIncludeCipherSuites("TLS_RSA_.*");
        cf.setExcludeCipherSuites("BOGUS"); // just to not exclude anything

        cf.start();

        // cf.dump(System.out, "");
        List<SslSelectionDump> dumps = cf.selectionDump();

        SSLEngine ssl = SSLContext.getDefault().createSSLEngine();

        List<String> tlsRsaSuites = Stream.of(ssl.getSupportedCipherSuites())
            .filter((suite) -> suite.startsWith("TLS_RSA_"))
            .collect(Collectors.toList());

        List<String> selectedSuites = Arrays.asList(cf.getSelectedCipherSuites());

        Optional<SslSelectionDump> cipherSuiteDumpOpt = dumps.stream()
            .filter((dump) -> dump.type.contains("Cipher Suite"))
            .findFirst();

        assertTrue(cipherSuiteDumpOpt.isPresent(), "Cipher Suite dump section should exist");

        SslSelectionDump cipherDump = cipherSuiteDumpOpt.get();

        assertThat("Dump Enabled List size is equal to selected list size", cipherDump.enabled.size(), is(selectedSuites.size()));

        for (String expectedCipherSuite : tlsRsaSuites)
        {
            assertThat("Selected Cipher Suites", selectedSuites, hasItem(expectedCipherSuite));
            assertThat("Dump Enabled Cipher Suites", cipherDump.enabled, hasItem(expectedCipherSuite));
        }
    }

    @Test
    public void testNoTsFileKs() throws Exception
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        cf.setKeyStorePassword("storepwd");

        cf.start();

        assertNotNull(cf.getSslContext());
    }

    @Test
    public void testNoTsSetKs() throws Exception
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream keystoreInputStream = this.getClass().getResourceAsStream("keystore.p12"))
        {
            ks.load(keystoreInputStream, "storepwd".toCharArray());
        }
        cf.setKeyStore(ks);

        cf.start();

        assertNotNull(cf.getSslContext());
    }

    @Test
    public void testNoTsNoKs() throws Exception
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        cf.start();
        assertNotNull(cf.getSslContext());
    }

    @Test
    public void testNoTsResourceKs() throws Exception
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        Resource keystoreResource = ResourceFactory.of(cf).newClassLoaderResource("keystore.p12");

        cf.setKeyStoreResource(keystoreResource);
        cf.setKeyStorePassword("storepwd");
        cf.setTrustStoreResource(keystoreResource);
        cf.setTrustStorePassword(null);

        cf.start();

        assertNotNull(cf.getSslContext());
    }

    @Test
    public void testResourceTsResourceKs() throws Exception
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        Resource keystoreResource = ResourceFactory.of(cf).newClassLoaderResource("keystore.p12");
        Resource truststoreResource = ResourceFactory.of(cf).newClassLoaderResource("keystore.p12");

        cf.setKeyStoreResource(keystoreResource);
        cf.setKeyStorePassword("storepwd");
        cf.setTrustStoreResource(truststoreResource);
        cf.setTrustStorePassword("storepwd");

        cf.start();

        assertNotNull(cf.getSslContext());
    }

    @Test
    public void testResourceTsWrongPWResourceKs() throws Exception
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        Resource keystoreResource = ResourceFactory.of(cf).newClassLoaderResource("keystore.p12");
        Resource truststoreResource = ResourceFactory.of(cf).newClassLoaderResource("keystore.p12");

        cf.setKeyStoreResource(keystoreResource);
        cf.setKeyStorePassword("storepwd");
        cf.setTrustStoreResource(truststoreResource);
        cf.setTrustStorePassword("wrong_storepwd");

        try (StacklessLogging ignore = new StacklessLogging(AbstractLifeCycle.class))
        {
            IOException x = assertThrows(IOException.class, cf::start);
            assertThat(x.getMessage(), containsString("password was incorrect"));
        }
    }

    @Test
    public void testNoKeyConfig()
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        try (StacklessLogging ignore = new StacklessLogging(AbstractLifeCycle.class))
        {
            IllegalArgumentException x = assertThrows(IllegalArgumentException.class, () ->
            {
                cf.setTrustStorePath("/foo");
                cf.start();
            });
            assertThat(x.getMessage(), containsString("TrustStore Path not accessible: /foo"));
        }
    }

    @Test
    public void testSetExcludeCipherSuitesRegex() throws Exception
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        cf.setExcludeCipherSuites(".*RC4.*");
        cf.start();
        SSLEngine sslEngine = cf.newSSLEngine();
        String[] enabledCipherSuites = sslEngine.getEnabledCipherSuites();
        assertThat("At least 1 cipherSuite is enabled", enabledCipherSuites.length, greaterThan(0));
        for (String enabledCipherSuite : enabledCipherSuites)
        {
            assertThat("CipherSuite does not contain RC4", enabledCipherSuite.contains("RC4"), equalTo(false));
        }
    }

    @Test
    public void testSetIncludeCipherSuitesRegex() throws Exception
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        cf.setIncludeCipherSuites(".*ECDHE.*", ".*WIBBLE.*");

        cf.start();
        SSLEngine sslEngine = cf.newSSLEngine();
        String[] enabledCipherSuites = sslEngine.getEnabledCipherSuites();
        assertThat("At least 1 cipherSuite is enabled", enabledCipherSuites.length, greaterThan(1));
        for (String enabledCipherSuite : enabledCipherSuites)
        {
            assertThat("CipherSuite contains ECDHE", enabledCipherSuite.contains("ECDHE"), equalTo(true));
        }
    }

    @Test
    public void testProtocolAndCipherSettingsAreNPESafe()
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        assertNotNull(cf.getExcludeProtocols());
        assertNotNull(cf.getIncludeProtocols());
        assertNotNull(cf.getExcludeCipherSuites());
        assertNotNull(cf.getIncludeCipherSuites());
    }

    @Test
    public void testSNICertificates() throws Exception
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        Resource keystoreResource = ResourceFactory.of(cf).newClassLoaderResource("keystore_sni.p12");

        cf.setKeyStoreResource(keystoreResource);
        cf.setKeyStorePassword("storepwd");

        cf.start();

        assertThat(cf.getAliases(), containsInAnyOrder("jetty", "other", "san", "wild"));

        assertThat(cf.getX509("jetty").getHosts(), containsInAnyOrder("jetty.eclipse.org"));
        assertTrue(cf.getX509("jetty").getWilds().isEmpty());
        assertTrue(cf.getX509("jetty").matches("JETTY.Eclipse.Org"));
        assertFalse(cf.getX509("jetty").matches("m.jetty.eclipse.org"));
        assertFalse(cf.getX509("jetty").matches("eclipse.org"));

        assertThat(cf.getX509("other").getHosts(), containsInAnyOrder("www.example.com"));
        assertTrue(cf.getX509("other").getWilds().isEmpty());
        assertTrue(cf.getX509("other").matches("www.example.com"));
        assertFalse(cf.getX509("other").matches("eclipse.org"));

        assertThat(cf.getX509("san").getHosts(), containsInAnyOrder("san example", "www.san.com", "m.san.com"));
        assertTrue(cf.getX509("san").getWilds().isEmpty());
        assertTrue(cf.getX509("san").matches("www.san.com"));
        assertTrue(cf.getX509("san").matches("m.san.com"));
        assertFalse(cf.getX509("san").matches("other.san.com"));
        assertFalse(cf.getX509("san").matches("san.com"));
        assertFalse(cf.getX509("san").matches("eclipse.org"));

        assertTrue(cf.getX509("wild").getHosts().isEmpty());
        assertThat(cf.getX509("wild").getWilds(), containsInAnyOrder("domain.com"));
        assertTrue(cf.getX509("wild").matches("domain.com"));
        assertTrue(cf.getX509("wild").matches("www.domain.com"));
        assertTrue(cf.getX509("wild").matches("other.domain.com"));
        assertFalse(cf.getX509("wild").matches("foo.bar.domain.com"));
        assertFalse(cf.getX509("wild").matches("other.com"));
    }

    @Test
    public void testNonDefaultKeyStoreTypeUsedForTrustStore() throws Exception
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        cf.setKeyStoreResource(ResourceFactory.of(cf).newClassLoaderResource("keystore.jks"));
        cf.setKeyStoreType("jks");
        cf.setKeyStorePassword("storepwd");
        cf.start();
        cf.stop();

        cf = new SslContextFactory.Server();
        cf.setKeyStoreResource(ResourceFactory.of(cf).newClassLoaderResource("keystore.jce"));
        cf.setKeyStoreType("jceks");
        cf.setKeyStorePassword("storepwd");
        cf.start();
        cf.stop();
    }

    @Test
    public void testClientSslContextFactory() throws Exception
    {
        SslContextFactory.Client cf = new SslContextFactory.Client();
        cf.start();

        assertEquals("HTTPS", cf.getEndpointIdentificationAlgorithm());
    }

    @Test
    public void testServerSslContextFactory() throws Exception
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        cf.start();

        assertNull(cf.getEndpointIdentificationAlgorithm());
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Will result in java.net.SocketException: An established connection was aborted by the software in your host machine during IO.readBytes(input)")
    public void testSNIWithPKIX() throws Exception
    {
        SslContextFactory.Server serverTLS = new SslContextFactory.Server();
        serverTLS.setKeyStoreResource(ResourceFactory.of(serverTLS).newClassLoaderResource("keystore_sni.p12"));
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setKeyManagerFactoryAlgorithm("PKIX");
        // The alias mapping function is required for PKIX.
        serverTLS.setAliasMapper(SslContextFactory.ALIAS_MAPPER_NULL);
        // Don't pick a default certificate if SNI does not match.
        serverTLS.setSniRequired(true);
        try
        {
            serverTLS.start();
            SslContextFactory.Client clientTLS = new SslContextFactory.Client(true);
            try
            {
                clientTLS.start();
                try (SSLServerSocket serverSocket = serverTLS.newSslServerSocket(null, 0, 128))
                {
                    String sniHost = "jetty.eclipse.org";
                    X509Certificate certificate = assertConnectEchoClose(serverSocket, sniHost);
                    assertTrue(new X509("alias", certificate).matches(sniHost));
                }
            }
            finally
            {
                clientTLS.stop();
            }
        }
        finally
        {
            serverTLS.stop();
        }
    }

    @Test
    public void testSNIWithPKIXAndDynamicKeyStore() throws Exception
    {
        SslContextFactory.Server serverTLS = new SslContextFactory.Server();

        char[] password = "storepwd".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = getClass().getResourceAsStream("/keystore_sni.p12"))
        {
            keyStore.load(input, password);
        }

        KeyStore dynamicKeyStore = KeyStore.getInstance("PKCS12");
        dynamicKeyStore.load(null, password);
        KeyStore.PasswordProtection protection = new KeyStore.PasswordProtection(password);
        dynamicKeyStore.setEntry("before", keyStore.getEntry("jetty", protection), protection);

        serverTLS.setKeyStore(dynamicKeyStore);
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setKeyManagerFactoryAlgorithm("PKIX");
        // The alias mapping function is required for PKIX.
        serverTLS.setAliasMapper(SslContextFactory.ALIAS_MAPPER_NULL);
        // Don't pick a default certificate if SNI does not match.
        serverTLS.setSniRequired(true);
        try
        {
            serverTLS.start();

            try (SSLServerSocket serverSocket = serverTLS.newSslServerSocket(null, 0, 128))
            {
                String sniHost = "jetty.eclipse.org";

                X509Certificate certificate = assertConnectEchoClose(serverSocket, sniHost);
                assertTrue(new X509("alias", certificate).matches(sniHost));

                // Remove the entry from the KeyStore, must not be able to connect.
                dynamicKeyStore.deleteEntry("before");
                assertThrows(SSLHandshakeException.class, () -> assertConnectEchoClose(serverSocket, sniHost));

                // Add a new entry to the KeyStore, under a different alias, must be able to connect.
                dynamicKeyStore.setEntry("after", keyStore.getEntry("jetty", protection), protection);
                certificate = assertConnectEchoClose(serverSocket, sniHost);
                assertTrue(new X509("alias", certificate).matches(sniHost));
            }
        }
        finally
        {
            serverTLS.stop();
        }
    }

    @Test
    public void testSNIWithPKIXAndDynamicKeyStoreAndPreferredAlias() throws Exception
    {
        SslContextFactory.Server serverTLS = new SslContextFactory.Server();

        char[] password = "storepwd".toCharArray();
        KeyStore p12KeyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = getClass().getResourceAsStream("/keystore.p12"))
        {
            p12KeyStore.load(input, password);
        }
        KeyStore jksKeyStore = KeyStore.getInstance("JKS");
        try (InputStream input = getClass().getResourceAsStream("/keystore.jks"))
        {
            jksKeyStore.load(input, password);
        }

        // Create a dynamic KeyStore that has 2 aliases for the same SNI "localhost".
        KeyStore dynamicKeyStore = KeyStore.getInstance("PKCS12");
        dynamicKeyStore.load(null, password);
        KeyStore.PasswordProtection protection = new KeyStore.PasswordProtection(password);
        dynamicKeyStore.setEntry("alias1", p12KeyStore.getEntry("mykey", protection), protection);
        dynamicKeyStore.setEntry("alias2", jksKeyStore.getEntry("mykey", protection), protection);

        serverTLS.setKeyStore(dynamicKeyStore);
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setKeyManagerFactoryAlgorithm("PKIX");
        // The alias mapping function is required for PKIX.
        serverTLS.setAliasMapper(SslContextFactory.ALIAS_MAPPER_NULL);
        // Don't pick a default certificate if SNI does not match.
        serverTLS.setSniRequired(true);
        // Specify a preferred alias.
        serverTLS.setCertAlias("alias2");
        try
        {
            serverTLS.start();
            try (SSLServerSocket serverSocket = serverTLS.newSslServerSocket(null, 0, 128))
            {
                String sniHost = "localhost.localdomain";
                X509Certificate certificate = assertConnectEchoClose(serverSocket, sniHost);
                assertTrue(new X509("alias", certificate).matches(sniHost));
                assertNotEquals(p12KeyStore.getCertificate("mykey"), certificate);
                assertEquals(jksKeyStore.getCertificate("mykey"), certificate);
            }
        }
        finally
        {
            serverTLS.stop();
        }
    }

    @Test
    public void testSNIWithPKIXAndDynamicKeyStoreAndExpiredCertificate() throws Exception
    {
        SslContextFactory.Server serverTLS = new SslContextFactory.Server();

        String sniHost = "jetty.eclipse.org";

        // Create an expired self-signed certificate for the SNI.
        Instant notAfter = Instant.now().minus(Duration.ofDays(1));
        KeyStore.PrivateKeyEntry expiredEntry = newSelfSignedPrivateKeyEntry(sniHost, notAfter.minus(Duration.ofDays(1)), notAfter);
        X509Certificate expiredCertificate = (X509Certificate)expiredEntry.getCertificate();

        char[] password = "storepwd".toCharArray();
        KeyStore sniKeyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = getClass().getResourceAsStream("/keystore_sni.p12"))
        {
            sniKeyStore.load(input, password);
        }

        // Create a dynamic KeyStore that only has an expired certificate for the SNI.
        KeyStore dynamicKeyStore = KeyStore.getInstance("PKCS12");
        dynamicKeyStore.load(null, password);
        KeyStore.PasswordProtection protection = new KeyStore.PasswordProtection(password);
        dynamicKeyStore.setEntry("expired", expiredEntry, protection);

        serverTLS.setKeyStore(dynamicKeyStore);
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setKeyManagerFactoryAlgorithm("PKIX");
        // The alias mapping function is required for PKIX.
        serverTLS.setAliasMapper(SslContextFactory.ALIAS_MAPPER_NULL);
        // Don't pick a default certificate if SNI does not match.
        serverTLS.setSniRequired(true);
        try
        {
            serverTLS.start();
            try (SSLServerSocket serverSocket = serverTLS.newSslServerSocket(null, 0, 128))
            {
                // The expired certificate is the only match, so it must be served.
                X509Certificate certificate = assertConnectEchoClose(serverSocket, sniHost);
                assertTrue(new X509("alias", certificate).matches(sniHost));
                assertEquals(expiredCertificate, certificate);

                // Add a valid certificate for the same SNI, it must be preferred.
                dynamicKeyStore.setEntry("valid", sniKeyStore.getEntry("jetty", protection), protection);
                certificate = assertConnectEchoClose(serverSocket, sniHost);
                assertTrue(new X509("alias", certificate).matches(sniHost));
                assertEquals(sniKeyStore.getCertificate("jetty"), certificate);
            }
        }
        finally
        {
            serverTLS.stop();
        }
    }

    @Test
    public void testSNIWithPKIXAndDynamicKeyStoreAndNotYetValidCertificate() throws Exception
    {
        SslContextFactory.Server serverTLS = new SslContextFactory.Server();

        String sniHost = "jetty.eclipse.org";

        // Create a not yet valid self-signed certificate for the SNI.
        Instant notBefore = Instant.now().plus(Duration.ofDays(1));
        KeyStore.PrivateKeyEntry notYetValidEntry = newSelfSignedPrivateKeyEntry(sniHost, notBefore, notBefore.plus(Duration.ofDays(1)));
        X509Certificate notYetValidCertificate = (X509Certificate)notYetValidEntry.getCertificate();

        char[] password = "storepwd".toCharArray();
        KeyStore sniKeyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = getClass().getResourceAsStream("/keystore_sni.p12"))
        {
            sniKeyStore.load(input, password);
        }

        // Create a dynamic KeyStore with a not yet valid certificate
        // for the SNI, and a valid certificate for a different host.
        KeyStore dynamicKeyStore = KeyStore.getInstance("PKCS12");
        dynamicKeyStore.load(null, password);
        KeyStore.PasswordProtection protection = new KeyStore.PasswordProtection(password);
        dynamicKeyStore.setEntry("future", notYetValidEntry, protection);
        dynamicKeyStore.setEntry("other", sniKeyStore.getEntry("other", protection), protection);
        X509Certificate otherCertificate = (X509Certificate)sniKeyStore.getCertificate("other");

        serverTLS.setKeyStore(dynamicKeyStore);
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setKeyManagerFactoryAlgorithm("PKIX");
        // The alias mapping function is required for PKIX.
        serverTLS.setAliasMapper(SslContextFactory.ALIAS_MAPPER_NULL);
        // Delegate the choice of the certificate if SNI does not match.
        serverTLS.setSniRequired(false);
        try
        {
            serverTLS.start();
            try (SSLServerSocket serverSocket = serverTLS.newSslServerSocket(null, 0, 128))
            {
                // The not yet valid certificate must not be served, so the
                // choice is delegated, and the other certificate is served.
                X509Certificate certificate = assertConnectEchoClose(serverSocket, sniHost);
                assertNotEquals(notYetValidCertificate, certificate);
                assertEquals(otherCertificate, certificate);
                assertFalse(new X509("alias", certificate).matches(sniHost));
            }
        }
        finally
        {
            serverTLS.stop();
        }
    }

    @Test
    public void testSNIWithPKIXAndDynamicKeyStoreAndValidWildcardAndExpiredExactCertificates() throws Exception
    {
        SslContextFactory.Server serverTLS = new SslContextFactory.Server();

        String sniHost = "www.domain.com";

        // Create an expired self-signed certificate that exactly matches the SNI.
        Instant notAfter = Instant.now().minus(Duration.ofDays(1));
        KeyStore.PrivateKeyEntry expiredEntry = newSelfSignedPrivateKeyEntry(sniHost, notAfter.minus(Duration.ofDays(1)), notAfter);
        X509Certificate expiredCertificate = (X509Certificate)expiredEntry.getCertificate();

        char[] password = "storepwd".toCharArray();
        KeyStore sniKeyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = getClass().getResourceAsStream("/keystore_sni.p12"))
        {
            sniKeyStore.load(input, password);
        }

        // Create a dynamic KeyStore with an expired exact certificate
        // and a valid wildcard certificate (*.domain.com) for the SNI.
        KeyStore dynamicKeyStore = KeyStore.getInstance("PKCS12");
        dynamicKeyStore.load(null, password);
        KeyStore.PasswordProtection protection = new KeyStore.PasswordProtection(password);
        dynamicKeyStore.setEntry("expired", expiredEntry, protection);
        dynamicKeyStore.setEntry("wild", sniKeyStore.getEntry("wild", protection), protection);
        X509Certificate wildCertificate = (X509Certificate)sniKeyStore.getCertificate("wild");

        serverTLS.setKeyStore(dynamicKeyStore);
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setKeyManagerFactoryAlgorithm("PKIX");
        // The alias mapping function is required for PKIX.
        serverTLS.setAliasMapper(SslContextFactory.ALIAS_MAPPER_NULL);
        // Don't pick a default certificate if SNI does not match.
        serverTLS.setSniRequired(true);
        try
        {
            serverTLS.start();
            try (SSLServerSocket serverSocket = serverTLS.newSslServerSocket(null, 0, 128))
            {
                // Both certificates match the SNI, but the valid
                // wildcard certificate must beat the expired exact one.
                X509Certificate certificate = assertConnectEchoClose(serverSocket, sniHost);
                X509 x509 = new X509("alias", certificate);
                assertTrue(x509.matches(sniHost));
                assertFalse(x509.getWilds().isEmpty());
                assertNotEquals(expiredCertificate, certificate);
                assertEquals(wildCertificate, certificate);
            }
        }
        finally
        {
            serverTLS.stop();
        }
    }

    @Test
    public void testSNIWithPKIXAndDynamicKeyStoreAndNewerCertificate() throws Exception
    {
        SslContextFactory.Server serverTLS = new SslContextFactory.Server();

        String sniHost = "jetty.eclipse.org";

        // Create two valid self-signed certificates for the SNI,
        // the newer one issued more recently than the older one.
        Instant now = Instant.now();
        Instant notAfter = now.plus(Duration.ofDays(30));
        KeyStore.PrivateKeyEntry olderEntry = newSelfSignedPrivateKeyEntry(sniHost, now.minus(Duration.ofDays(10)), notAfter);
        X509Certificate olderCertificate = (X509Certificate)olderEntry.getCertificate();
        KeyStore.PrivateKeyEntry newerEntry = newSelfSignedPrivateKeyEntry(sniHost, now.minus(Duration.ofDays(1)), notAfter);
        X509Certificate newerCertificate = (X509Certificate)newerEntry.getCertificate();

        // Add the older certificate first, so that it
        // comes first in the KeyManager aliases order.
        char[] password = "storepwd".toCharArray();
        KeyStore dynamicKeyStore = KeyStore.getInstance("PKCS12");
        dynamicKeyStore.load(null, password);
        KeyStore.PasswordProtection protection = new KeyStore.PasswordProtection(password);
        dynamicKeyStore.setEntry("older", olderEntry, protection);
        dynamicKeyStore.setEntry("newer", newerEntry, protection);

        serverTLS.setKeyStore(dynamicKeyStore);
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setKeyManagerFactoryAlgorithm("PKIX");
        // The alias mapping function is required for PKIX.
        serverTLS.setAliasMapper(SslContextFactory.ALIAS_MAPPER_NULL);
        // Don't pick a default certificate if SNI does not match.
        serverTLS.setSniRequired(true);
        try
        {
            serverTLS.start();
            try (SSLServerSocket serverSocket = serverTLS.newSslServerSocket(null, 0, 128))
            {
                // Both certificates are valid and match the SNI,
                // but the newer certificate must beat the older one.
                X509Certificate certificate = assertConnectEchoClose(serverSocket, sniHost);
                assertTrue(new X509("alias", certificate).matches(sniHost));
                assertNotEquals(olderCertificate, certificate);
                assertEquals(newerCertificate, certificate);
            }
        }
        finally
        {
            serverTLS.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testSNIWithPKIXAndDynamicKeyStoreAndAddedCertificateForNewHost(boolean openJDKAliasMapper) throws Exception
    {
        SslContextFactory.Server serverTLS = new SslContextFactory.Server();

        char[] password = "storepwd".toCharArray();
        KeyStore sniKeyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = getClass().getResourceAsStream("/keystore_sni.p12"))
        {
            sniKeyStore.load(input, password);
        }
        X509Certificate jettyCertificate = (X509Certificate)sniKeyStore.getCertificate("jetty");
        X509Certificate otherCertificate = (X509Certificate)sniKeyStore.getCertificate("other");

        // Create a dynamic KeyStore with only one certificate.
        KeyStore dynamicKeyStore = KeyStore.getInstance("PKCS12");
        dynamicKeyStore.load(null, password);
        KeyStore.PasswordProtection protection = new KeyStore.PasswordProtection(password);
        dynamicKeyStore.setEntry("jetty", sniKeyStore.getEntry("jetty", protection), protection);

        serverTLS.setKeyStore(dynamicKeyStore);
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setKeyManagerFactoryAlgorithm("PKIX");
        // The alias mapping function is required for PKIX.
        serverTLS.setAliasMapper(openJDKAliasMapper ? SslContextFactory.ALIAS_MAPPER_OPENJDK_PKIX : SslContextFactory.ALIAS_MAPPER_NULL);
        // Don't pick a default certificate if SNI does not match.
        serverTLS.setSniRequired(true);
        try
        {
            serverTLS.start();
            try (SSLServerSocket serverSocket = serverTLS.newSslServerSocket(null, 0, 128))
            {
                String jettyHost = "jetty.eclipse.org";
                String otherHost = "www.example.com";

                X509Certificate certificate = assertConnectEchoClose(serverSocket, jettyHost);
                assertEquals(jettyCertificate, certificate);

                // There is no certificate for the other host yet, must not be able to connect.
                assertThrows(SSLHandshakeException.class, () -> assertConnectEchoClose(serverSocket, otherHost));

                // Add a certificate for the other host at runtime, must be selected.
                dynamicKeyStore.setEntry("other", sniKeyStore.getEntry("other", protection), protection);
                certificate = assertConnectEchoClose(serverSocket, otherHost);
                assertTrue(new X509("alias", certificate).matches(otherHost));
                assertEquals(otherCertificate, certificate);

                // The first certificate must still be selected for its host.
                certificate = assertConnectEchoClose(serverSocket, jettyHost);
                assertEquals(jettyCertificate, certificate);
            }
        }
        finally
        {
            serverTLS.stop();
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
        "SunX509 | unknown.com",
        "SunX509 | null",
        "PKIX    | unknown.com",
        "PKIX    | null"
    })
    public void testCertAliasWithoutSNIMatch(String keyManagerFactoryAlgorithm, String sniHost) throws Exception
    {
        SslContextFactory.Server serverTLS = new SslContextFactory.Server();
        Resource keyStoreResource = ResourceFactory.of(serverTLS).newClassLoaderResource("keystore_sni.p12");
        serverTLS.setKeyStoreResource(keyStoreResource);
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setKeyManagerFactoryAlgorithm(keyManagerFactoryAlgorithm);
        if ("PKIX".equals(keyManagerFactoryAlgorithm))
            serverTLS.setAliasMapper(SslContextFactory.ALIAS_MAPPER_NULL);
        // Delegate the choice of the certificate if SNI does not match.
        serverTLS.setSniRequired(false);
        // Not the first alias in the KeyStore, so that it is
        // not chosen by chance when the choice is delegated.
        serverTLS.setCertAlias("san");

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = keyStoreResource.newInputStream())
        {
            keyStore.load(input, "storepwd".toCharArray());
        }
        X509Certificate sanCertificate = (X509Certificate)keyStore.getCertificate("san");

        try
        {
            serverTLS.start();
            try (SSLServerSocket serverSocket = serverTLS.newSslServerSocket(null, 0, 128))
            {
                // No certificate matches the SNI (or there is no SNI),
                // so the certificate with the configured alias must be served.
                X509Certificate certificate = assertConnectEchoClose(serverSocket, sniHost);
                assertEquals(sanCertificate, certificate);
            }
        }
        finally
        {
            serverTLS.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testSNIWithPKIXAndIdentityAliasMapper(boolean sniRequired) throws Exception
    {
        SslContextFactory.Server serverTLS = new SslContextFactory.Server();
        serverTLS.setKeyStoreResource(ResourceFactory.of(serverTLS).newClassLoaderResource("keystore_sni.p12"));
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setKeyManagerFactoryAlgorithm("PKIX");
        // The identity mapper is wrong for PKIX, but the implementation is
        // lenient enough to make it work (although with a performance impact).
        serverTLS.setAliasMapper(SslContextFactory.ALIAS_MAPPER_IDENTITY);
        serverTLS.setSniRequired(sniRequired);
        try
        {
            serverTLS.start();
            try (SSLServerSocket serverSocket = serverTLS.newSslServerSocket(null, 0, 128))
            {
                String sniHost = "jetty.eclipse.org";
                X509Certificate certificate = assertConnectEchoClose(serverSocket, sniHost);
                assertTrue(new X509("alias", certificate).matches(sniHost));
            }
        }
        finally
        {
            serverTLS.stop();
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SunX509 | ALIAS_MAPPER_IDENTITY     | true",
        "PKIX    | ALIAS_MAPPER_OPENJDK_PKIX | true",
        "PKIX    | ALIAS_MAPPER_NULL         | true",
        "PKIX    | ALIAS_MAPPER_IDENTITY     | true"
    })
    public void testClientCertAlias(String keyManagerFactoryAlgorithm, String aliasMapper, boolean success) throws Exception
    {
        // Create two client certificates.
        Instant now = Instant.now();
        KeyStore.PrivateKeyEntry client1Entry = newSelfSignedPrivateKeyEntry("client1", now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)));
        KeyStore.PrivateKeyEntry client2Entry = newSelfSignedPrivateKeyEntry("client2", now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)));

        // The server requires a client certificate, and trusts both.
        KeyStore serverTrustStore = KeyStore.getInstance("PKCS12");
        serverTrustStore.load(null, null);
        serverTrustStore.setCertificateEntry("client1", client1Entry.getCertificate());
        serverTrustStore.setCertificateEntry("client2", client2Entry.getCertificate());
        char[] password = "storepwd".toCharArray();
        KeyStore serverKeyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = getClass().getResourceAsStream("/keystore_sni.p12"))
        {
            serverKeyStore.load(input, password);
        }
        SslContextFactory.Server serverTLS = new SslContextFactory.Server();
        serverTLS.setKeyStore(serverKeyStore);
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setTrustStore(serverTrustStore);
        serverTLS.setNeedClientAuth(true);

        // The client trusts the server certificates.
        KeyStore clientTrustStore = KeyStore.getInstance("PKCS12");
        clientTrustStore.load(null, null);
        for (String alias : Collections.list(serverKeyStore.aliases()))
        {
            clientTrustStore.setCertificateEntry(alias, serverKeyStore.getCertificate(alias));
        }

        // Create a client KeyStore with the two client certificates.
        KeyStore clientKeyStore = KeyStore.getInstance("PKCS12");
        clientKeyStore.load(null, password);
        KeyStore.PasswordProtection protection = new KeyStore.PasswordProtection(password);
        clientKeyStore.setEntry("client1", client1Entry, protection);
        clientKeyStore.setEntry("client2", client2Entry, protection);

        SslContextFactory.Client clientTLS = new SslContextFactory.Client();
        clientTLS.setEndpointIdentificationAlgorithm(null);
        clientTLS.setKeyStore(clientKeyStore);
        clientTLS.setKeyStorePassword("storepwd");
        clientTLS.setTrustStore(clientTrustStore);
        clientTLS.setKeyManagerFactoryAlgorithm(keyManagerFactoryAlgorithm);
        @SuppressWarnings("unchecked")
        UnaryOperator<String> aliasMapperFn = (UnaryOperator<String>)SslContextFactory.class.getField(aliasMapper).get(clientTLS);
        clientTLS.setAliasMapper(aliasMapperFn);
        // Not the first alias in the KeyStore, so that it is
        // not chosen by chance when the choice is delegated.
        clientTLS.setCertAlias("client2");

        try
        {
            serverTLS.start();
            clientTLS.start();
            try (SSLServerSocket serverSocket = serverTLS.newSslServerSocket(null, 0, 128);
                 SSLSocket clientSocket = clientTLS.newSslSocket())
            {
                clientSocket.connect(new InetSocketAddress("localhost", serverSocket.getLocalPort()), 5000);
                try (SSLSocket sslSocket = (SSLSocket)serverSocket.accept())
                {
                    // The server handshake completes when the client certificate is received.
                    CompletableFuture<Certificate> serverHandshake = CompletableFuture.supplyAsync(() ->
                    {
                        try
                        {
                            sslSocket.startHandshake();
                            return sslSocket.getSession().getPeerCertificates()[0];
                        }
                        catch (IOException x)
                        {
                            throw new UncheckedIOException(x);
                        }
                    });

                    try
                    {
                        clientSocket.startHandshake();
                    }
                    catch (IOException x)
                    {
                        // The client may notice the failure, depending on the TLS version.
                        if (success)
                            throw x;
                    }

                    if (success)
                    {
                        assertEquals(client2Entry.getCertificate(), serverHandshake.get(5, TimeUnit.SECONDS));
                    }
                    else
                    {
                        // No client certificate is sent, so the server fails the handshake.
                        ExecutionException failure = assertThrows(ExecutionException.class, () -> serverHandshake.get(5, TimeUnit.SECONDS));
                        assertInstanceOf(SSLHandshakeException.class, failure.getCause().getCause());
                    }
                }
            }
        }
        finally
        {
            clientTLS.stop();
            serverTLS.stop();
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
        "1.0.alias        | alias",
        "123.0.my.alias   | my.alias",
        // Not the first KeyStore.
        "1.1.alias        | null",
        // Not in the OpenJDK PKIX form.
        "alias            | null",
        "1.alias          | null",
        "a.0.alias        | null",
        ".0.alias         | null",
        "1.0.             | null",
    })
    public void testOpenJDKPKIXAliasMapper(String keyManagerAlias, String keyStoreAlias)
    {
        assertEquals(keyStoreAlias, SslContextFactory.ALIAS_MAPPER_OPENJDK_PKIX.apply(keyManagerAlias));
    }

    private static KeyStore.PrivateKeyEntry newSelfSignedPrivateKeyEntry(String host, Instant notBefore, Instant notAfter) throws Exception
    {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        X500Name subject = new X500Name("CN=" + host);
        X509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(subject, BigInteger.ONE, Date.from(notBefore), Date.from(notAfter), subject, keyPair.getPublic())
            .addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.dNSName, host)));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(certificateBuilder.build(signer));
        return new KeyStore.PrivateKeyEntry(keyPair.getPrivate(), new Certificate[]{certificate});
    }

    private static X509Certificate assertConnectEchoClose(SSLServerSocket serverSocket, String sniHost) throws Exception
    {
        SslContextFactory.Client clientTLS = new SslContextFactory.Client(true);
        clientTLS.start();
        try
        {
            try (SSLSocket clientSocket = clientTLS.newSslSocket())
            {
                SSLParameters sslParameters = clientSocket.getSSLParameters();
                // A null sniHost means that the client does not send the SNI extension.
                sslParameters.setServerNames(sniHost == null ? List.of() : List.of(new SNIHostName(sniHost)));
                clientSocket.setSSLParameters(sslParameters);
                clientSocket.connect(new InetSocketAddress("localhost", serverSocket.getLocalPort()), 5000);
                AtomicReference<X509Certificate> certificateRef = new AtomicReference<>();
                byte[] data = new byte[]{'H', 'E', 'L', 'L', 'O'};
                try (SSLSocket sslSocket = (SSLSocket)serverSocket.accept())
                {
                    CompletableFuture.runAsync(() ->
                    {
                        try
                        {
                            clientSocket.startHandshake();
                            Certificate[] certificates = clientSocket.getSession().getPeerCertificates();
                            assertNotNull(certificates);
                            assertThat(certificates.length, greaterThan(0));
                            certificateRef.set((X509Certificate)certificates[0]);
                            OutputStream clientOutput = clientSocket.getOutputStream();
                            clientOutput.write(data);
                            clientOutput.flush();
                        }
                        catch (IOException x)
                        {
                            IO.close(sslSocket);
                            throw new UncheckedIOException(x);
                        }
                    });

                    // Verify that we received the data the client sent.
                    sslSocket.setSoTimeout(1000);
                    InputStream serverInput = sslSocket.getInputStream();
                    byte[] buffer = new byte[128];
                    int read = serverInput.read(buffer);
                    assertEquals(data.length, read);
                    assertArrayEquals(data, Arrays.copyOfRange(buffer, 0, read));

                    // Speed up the test by closing as soon as possible.
                    clientSocket.close();

                    return certificateRef.get();
                }
            }
        }
        finally
        {
            clientTLS.stop();
        }
    }
}
