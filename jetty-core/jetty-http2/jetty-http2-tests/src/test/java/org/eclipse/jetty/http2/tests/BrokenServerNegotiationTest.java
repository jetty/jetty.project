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

package org.eclipse.jetty.http2.tests;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLHandshakeException;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BrokenServerNegotiationTest
{
    private volatile ServerSocket serverSocket;

    public int startServer() throws Exception
    {
        if (serverSocket != null)
            return serverSocket.getLocalPort();
        Thread server = new Thread(() ->
        {
            try
            {
                serverSocket = new ServerSocket(0);
                while (true)
                {
                    Socket accept = serverSocket.accept();
                    accept.close();
                }
            }
            catch (Exception e)
            {
                // ignore, time to shut down
            }
        });
        server.start();
        await().atMost(5, TimeUnit.SECONDS).until(() -> serverSocket, notNullValue());
        return serverSocket.getLocalPort();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        if (serverSocket != null)
            serverSocket.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testAlpnNegotiation(boolean useAlpn) throws Exception
    {
        int port = startServer();
        ClientConnector clientConnector = new ClientConnector();
        HTTP2Client h2Client = new HTTP2Client();
        h2Client.setUseALPN(useAlpn);
        ClientConnectionFactoryOverHTTP2.HTTP2 http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(h2Client);
        ClientConnectionFactory.Info http1 = HttpClientConnectionFactory.HTTP11;
        HttpClientTransportDynamic transportDynamic = new HttpClientTransportDynamic(clientConnector, http1,  http2);
        HttpClient client = new HttpClient(transportDynamic);
        client.start();

        ExecutionException ee = assertThrows(ExecutionException.class, () -> client.GET("https://localhost:" + port + "/hello"));
        assertInstanceOf(SSLHandshakeException.class, ee.getCause());

        LifeCycle.stop(client);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testH2Only(boolean useAlpn) throws Exception
    {
        int port = startServer();
        HTTP2Client h2Client = new HTTP2Client();
        h2Client.setUseALPN(useAlpn);
        HttpClientTransportOverHTTP2 httpClientTransportOverHTTP2 = new HttpClientTransportOverHTTP2(h2Client);
        HttpClient client = new HttpClient(httpClientTransportOverHTTP2);
        client.start();

        ExecutionException ee = assertThrows(ExecutionException.class, () -> client.GET("https://localhost:" + port + "/hello"));
        assertInstanceOf(SSLHandshakeException.class, ee.getCause());

        LifeCycle.stop(client);
    }
}
