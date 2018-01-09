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

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.eclipse.jetty.client.Address;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.rhttp.client.JettyClient;
import org.eclipse.jetty.rhttp.client.RHTTPClient;
import org.eclipse.jetty.rhttp.gateway.GatewayServer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.nio.SelectChannelConnector;


/**
 * @version $Revision$ $Date$
 */
public class DisconnectClientTest extends TestCase
{
    public void testDifferentClientDisconnects() throws Exception
    {
        GatewayServer server = new GatewayServer();
        Connector connector = new SelectChannelConnector();
        server.addConnector(connector);
        server.start();
        try
        {
            Address address = new Address("localhost", connector.getLocalPort());

            HttpClient httpClient = new HttpClient();
            httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
            httpClient.start();
            try
            {
                final CountDownLatch latch = new CountDownLatch(1);
                String targetId = "1";
                final RHTTPClient client1 = new JettyClient(httpClient, address, server.getContext().getContextPath()+GatewayServer.DFT_CONNECT_PATH, targetId)
                {
                    @Override
                    protected void connectComplete(byte[] responseContent) throws IOException
                    {
                        // If the other client can disconnect this one, this method is called soon after it disconnected
                        latch.countDown();
                        super.connectComplete(responseContent);
                    }
                };
                client1.connect();
                try
                {
                    final RHTTPClient client2 = new JettyClient(httpClient, address, server.getContext().getContextPath()+GatewayServer.DFT_CONNECT_PATH, targetId);
                    // Disconnect client 2, this should not disconnect client1
                    client2.disconnect();

                    // We want the await() to expire, it means it has not disconnected
                    assertFalse(latch.await(1000, TimeUnit.MILLISECONDS));
                }
                finally
                {
                    client1.disconnect();
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
