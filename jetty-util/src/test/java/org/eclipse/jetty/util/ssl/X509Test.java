//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class X509Test
{
    @Test
    public void testIsCertSignNormal()
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
    public void testIsCertSignNormalNoSupported()
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
    public void testIsCertSignNonStandardShort()
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
    public void testIsCertSignNonStandardShorter()
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
    public void testIsCertSignNormalNull()
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
    public void testIsCertSignNormalEmpty()
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

    @Test
    public void testBaseClassWithSni()
    {
        SslContextFactory baseSsl = new SslContextFactory();
        Path keystorePath = MavenTestingUtils.getTestResourcePathFile("keystore_sni.p12");
        baseSsl.setKeyStoreResource(new PathResource(keystorePath));
        baseSsl.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        baseSsl.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
        IllegalStateException ex = assertThrows(IllegalStateException.class, baseSsl::start);
        assertThat("IllegalStateException.message", ex.getMessage(), containsString("KeyStores with multiple certificates are not supported on the base class"));
    }

    @Test
    public void testServerClassWithSni() throws Exception
    {
        SslContextFactory serverSsl = new SslContextFactory.Server();
        Path keystorePath = MavenTestingUtils.getTestResourcePathFile("keystore_sni.p12");
        serverSsl.setKeyStoreResource(new PathResource(keystorePath));
        serverSsl.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        serverSsl.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
        serverSsl.start();
    }

    @Test
    public void testClientClassWithSni() throws Exception
    {
        SslContextFactory clientSsl = new SslContextFactory.Client();
        Path keystorePath = MavenTestingUtils.getTestResourcePathFile("keystore_sni.p12");
        clientSsl.setKeyStoreResource(new PathResource(keystorePath));
        clientSsl.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        clientSsl.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
        clientSsl.start();
    }

    @Test
    public void testBaseClassWithoutSni() throws Exception
    {
        SslContextFactory baseSsl = new SslContextFactory();
        Resource keystoreResource = Resource.newSystemResource("keystore");
        baseSsl.setKeyStoreResource(keystoreResource);
        baseSsl.setKeyStorePassword("storepwd");
        baseSsl.setKeyManagerPassword("keypwd");
        baseSsl.start();
    }

    @Test
    public void testServerClassWithoutSni() throws Exception
    {
        SslContextFactory serverSsl = new SslContextFactory.Server();
        Resource keystoreResource = Resource.newSystemResource("keystore");
        serverSsl.setKeyStoreResource(keystoreResource);
        serverSsl.setKeyStorePassword("storepwd");
        serverSsl.setKeyManagerPassword("keypwd");
        serverSsl.start();
    }

    @Test
    public void testClientClassWithoutSni() throws Exception
    {
        SslContextFactory clientSsl = new SslContextFactory.Client();
        Resource keystoreResource = Resource.newSystemResource("keystore");
        clientSsl.setKeyStoreResource(keystoreResource);
        clientSsl.setKeyStorePassword("storepwd");
        clientSsl.setKeyManagerPassword("keypwd");
        clientSsl.start();
    }
}
