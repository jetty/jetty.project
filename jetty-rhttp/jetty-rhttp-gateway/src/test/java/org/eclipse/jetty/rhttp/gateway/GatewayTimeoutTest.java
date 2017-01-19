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

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.client.Address;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.rhttp.client.JettyClient;
import org.eclipse.jetty.rhttp.client.RHTTPClient;
import org.eclipse.jetty.rhttp.client.RHTTPListener;
import org.eclipse.jetty.rhttp.client.RHTTPRequest;
import org.eclipse.jetty.rhttp.client.RHTTPResponse;
import org.eclipse.jetty.rhttp.gateway.GatewayServer;
import org.eclipse.jetty.rhttp.gateway.StandardGateway;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.nio.SelectChannelConnector;


/**
 * @version $Revision$ $Date$
 */
public class GatewayTimeoutTest extends TestCase
{
    /**
     * Tests a forwarded request that lasts longer than the gateway timeout.
     * The gateway client will perform 2 long polls before the forwarded request's response is returned.
     *
     * @throws Exception in case of test exceptions
     */
    public void testGatewayTimeout() throws Exception
    {
        GatewayServer server = new GatewayServer();
        Connector connector = new SelectChannelConnector();
        server.addConnector(connector);
        final long gatewayTimeout = 5000L;
        ((StandardGateway)server.getGateway()).setGatewayTimeout(gatewayTimeout);
        final long externalTimeout = gatewayTimeout * 2;
        ((StandardGateway)server.getGateway()).setExternalTimeout(externalTimeout);
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
                final RHTTPClient client = new JettyClient(httpClient, address, server.getContext().getContextPath()+GatewayServer.DFT_CONNECT_PATH, targetId);
                final AtomicReference<Exception> exceptionRef = new AtomicReference<Exception>();
                client.addListener(new RHTTPListener()
                {
                    public void onRequest(RHTTPRequest request)
                    {
                        try
                        {
                            // Wait until gateway timeout expires
                            Thread.sleep(gatewayTimeout + 1000L);
                            RHTTPResponse response = new RHTTPResponse(request.getId(), 200, "OK", new HashMap<String, String>(), request.getBody());
                            client.deliver(response);
                        }
                        catch (Exception x)
                        {
                            exceptionRef.set(x);
                        }
                    }
                });
                client.connect();
                try
                {
                    // Make a request to the gateway and check response
                    ContentExchange exchange = new ContentExchange(true);
                    exchange.setMethod(HttpMethods.POST);
                    exchange.setAddress(address);
                    exchange.setURI(server.getContext().getContextPath()+GatewayServer.DFT_EXT_PATH + "/" + URLEncoder.encode(targetId, "UTF-8"));
                    String requestContent = "body";
                    exchange.setRequestContent(new ByteArrayBuffer(requestContent.getBytes("UTF-8")));
                    httpClient.send(exchange);

                    int status = exchange.waitForDone();
                    assertEquals(HttpExchange.STATUS_COMPLETED, status);
                    assertEquals(HttpServletResponse.SC_OK, exchange.getResponseStatus());
                    assertNull(exceptionRef.get());
                    String responseContent = exchange.getResponseContent();
                    assertEquals(responseContent, requestContent);
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
