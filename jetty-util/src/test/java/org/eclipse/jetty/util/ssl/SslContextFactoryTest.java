//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.ssl;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SslContextFactoryTest
{
    private SslContextFactory cf;

    @BeforeEach
    public void setUp() throws Exception
    {
        cf = new SslContextFactory.Server();

        java.security.cert.CertPathBuilder certPathBuilder = java.security.cert.CertPathBuilder.getInstance("PKIX");
        java.security.cert.PKIXRevocationChecker revocationChecker = (java.security.cert.PKIXRevocationChecker)certPathBuilder.getRevocationChecker();
        revocationChecker.setOptions(java.util.EnumSet.of(
            java.security.cert.PKIXRevocationChecker.Option.valueOf("PREFER_CRLS"),
            java.security.cert.PKIXRevocationChecker.Option.valueOf("SOFT_FAIL"),
            java.security.cert.PKIXRevocationChecker.Option.valueOf("NO_FALLBACK")));
        cf.setPkixCertPathChecker(revocationChecker);
    }

    @Test
    public void testSLOTH() throws Exception
    {
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");

        cf.start();

        // cf.dump(System.out, "");
        List<SslSelectionDump> dumps = cf.selectionDump();

        SslSelectionDump cipherDump = dumps.stream()
            .filter((dump) -> dump.type.contains("Cipher Suite"))
            .findFirst().get();

        for (String enabledCipher : cipherDump.enabled)
        {
            assertThat("Enabled Cipher Suite", enabledCipher, not(matchesRegex(".*_RSA_.*(SHA1|MD5|SHA)")));
        }
    }

    @Test
    public void testDumpIncludeTlsRsa() throws Exception
    {
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
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
        SslSelectionDump cipherDump = dumps.stream()
            .filter((dump) -> dump.type.contains("Cipher Suite"))
            .findFirst().get();
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
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");

        cf.start();

        assertTrue(cf.getSslContext() != null);
    }

    @Test
    public void testNoTsSetKs() throws Exception
    {
        KeyStore ks = KeyStore.getInstance("JKS");
        try (InputStream keystoreInputStream = this.getClass().getResourceAsStream("keystore"))
        {
            ks.load(keystoreInputStream, "storepwd".toCharArray());
        }
        cf.setKeyStore(ks);
        cf.setKeyManagerPassword("keypwd");

        cf.start();

        assertTrue(cf.getSslContext() != null);
    }

    @Test
    public void testNoTsNoKs() throws Exception
    {
        cf.start();
        assertTrue(cf.getSslContext() != null);
    }

    @Test
    public void testTrustAll() throws Exception
    {
        cf.start();
        assertTrue(cf.getSslContext() != null);
    }

    @Test
    public void testNoTsResourceKs() throws Exception
    {
        Resource keystoreResource = Resource.newSystemResource("keystore");

        cf.setKeyStoreResource(keystoreResource);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        cf.setTrustStoreResource(keystoreResource);
        cf.setTrustStorePassword(null);

        cf.start();

        assertTrue(cf.getSslContext() != null);
    }

    @Test
    public void testResourceTsResourceKs() throws Exception
    {
        Resource keystoreResource = Resource.newSystemResource("keystore");
        Resource truststoreResource = Resource.newSystemResource("keystore");

        cf.setKeyStoreResource(keystoreResource);
        cf.setTrustStoreResource(truststoreResource);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        cf.setTrustStorePassword("storepwd");

        cf.start();

        assertTrue(cf.getSslContext() != null);
    }

    @Test
    public void testResourceTsResourceKsWrongPW() throws Exception
    {
        Resource keystoreResource = Resource.newSystemResource("keystore");
        Resource truststoreResource = Resource.newSystemResource("keystore");

        cf.setKeyStoreResource(keystoreResource);
        cf.setTrustStoreResource(truststoreResource);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("wrong_keypwd");
        cf.setTrustStorePassword("storepwd");

        try (StacklessLogging ignore = new StacklessLogging(AbstractLifeCycle.class))
        {
            java.security.UnrecoverableKeyException x = assertThrows(
                java.security.UnrecoverableKeyException.class, () -> cf.start());
            assertThat(x.getMessage(), containsString("Cannot recover key"));
        }
    }

    @Test
    public void testResourceTsWrongPWResourceKs() throws Exception
    {
        Resource keystoreResource = Resource.newSystemResource("keystore");
        Resource truststoreResource = Resource.newSystemResource("keystore");

        cf.setKeyStoreResource(keystoreResource);
        cf.setTrustStoreResource(truststoreResource);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        cf.setTrustStorePassword("wrong_storepwd");

        try (StacklessLogging ignore = new StacklessLogging(AbstractLifeCycle.class))
        {
            IOException x = assertThrows(IOException.class, () -> cf.start());
            assertThat(x.getMessage(), containsString("Keystore was tampered with, or password was incorrect"));
        }
    }

    @Test
    public void testNoKeyConfig() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(AbstractLifeCycle.class))
        {
            IllegalStateException x = assertThrows(IllegalStateException.class, () ->
            {
                cf.setTrustStorePath("/foo");
                cf.start();
            });
            assertThat(x.getMessage(), containsString("no valid keystore"));
        }
    }

    @Test
    public void testSetExcludeCipherSuitesRegex() throws Exception
    {
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
        assertNotNull(cf.getExcludeProtocols());
        assertNotNull(cf.getIncludeProtocols());
        assertNotNull(cf.getExcludeCipherSuites());
        assertNotNull(cf.getIncludeCipherSuites());
    }

    @Test
    public void testSNICertificates() throws Exception
    {
        Resource keystoreResource = Resource.newSystemResource("snikeystore");

        cf.setKeyStoreResource(keystoreResource);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");

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

        assertThat(cf.getX509("san").getHosts(), containsInAnyOrder("www.san.com", "m.san.com"));
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
        cf = new SslContextFactory.Server();
        cf.setKeyStoreResource(Resource.newSystemResource("keystore.p12"));
        cf.setKeyStoreType("pkcs12");
        cf.setKeyStorePassword("storepwd");
        cf.start();
        cf.stop();

        cf = new SslContextFactory.Server();
        cf.setKeyStoreResource(Resource.newSystemResource("keystore.jce"));
        cf.setKeyStoreType("jceks");
        cf.setKeyStorePassword("storepwd");
        cf.start();
        cf.stop();
    }

    @Test
    public void testClientSslContextFactory() throws Exception
    {
        cf = new SslContextFactory.Client();
        cf.start();

        assertEquals("HTTPS", cf.getEndpointIdentificationAlgorithm());
    }

    @Test
    public void testServerSslContextFactory() throws Exception
    {
        cf = new SslContextFactory.Server();
        cf.start();

        assertNull(cf.getEndpointIdentificationAlgorithm());
    }
}
