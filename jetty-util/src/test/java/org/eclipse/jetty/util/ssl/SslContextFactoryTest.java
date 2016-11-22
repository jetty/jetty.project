//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertFalse;
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
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;


public class SslContextFactoryTest
{

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
        cf.setTrustStoreResource(keystoreResource);
        cf.setTrustStorePassword(null);

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

        try (StacklessLogging stackless = new StacklessLogging(AbstractLifeCycle.class))
        {
            cf.start();
            Assert.fail();
        }
        catch(java.security.UnrecoverableKeyException e)
        {
            Assert.assertThat(e.toString(),Matchers.containsString("UnrecoverableKeyException"));
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

        try (StacklessLogging stackless = new StacklessLogging(AbstractLifeCycle.class))
        {
            cf.start();
            Assert.fail();
        }
        catch(IOException e)
        {
            Assert.assertThat(e.toString(),Matchers.containsString("java.io.IOException: Keystore was tampered with, or password was incorrect"));
        }
    }

    @Test
    public void testNoKeyConfig() throws Exception
    {
        try (StacklessLogging stackless = new StacklessLogging(AbstractLifeCycle.class))
        {
            cf.setTrustStorePath("/foo");
            cf.start();
            Assert.fail();
        }
        catch (IllegalStateException e)
        {
            Assert.assertThat(e.toString(),Matchers.containsString("IllegalStateException: no valid keystore"));
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
            assertThat("CipherSuite does not contain RC4", enabledCipherSuite.contains("RC4"), equalTo(false));
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
    
    @Test
    public void testSNICertificates() throws Exception
    {
        Resource keystoreResource = Resource.newSystemResource("snikeystore");

        cf.setKeyStoreResource(keystoreResource);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        
        cf.start();
        
        assertThat(cf.getAliases(),containsInAnyOrder("jetty","other","san","wild"));
        
        assertThat(cf.getX509("jetty").getHosts(),containsInAnyOrder("jetty.eclipse.org"));
        assertTrue(cf.getX509("jetty").getWilds().isEmpty());
        assertTrue(cf.getX509("jetty").matches("JETTY.Eclipse.Org"));
        assertFalse(cf.getX509("jetty").matches("m.jetty.eclipse.org"));
        assertFalse(cf.getX509("jetty").matches("eclipse.org"));
        
        assertThat(cf.getX509("other").getHosts(),containsInAnyOrder("www.example.com"));
        assertTrue(cf.getX509("other").getWilds().isEmpty());
        assertTrue(cf.getX509("other").matches("www.example.com"));
        assertFalse(cf.getX509("other").matches("eclipse.org"));
        
        assertThat(cf.getX509("san").getHosts(),containsInAnyOrder("www.san.com","m.san.com"));
        assertTrue(cf.getX509("san").getWilds().isEmpty());
        assertTrue(cf.getX509("san").matches("www.san.com"));
        assertTrue(cf.getX509("san").matches("m.san.com"));
        assertFalse(cf.getX509("san").matches("other.san.com"));
        assertFalse(cf.getX509("san").matches("san.com"));
        assertFalse(cf.getX509("san").matches("eclipse.org"));
        
        assertTrue(cf.getX509("wild").getHosts().isEmpty());
        assertThat(cf.getX509("wild").getWilds(),containsInAnyOrder("domain.com"));
        assertTrue(cf.getX509("wild").matches("domain.com"));
        assertTrue(cf.getX509("wild").matches("www.domain.com"));
        assertTrue(cf.getX509("wild").matches("other.domain.com"));
        assertFalse(cf.getX509("wild").matches("foo.bar.domain.com"));
        assertFalse(cf.getX509("wild").matches("other.com"));
    }
}
