//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManager;
import javax.net.ssl.X509ExtendedKeyManager;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class X509Test
{
    @Test
    public void testIsCertSign_Normal()
    {
        X509Certificate bogusX509 = new X509CertificateAdapter()
        {
            @Override
            public boolean[] getKeyUsage()
            {
                boolean[] keyUsage = new boolean[8];
                keyUsage[5] = true;
                return keyUsage;
            }
        };

        assertThat("Normal X509", X509.isCertSign(bogusX509), is(true));
    }

    @Test
    public void testIsCertSign_Normal_NoSupported()
    {
        X509Certificate bogusX509 = new X509CertificateAdapter()
        {
            @Override
            public boolean[] getKeyUsage()
            {
                boolean[] keyUsage = new boolean[8];
                keyUsage[5] = false;
                return keyUsage;
            }
        };

        assertThat("Normal X509", X509.isCertSign(bogusX509), is(false));
    }

    @Test
    public void testIsCertSign_NonStandard_Short()
    {
        X509Certificate bogusX509 = new X509CertificateAdapter()
        {
            @Override
            public boolean[] getKeyUsage()
            {
                boolean[] keyUsage = new boolean[6]; // at threshold
                keyUsage[5] = true;
                return keyUsage;
            }
        };

        assertThat("NonStandard X509", X509.isCertSign(bogusX509), is(true));
    }

    @Test
    public void testIsCertSign_NonStandard_Shorter()
    {
        X509Certificate bogusX509 = new X509CertificateAdapter()
        {
            @Override
            public boolean[] getKeyUsage()
            {
                boolean[] keyUsage = new boolean[5]; // just below threshold
                return keyUsage;
            }
        };

        assertThat("NonStandard X509", X509.isCertSign(bogusX509), is(false));
    }

    @Test
    public void testIsCertSign_Normal_Null()
    {
        X509Certificate bogusX509 = new X509CertificateAdapter()
        {
            @Override
            public boolean[] getKeyUsage()
            {
                return null;
            }
        };

        assertThat("Normal X509", X509.isCertSign(bogusX509), is(false));
    }

    @Test
    public void testIsCertSign_Normal_Empty()
    {
        X509Certificate bogusX509 = new X509CertificateAdapter()
        {
            @Override
            public boolean[] getKeyUsage()
            {
                return new boolean[0];
            }
        };

        assertThat("Normal X509", X509.isCertSign(bogusX509), is(false));
    }

    private X509ExtendedKeyManager getX509ExtendedKeyManager(SslContextFactory sslContextFactory) throws Exception
    {
        Resource keystoreResource = Resource.newSystemResource("keystore");
        Resource truststoreResource = Resource.newSystemResource("keystore");
        sslContextFactory.setKeyStoreResource(keystoreResource);
        sslContextFactory.setTrustStoreResource(truststoreResource);
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");
        sslContextFactory.setTrustStorePassword("storepwd");
        sslContextFactory.start();

        KeyManager[] keyManagers = sslContextFactory.getKeyManagers(sslContextFactory.getKeyStore());
        X509ExtendedKeyManager x509ExtendedKeyManager = null;

        for (KeyManager keyManager : keyManagers)
        {
            if (keyManager instanceof X509ExtendedKeyManager)
            {
                x509ExtendedKeyManager = (X509ExtendedKeyManager)keyManager;
                break;
            }
        }
        assertThat("Found X509ExtendedKeyManager", x509ExtendedKeyManager, is(notNullValue()));
        return x509ExtendedKeyManager;
    }

    @Test
    public void testSniX509ExtendedKeyManager_BaseClass() throws Exception
    {
        SslContextFactory baseSsl = new SslContextFactory();
        X509ExtendedKeyManager x509ExtendedKeyManager = getX509ExtendedKeyManager(baseSsl);
        UnsupportedOperationException npe = assertThrows(UnsupportedOperationException.class, () -> baseSsl.newSniX509ExtendedKeyManager(x509ExtendedKeyManager));
        assertThat("UnsupportedOperationException.message", npe.getMessage(), containsString("X509ExtendedKeyManager only supported on " + SslContextFactory.Server.class.getName()));
    }

    @Test
    public void testSniX509ExtendedKeyManager_BaseClass_Start() throws Exception
    {
        SslContextFactory baseSsl = new SslContextFactory();
        Path keystorePath = MavenTestingUtils.getTestResourcePathFile("keystore_sni.p12");
        baseSsl.setKeyStoreResource(new PathResource(keystorePath));
        baseSsl.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        baseSsl.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
        baseSsl.start(); // should not throw an exception
    }

    @Test
    public void testSniX509ExtendedKeyManager_ClientClass() throws Exception
    {
        SslContextFactory clientSsl = new SslContextFactory.Client();
        X509ExtendedKeyManager x509ExtendedKeyManager = getX509ExtendedKeyManager(clientSsl);
        X509ExtendedKeyManager sniX509ExtendedKeyManager = clientSsl.newSniX509ExtendedKeyManager(x509ExtendedKeyManager);
        assertThat("SNI X509 ExtendedKeyManager is undefined in Client mode", sniX509ExtendedKeyManager, is(x509ExtendedKeyManager));
    }

    @Test
    public void testSniX509ExtendedKeyManager_ServerClass() throws Exception
    {
        SslContextFactory serverSsl = new SslContextFactory.Server();
        X509ExtendedKeyManager x509ExtendedKeyManager = getX509ExtendedKeyManager(serverSsl);
        serverSsl.newSniX509ExtendedKeyManager(x509ExtendedKeyManager);
    }
}
