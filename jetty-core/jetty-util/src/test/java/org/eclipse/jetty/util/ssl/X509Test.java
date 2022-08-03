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

import java.nio.file.Path;
import java.security.cert.X509Certificate;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

public class X509Test
{
    @BeforeEach
    public void beforeEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @AfterEach
    public void afterEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

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
    public void testServerClassWithSni() throws Exception
    {
        SslContextFactory serverSsl = new SslContextFactory.Server();
        Path keystorePath = MavenTestingUtils.getTestResourcePathFile("keystore_sni.p12");
        serverSsl.setKeyStoreResource(ResourceFactory.ROOT.newResource(keystorePath));
        serverSsl.setKeyStorePassword("storepwd");
        serverSsl.start();
    }

    @Test
    public void testClientClassWithSni() throws Exception
    {
        SslContextFactory clientSsl = new SslContextFactory.Client();
        Path keystorePath = MavenTestingUtils.getTestResourcePathFile("keystore_sni.p12");
        clientSsl.setKeyStoreResource(ResourceFactory.ROOT.newResource(keystorePath));
        clientSsl.setKeyStorePassword("storepwd");
        clientSsl.start();
    }

    @Test
    public void testServerClassWithoutSni() throws Exception
    {
        SslContextFactory serverSsl = new SslContextFactory.Server();
        Resource keystoreResource = ResourceFactory.ROOT.newSystemResource("keystore.p12");
        serverSsl.setKeyStoreResource(keystoreResource);
        serverSsl.setKeyStorePassword("storepwd");
        serverSsl.start();
    }

    @Test
    public void testClientClassWithoutSni() throws Exception
    {
        SslContextFactory clientSsl = new SslContextFactory.Client();
        Resource keystoreResource = ResourceFactory.ROOT.newSystemResource("keystore.p12");
        clientSsl.setKeyStoreResource(keystoreResource);
        clientSsl.setKeyStorePassword("storepwd");
        clientSsl.start();
    }
}
