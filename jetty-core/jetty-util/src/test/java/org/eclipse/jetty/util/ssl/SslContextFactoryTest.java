//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;

import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.Test;

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
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        Resource keystoreResource = ResourceFactory.of(cf).newSystemResource("keystore.p12");

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
        Resource keystoreResource = ResourceFactory.of(cf).newSystemResource("keystore.p12");
        Resource truststoreResource = ResourceFactory.of(cf).newSystemResource("keystore.p12");

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
        Resource keystoreResource = ResourceFactory.of(cf).newSystemResource("keystore.p12");
        Resource truststoreResource = ResourceFactory.of(cf).newSystemResource("keystore.p12");

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
            assertThat(x.getMessage(), containsString("TrustStore Path does not exist: /foo"));
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
        Resource keystoreResource = ResourceFactory.of(cf).newSystemResource("snikeystore.p12");

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
        cf.setKeyStoreResource(ResourceFactory.of(cf).newSystemResource("keystore.p12"));
        cf.setKeyStoreType("pkcs12");
        cf.setKeyStorePassword("storepwd");
        cf.start();
        cf.stop();

        cf = new SslContextFactory.Server();
        cf.setKeyStoreResource(ResourceFactory.of(cf).newSystemResource("keystore.jce"));
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
    public void testSNIWithPKIX() throws Exception
    {
        SslContextFactory.Server serverTLS = new SslContextFactory.Server()
        {
            @Override
            protected X509ExtendedKeyManager newSniX509ExtendedKeyManager(X509ExtendedKeyManager keyManager)
            {
                SniX509ExtendedKeyManager result = new SniX509ExtendedKeyManager(keyManager, this);
                result.setAliasMapper(alias ->
                {
                    // Workaround for https://bugs.openjdk.java.net/browse/JDK-8246262.
                    Matcher matcher = Pattern.compile(".*\\..*\\.(.*)").matcher(alias);
                    if (matcher.matches())
                        return matcher.group(1);
                    return alias;
                });
                return result;
            }
        };
        // This test requires a SNI keystore so that the X509ExtendedKeyManager is wrapped.
        serverTLS.setKeyStoreResource(ResourceFactory.of(serverTLS).newSystemResource("keystore_sni.p12"));
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setKeyManagerFactoryAlgorithm("PKIX");
        // Don't pick a default certificate if SNI does not match.
        serverTLS.setSniRequired(true);
        serverTLS.start();

        SslContextFactory.Client clientTLS = new SslContextFactory.Client(true);
        clientTLS.start();

        try (SSLServerSocket serverSocket = serverTLS.newSslServerSocket(null, 0, 128);
             SSLSocket clientSocket = clientTLS.newSslSocket())
        {
            SSLParameters sslParameters = clientSocket.getSSLParameters();
            String hostName = "jetty.eclipse.org";
            sslParameters.setServerNames(Collections.singletonList(new SNIHostName(hostName)));
            clientSocket.setSSLParameters(sslParameters);
            clientSocket.connect(new InetSocketAddress("localhost", serverSocket.getLocalPort()), 5000);
            try (SSLSocket sslSocket = (SSLSocket)serverSocket.accept())
            {
                byte[] data = "HELLO".getBytes(StandardCharsets.UTF_8);
                new Thread(() ->
                {
                    try
                    {
                        // Start the TLS handshake and verify that
                        // the client got the right server certificate.
                        clientSocket.startHandshake();
                        Certificate[] certificates = clientSocket.getSession().getPeerCertificates();
                        assertThat(certificates.length, greaterThan(0));
                        X509Certificate certificate = (X509Certificate)certificates[0];
                        assertThat(certificate.getSubjectX500Principal().getName(), startsWith("CN=" + hostName));
                        // Send some data to verify communication is ok.
                        OutputStream output = clientSocket.getOutputStream();
                        output.write(data);
                        output.flush();
                        clientSocket.close();
                    }
                    catch (Throwable x)
                    {
                        x.printStackTrace();
                    }
                }).start();
                // Verify that we received the data the client sent.
                sslSocket.setSoTimeout(5000);
                InputStream input = sslSocket.getInputStream();
                byte[] bytes = IO.readBytes(input);
                assertArrayEquals(data, bytes);
            }
        }

        clientTLS.stop();
        serverTLS.stop();
    }
}
