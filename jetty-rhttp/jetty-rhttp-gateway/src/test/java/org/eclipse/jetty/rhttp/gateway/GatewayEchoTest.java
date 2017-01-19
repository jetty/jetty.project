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

import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.io.ByteArrayBuffer;

/**
 * @version $Revision$ $Date$
 */
public class GatewayEchoTest extends TestCase
{
    /**
     * Tests that the basic functionality of the gateway works,
     * by issuing a request and by replying with the same body.
     *
     * @throws Exception in case of test exceptions
     */
    public void testEcho() throws Exception
    {
        GatewayEchoServer server = new GatewayEchoServer();
        server.start();
        try
        {
            HttpClient httpClient = new HttpClient();
            httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
            httpClient.start();
            try
            {
                // Make a request to the gateway and check response
                ContentExchange exchange = new ContentExchange(true);
                exchange.setMethod(HttpMethods.POST);
                exchange.setAddress(server.getAddress());
                exchange.setURI(server.getURI() + "/");
                String requestBody = "body";
                exchange.setRequestContent(new ByteArrayBuffer(requestBody.getBytes("UTF-8")));
                httpClient.send(exchange);
                int status = exchange.waitForDone();
                assertEquals(HttpExchange.STATUS_COMPLETED, status);
                assertEquals(HttpServletResponse.SC_OK, exchange.getResponseStatus());
                String responseContent = exchange.getResponseContent();
                assertEquals(responseContent, requestBody);
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
