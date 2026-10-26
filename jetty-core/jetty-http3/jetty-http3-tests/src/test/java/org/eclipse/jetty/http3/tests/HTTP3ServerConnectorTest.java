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

package org.eclipse.jetty.http3.tests;

import java.io.InputStream;
import java.security.KeyStore;
import java.util.Locale;
import javax.net.ssl.SSLContext;

import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerConnector;
import org.eclipse.jetty.quic.server.ServerQuicConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(WorkDirExtension.class)
// Isolated because the default Locale is changed.
@Isolated
public class HTTP3ServerConnectorTest
{
    public WorkDir workDir;

    @Test
    public void testStartHTTP3ServerConnectorWithoutKeyStore()
    {
        Server server = new Server();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        ServerQuicConfiguration quicConfiguration = new ServerQuicConfiguration(sslContextFactory, workDir.getEmptyPathDir());
        HTTP3ServerConnector connector = new HTTP3ServerConnector(server, sslContextFactory, new HTTP3ServerConnectionFactory(quicConfiguration));
        server.addConnector(connector);
        assertThrows(IllegalStateException.class, server::start);
    }

    @Test
    public void testStartHTTP3ServerConnectorWithoutKeyStoreWithSSLContext() throws Exception
    {
        Server server = new Server();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setSslContext(SSLContext.getDefault());
        ServerQuicConfiguration quicConfiguration = new ServerQuicConfiguration(sslContextFactory, workDir.getEmptyPathDir());
        HTTP3ServerConnector connector = new HTTP3ServerConnector(server, sslContextFactory, new HTTP3ServerConnectionFactory(quicConfiguration));
        server.addConnector(connector);
        assertThrows(IllegalStateException.class, server::start);
    }

    @Test
    public void testStartHTTP3ServerConnectorWithEmptyKeyStoreInstance() throws Exception
    {
        Server server = new Server();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        sslContextFactory.setKeyStore(keyStore);
        ServerQuicConfiguration quicConfiguration = new ServerQuicConfiguration(sslContextFactory, workDir.getEmptyPathDir());
        HTTP3ServerConnector connector = new HTTP3ServerConnector(server, sslContextFactory, new HTTP3ServerConnectionFactory(quicConfiguration));
        server.addConnector(connector);
        assertThrows(IllegalStateException.class, server::start);
    }

    @Test
    public void testStartHTTP3ServerConnectorWithValidKeyStoreInstanceWithoutPemWorkDir() throws Exception
    {
        Server server = new Server();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = getClass().getResourceAsStream("/keystore.p12"))
        {
            keyStore.load(is, "storepwd".toCharArray());
        }
        sslContextFactory.setKeyStore(keyStore);
        sslContextFactory.setKeyManagerPassword("storepwd");
        ServerQuicConfiguration quicConfiguration = new ServerQuicConfiguration(sslContextFactory, null);
        HTTP3ServerConnector connector = new HTTP3ServerConnector(server, sslContextFactory, new HTTP3ServerConnectionFactory(quicConfiguration));
        server.addConnector(connector);
        assertThrows(IllegalStateException.class, server::start);
    }

    @Test
    public void testStartHTTP3ServerConnectorWithValidKeyStoreInstance() throws Exception
    {
        Server server = new Server();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = getClass().getResourceAsStream("/keystore.p12"))
        {
            keyStore.load(is, "storepwd".toCharArray());
        }
        sslContextFactory.setKeyStore(keyStore);
        sslContextFactory.setKeyManagerPassword("storepwd");
        ServerQuicConfiguration quicConfiguration = new ServerQuicConfiguration(sslContextFactory, workDir.getEmptyPathDir());
        HTTP3ServerConnector connector = new HTTP3ServerConnector(server, sslContextFactory, new HTTP3ServerConnectionFactory(quicConfiguration));
        server.addConnector(connector);
        try
        {
            server.start();
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "ar-EG-u-nu-arab"})
    public void testAltSvcHttpFieldDoesNotDependOnDefaultLocale(String languageTag) throws Exception
    {
        Server server = new Server();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = getClass().getResourceAsStream("/keystore.p12"))
        {
            keyStore.load(is, "storepwd".toCharArray());
        }
        sslContextFactory.setKeyStore(keyStore);
        sslContextFactory.setKeyManagerPassword("storepwd");
        ServerQuicConfiguration quicConfiguration = new ServerQuicConfiguration(sslContextFactory, workDir.getEmptyPathDir());
        HTTP3ServerConnector connector = new HTTP3ServerConnector(server, sslContextFactory, new HTTP3ServerConnectionFactory(quicConfiguration));
        server.addConnector(connector);

        // The Arabic-Egyptian locale with Arabic digits does not format numbers using the ascii
        // characters 0-9, if it was used the port would be formatted as h3=":٤٥٦٧٨" for instance.
        Locale defaultLocale = Locale.getDefault();
        try
        {
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            server.start();
            assertEquals("h3=\":" + connector.getLocalPort() + "\"", connector.getAltSvcHttpField().getValue());
        }
        finally
        {
            Locale.setDefault(defaultLocale);
            LifeCycle.stop(server);
        }
    }
}
