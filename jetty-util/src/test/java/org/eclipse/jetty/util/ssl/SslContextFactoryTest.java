//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Arrays;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.util.resource.Resource;
import org.hamcrest.Matchers;
import org.junit.Assert;
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
    public void testNoTsFileKs() throws Exception
    {
        String keystorePath = System.getProperty("basedir",".") + "/src/test/resources/keystore";
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

        try
        {
            ((StdErrLog)Log.getLogger(AbstractLifeCycle.class)).setHideStacks(true);
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

        try
        {
            ((StdErrLog)Log.getLogger(AbstractLifeCycle.class)).setHideStacks(true);
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
        try
        {
            ((StdErrLog)Log.getLogger(AbstractLifeCycle.class)).setHideStacks(true);
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
            assertThat("CipherSuite does not contain RC4", enabledCipherSuite.contains("RC4"), is(false));
    }

    @Test
    public void testSetIncludeCipherSuitesRegex() throws Exception
    {
        Log.getLogger(SslContextFactory.class).setDebugEnabled(true);
        cf.setIncludeCipherSuites(".*ECDHE.*",".*WIBBLE.*");
        cf.start();
        SSLEngine sslEngine = cf.newSSLEngine();
        String[] enabledCipherSuites = sslEngine.getEnabledCipherSuites();
        assertThat("At least 1 cipherSuite is enabled", enabledCipherSuites.length, greaterThan(1));
        for (String enabledCipherSuite : enabledCipherSuites)
            assertThat("CipherSuite contains ECDHE", enabledCipherSuite.contains("ECDHE"), is(true));
    }

    @Test
    public void testProtocolAndCipherSettingsAreNPESafe()
    {
    	assertNotNull(cf.getExcludeProtocols());
    	assertNotNull(cf.getIncludeProtocols());
    	assertNotNull(cf.getExcludeCipherSuites());
    	assertNotNull(cf.getIncludeCipherSuites());
    }
    
    private void assertSelectedMatchesIncluded(String[] includeStrings, String[] selectedStrings)
    {
        assertThat(includeStrings.length + " strings are selected", selectedStrings.length, is(includeStrings.length));
        assertThat("order from includeStrings is preserved", selectedStrings[0], equalTo(includeStrings[0]));
        assertThat("order from includeStrings is preserved", selectedStrings[1], equalTo(includeStrings[1]));
        assertThat("order from includeStrings is preserved", selectedStrings[2], equalTo(includeStrings[2]));
    }
}
