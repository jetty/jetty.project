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

package org.eclipse.jetty.test.client.transport;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.ProxyProtocolClientConnectionFactory;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.proxy.ProxyHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProxyWithProxyProtocolTest
{
    private Server server;
    private ServerConnector serverConnector;
    private Server proxy;
    private ServerConnector proxyConnector;
    private HttpClient client;

    private void start(Handler handler) throws Exception
    {
        server = new Server();
        serverConnector = new ServerConnector(server, 1, 1, new ProxyConnectionFactory(), new HttpConnectionFactory());
        server.addConnector(serverConnector);
        server.setHandler(handler);
        server.start();

        SslContextFactory.Server proxyTLS = new SslContextFactory.Server();
        proxyTLS.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12"));
        proxyTLS.setKeyStorePassword("storepwd");
        proxy = new Server();
        proxyConnector = new ServerConnector(proxy, 1, 1, proxyTLS);
        proxy.addConnector(proxyConnector);
        proxy.setHandler(new ProxyHandler.Reverse(request -> HttpURI.from("http://localhost:" + serverConnector.getLocalPort()))
        {
            @Override
            protected org.eclipse.jetty.client.Request newProxyToServerRequest(Request clientToProxyRequest, HttpURI newHttpURI)
            {
                var request = super.newProxyToServerRequest(clientToProxyRequest, newHttpURI);
                EndPoint clientToProxyEndPoint = clientToProxyRequest.getConnectionMetaData().getConnection().getEndPoint();
                // Forward the remote client information to the server.
                return request.tag(ProxyProtocolClientConnectionFactory.V2.Tag.from(clientToProxyEndPoint, false));
            }
        });
        proxy.start();

        SslContextFactory.Client clientTLS = new SslContextFactory.Client(true);
        client = new HttpClient();
        client.setSslContextFactory(clientTLS);
        client.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(proxy);
        LifeCycle.stop(server);
    }

    @Test
    public void testProxyWithProxyProtocol() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                assertTrue(request.isSecure());
                callback.succeeded();
                return true;
            }
        });

        ContentResponse response = client.newRequest("https://localhost:" + proxyConnector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }
}
