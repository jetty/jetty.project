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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLHandshakeException;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BrokenServerNegotiationTest
{
    @Test
    public void testAlpnNegociationWithBrokenServer() throws Exception
    {
        AtomicReference<ServerSocket> serverSocketRef = new AtomicReference<>();
        AtomicInteger port = new AtomicInteger();
        Thread server = new Thread(() ->
        {
            try
            {
                ServerSocket serverSocket = new ServerSocket(0);
                serverSocketRef.set(serverSocket);
                port.set(serverSocket.getLocalPort());
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
        await().atMost(5, TimeUnit.SECONDS).until(port::get, greaterThan(0));

        ClientConnector clientConnector = new ClientConnector();
        HTTP2Client h2Client = new HTTP2Client();
        ClientConnectionFactoryOverHTTP2.HTTP2 http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(h2Client);
        ClientConnectionFactory.Info http1 = HttpClientConnectionFactory.HTTP11;
        HttpClientTransportDynamic transportDynamic = new HttpClientTransportDynamic(clientConnector, http1,  http2);
        HttpClient client = new HttpClient(transportDynamic);
        client.start();

        ExecutionException ee = assertThrows(ExecutionException.class, () -> client.GET("https://localhost:" + port.get() + "/hello"));
        assertInstanceOf(SSLHandshakeException.class, ee.getCause());

        LifeCycle.stop(client);
        serverSocketRef.get().close();
    }
}
