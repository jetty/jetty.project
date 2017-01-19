//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import junit.framework.TestCase;

import org.eclipse.jetty.client.Address;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.rhttp.client.JettyClient;
import org.eclipse.jetty.rhttp.client.RHTTPClient;
import org.eclipse.jetty.rhttp.gateway.GatewayServer;
import org.eclipse.jetty.rhttp.gateway.StandardGateway;
import org.eclipse.jetty.server.nio.SelectChannelConnector;


/**
 * @version $Revision$ $Date$
 */
public class HandshakeClientTest extends TestCase
{
    public void testConnectReturnsImmediately() throws Exception
    {
        GatewayServer server = new GatewayServer();
        SelectChannelConnector connector = new SelectChannelConnector();
        server.addConnector(connector);
        long gwt=5000L;
        ((StandardGateway)server.getGateway()).setGatewayTimeout(gwt);
        server.start();
        try
        {
            HttpClient httpClient = new HttpClient();
            httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
            httpClient.start();
            try
            {
                RHTTPClient client = new JettyClient(httpClient, new Address("localhost", connector.getLocalPort()), server.getContext().getContextPath()+GatewayServer.DFT_CONNECT_PATH, "test1");
                long start = System.currentTimeMillis();
                client.connect();
                try
                {
                    long end = System.currentTimeMillis();
                    assertTrue(end - start < gwt / 2);
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
