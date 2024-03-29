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

package org.eclipse.jetty.test.keystore;

import java.io.File;
import java.nio.file.Path;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.keystore.KeystoreGenerator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class KeystoreGeneratorTest
{
    private Server _server;
    private ServerConnector _connector;
    private HttpClient _httpClient;

    public KeystoreGeneratorTest()
    {
    }

    @BeforeEach
    public void before(WorkDir workDir) throws Exception
    {
        Path tmpPath = workDir.getEmptyPathDir();
        // Generate a test keystore.
        String password = "myKeystorePassword";
        Path outputFile = tmpPath.resolve("keystore-test.p12");
        File myPassword = KeystoreGenerator.generateTestKeystore(outputFile.toString(), password);
        assertTrue(myPassword.exists());

        // Configure the SslContextFactory and HttpConnectionFactory to use the keystore.
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(myPassword.getAbsolutePath());
        sslContextFactory.setKeyStorePassword(password);
        SslConnectionFactory sslConnectionFactory = new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString());
        HttpConfiguration httpsConfig = new HttpConfiguration();
        SecureRequestCustomizer secureRequestCustomizer = new SecureRequestCustomizer();
        secureRequestCustomizer.setSniHostCheck(false);
        httpsConfig.addCustomizer(secureRequestCustomizer);
        HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(httpsConfig);

        // Start the server.
        _server = new Server();
        _connector = new ServerConnector(_server, sslConnectionFactory, httpConnectionFactory);
        _server.addConnector(_connector);
        _server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.write(true, BufferUtil.toBuffer("success"), callback);
                return true;
            }
        });
        _server.start();

        // Configure the client.
        SslContextFactory.Client clientSslContextFactory = new SslContextFactory.Client();
        clientSslContextFactory.setTrustAll(true);
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(clientSslContextFactory);
        _httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector));
        _httpClient.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _httpClient.stop();
        _server.stop();
    }

    @Test
    public void test() throws Exception
    {
        ContentResponse response = _httpClient.GET("https://localhost:" + _connector.getLocalPort());
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), equalTo("success"));
    }
}
