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
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletRequest;
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
import org.eclipse.jetty.rhttp.gateway.ExternalRequest;
import org.eclipse.jetty.rhttp.gateway.Gateway;
import org.eclipse.jetty.rhttp.gateway.GatewayServer;
import org.eclipse.jetty.rhttp.gateway.StandardGateway;
import org.eclipse.jetty.server.nio.SelectChannelConnector;


/**
 * @version $Revision$ $Date$
 */
public class ExternalRequestNotSuspendedTest extends TestCase
{
    public void testExternalRequestNotSuspended() throws Exception
    {
        final CountDownLatch respondLatch = new CountDownLatch(1);
        final CountDownLatch suspendLatch = new CountDownLatch(1);
        final AtomicBoolean suspended = new AtomicBoolean(true);
        GatewayServer server = new GatewayServer()
        {
            @Override
            protected Gateway createGateway()
            {
                StandardGateway gateway = new StandardGateway()
                {
                    @Override
                    public ExternalRequest newExternalRequest(HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException
                    {
                        return new SlowToSuspendExternalRequest(super.newExternalRequest(httpRequest, httpResponse), respondLatch, suspendLatch, suspended);
                    }
                };
                return gateway;
            }
        };
        SelectChannelConnector connector = new SelectChannelConnector();
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
                String targetId = "1";
                final RHTTPClient client = new JettyClient(httpClient, address, server.getContext().getContextPath()+GatewayServer.DFT_CONNECT_PATH, targetId);
                final AtomicReference<Exception> exception = new AtomicReference<Exception>();
                client.addListener(new RHTTPListener()
                {
                    public void onRequest(RHTTPRequest request)
                    {
                        try
                        {
                            RHTTPResponse response = new RHTTPResponse(request.getId(), 200, "OK", new HashMap<String, String>(), request.getBody());
                            client.deliver(response);
                        }
                        catch (Exception x)
                        {
                            exception.set(x);
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
                    assertNull(exception.get());

                    suspendLatch.await();
                    assertFalse(suspended.get());
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

    private class SlowToSuspendExternalRequest implements ExternalRequest
    {
        private final ExternalRequest delegate;
        private final CountDownLatch respondLatch;
        private final CountDownLatch suspendLatch;
        private final AtomicBoolean suspended;

        private SlowToSuspendExternalRequest(ExternalRequest delegate, CountDownLatch respondLatch, CountDownLatch suspendLatch, AtomicBoolean suspended)
        {
            this.delegate = delegate;
            this.respondLatch = respondLatch;
            this.suspendLatch = suspendLatch;
            this.suspended = suspended;
        }

        public boolean suspend()
        {
            try
            {
                respondLatch.await();
                boolean result = delegate.suspend();
                suspended.set(result);
                suspendLatch.countDown();
                return result;
            }
            catch (InterruptedException x)
            {
                throw new AssertionError(x);
            }
        }

        public void respond(RHTTPResponse response) throws IOException
        {
            delegate.respond(response);
            respondLatch.countDown();
        }

        public RHTTPRequest getRequest()
        {
            return delegate.getRequest();
        }
    }
}
