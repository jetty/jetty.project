//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util.ssl;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.toolchain.test.JDK;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;


public class SslContextFactoryTest
{
    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    private SslContextFactory cf;

    @Before
    public void setUp() throws Exception
    {
        cf = new SslContextFactory();
    }

    @Test
    public void testSLOTH() throws Exception
    {
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");

        cf.start();
        
        cf.dump(System.out, "");
    }
    
    @Test
    public void testNoTsFileKs() throws Exception
    {
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");

        cf.start();

        assertTrue(cf.getSslContext()!=null);
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

        assertTrue(cf.getSslContext()!=null);
    }

    @Test
    public void testNoTsNoKs() throws Exception
    {
        cf.start();
        assertTrue(cf.getSslContext()!=null);
    }

    @Test
    public void testTrustAll() throws Exception
    {
        cf.start();
        assertTrue(cf.getSslContext()!=null);
    }

    @Test
    public void testNoTsResourceKs() throws Exception
    {
        Resource keystoreResource = Resource.newSystemResource("keystore");

        cf.setKeyStoreResource(keystoreResource);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        
        cf.start();

        assertTrue(cf.getSslContext()!=null);
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

        assertTrue(cf.getSslContext()!=null);
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

        try (StacklessLogging ignored = new StacklessLogging(AbstractLifeCycle.class))
        {
            expectedException.expect(java.security.UnrecoverableKeyException.class);
            cf.start();
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

        try (StacklessLogging ignored = new StacklessLogging(AbstractLifeCycle.class))
        {
            expectedException.expect(IOException.class);
            expectedException.expectMessage(containsString("Keystore was tampered with, or password was incorrect"));
            cf.start();
        }
    }

    @Test
    public void testNoKeyConfig() throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(AbstractLifeCycle.class))
        {
            cf.setTrustStorePath("/foo");
            expectedException.expect(IllegalStateException.class);
            expectedException.expectMessage(containsString("SSL doesn't have a valid keystore"));
            cf.start();
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
            assertThat("CipherSuite does not contain RC4", enabledCipherSuite.contains("RC4"), is(false));
    }

    @Test
    public void testSetIncludeCipherSuitesRegex() throws Exception
    {
        cf.setIncludeCipherSuites(".*ECDHE.*",".*WIBBLE.*");
        Assume.assumeFalse(JDK.IS_8);
        
        cf.start();
        SSLEngine sslEngine = cf.newSSLEngine();
        String[] enabledCipherSuites = sslEngine.getEnabledCipherSuites();
        assertThat("At least 1 cipherSuite is enabled", enabledCipherSuites.length, greaterThan(1));
        for (String enabledCipherSuite : enabledCipherSuites)
            assertThat("CipherSuite contains ECDHE", enabledCipherSuite.contains("ECDHE"), equalTo(true));
    }

    @Test
    public void testProtocolAndCipherSettingsAreNPESafe()
    {
    	assertNotNull(cf.getExcludeProtocols());
    	assertNotNull(cf.getIncludeProtocols());
    	assertNotNull(cf.getExcludeCipherSuites());
    	assertNotNull(cf.getIncludeCipherSuites());
    }
}
