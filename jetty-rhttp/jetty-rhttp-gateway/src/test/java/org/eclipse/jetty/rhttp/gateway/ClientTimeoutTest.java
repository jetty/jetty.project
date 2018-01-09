//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.rhttp.gateway;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.TestCase;

import org.eclipse.jetty.client.Address;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.rhttp.client.ClientListener;
import org.eclipse.jetty.rhttp.client.JettyClient;
import org.eclipse.jetty.rhttp.client.RHTTPClient;
import org.eclipse.jetty.rhttp.gateway.GatewayServer;
import org.eclipse.jetty.rhttp.gateway.StandardGateway;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.nio.SelectChannelConnector;


/**
 * @version $Revision$ $Date$
 */
public class ClientTimeoutTest extends TestCase
{
    public void testClientTimeout() throws Exception
    {
        GatewayServer server = new GatewayServer();
        Connector connector = new SelectChannelConnector();
        server.addConnector(connector);
        final long clientTimeout = 2000L;
        server.getConnectorServlet().setInitParameter("clientTimeout",""+clientTimeout);
        final long gatewayTimeout = 4000L;
        ((StandardGateway)server.getGateway()).setGatewayTimeout(gatewayTimeout);
        server.start();
        try
        {
            Address address = new Address("localhost", connector.getLocalPort());

            HttpClient httpClient = new HttpClient();
            httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
            httpClient.start();
            try
            {
                String targetId = "1";
                final RHTTPClient client = new JettyClient(httpClient, address, server.getContext().getContextPath()+GatewayServer.DFT_CONNECT_PATH, targetId)
                {
                    private final AtomicInteger connects = new AtomicInteger();

                    @Override
                    protected void asyncConnect()
                    {
                        if (connects.incrementAndGet() == 2)
                        {
                            try
                            {
                                // Wait here instead of connecting, so that the client expires on the server
                                Thread.sleep(clientTimeout * 2);
                            }
                            catch (InterruptedException x)
                            {
                                throw new RuntimeException(x);
                            }
                        }
                        super.asyncConnect();
                    }
                };

                final CountDownLatch connectLatch = new CountDownLatch(1);
                client.addClientListener(new ClientListener.Adapter()
                {
                    @Override
                    public void connectRequired()
                    {
                        connectLatch.countDown();
                    }
                });
                client.connect();
                try
                {
                    assertTrue(connectLatch.await(gatewayTimeout + clientTimeout * 3, TimeUnit.MILLISECONDS));
                }
                finally
                {
                    client.disconnect();
                }
            }
            finally
            {
                httpClient.stop();
            }
        }
        finally
        {
            server.stop();
        }
    }
}
