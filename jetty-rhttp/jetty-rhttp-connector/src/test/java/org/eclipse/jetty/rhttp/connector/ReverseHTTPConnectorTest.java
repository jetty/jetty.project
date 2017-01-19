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

package org.eclipse.jetty.rhttp.connector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.rhttp.client.ClientListener;
import org.eclipse.jetty.rhttp.client.RHTTPClient;
import org.eclipse.jetty.rhttp.client.RHTTPListener;
import org.eclipse.jetty.rhttp.client.RHTTPRequest;
import org.eclipse.jetty.rhttp.client.RHTTPResponse;
import org.eclipse.jetty.rhttp.connector.ReverseHTTPConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

/**
 * @version $Revision$ $Date$
 */
public class ReverseHTTPConnectorTest extends TestCase
{
    public void testGatewayConnectorWithoutRequestBody() throws Exception
    {
        testGatewayConnector(false);
    }

    public void testGatewayConnectorWithRequestBody() throws Exception
    {
        testGatewayConnector(true);
    }

    private void testGatewayConnector(boolean withRequestBody) throws Exception
    {
        Server server = new Server();
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        CountDownLatch clientLatch = new CountDownLatch(1);
        AtomicReference<RHTTPResponse> responseRef = new AtomicReference<RHTTPResponse>();
        ReverseHTTPConnector connector = new ReverseHTTPConnector(new TestClient(clientLatch, responseRef));
        server.addConnector(connector);
        final String method = "POST";
        final String uri = "/test";
        final byte[] requestBody = withRequestBody ? "REQUEST-BODY".getBytes("UTF-8") : new byte[0];
        final int statusCode = HttpServletResponse.SC_CREATED;
        final String headerName = "foo";
        final String headerValue = "bar";
        final byte[] responseBody = "RESPONSE-BODY".getBytes("UTF-8");
        server.setHandler(new AbstractHandler()
        {
            public void handle(String pathInfo, org.eclipse.jetty.server.Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
            {
                assertEquals(method, httpRequest.getMethod());
                assertEquals(uri, httpRequest.getRequestURI());
                assertEquals(headerValue, httpRequest.getHeader(headerName));
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                InputStream input = httpRequest.getInputStream();
                int read;
                while ((read = input.read()) >= 0)
                    baos.write(read);
                baos.close();
                assertTrue(Arrays.equals(requestBody, baos.toByteArray()));

                httpResponse.setStatus(statusCode);
                httpResponse.setHeader(headerName, headerValue);
                OutputStream output = httpResponse.getOutputStream();
                output.write(responseBody);
                output.flush();
                request.setHandled(true);
                handlerLatch.countDown();
            }
        });
        server.start();

        HashMap<String, String> headers = new HashMap<String, String>();
        headers.put("Host", "localhost");
        headers.put(headerName, headerValue);
        headers.put("Content-Length", String.valueOf(requestBody.length));
        RHTTPRequest request = new RHTTPRequest(1, method, uri, headers, requestBody);
        request = RHTTPRequest.fromRequestBytes(request.getId(), request.getRequestBytes());
        connector.onRequest(request);

        assertTrue(handlerLatch.await(1000, TimeUnit.MILLISECONDS));
        assertTrue(clientLatch.await(1000, TimeUnit.MILLISECONDS));
        RHTTPResponse response = responseRef.get();
        assertEquals(request.getId(), response.getId());
        assertEquals(statusCode, response.getStatusCode());
        assertEquals(headerValue, response.getHeaders().get(headerName));
        assertTrue(Arrays.equals(response.getBody(), responseBody));
    }

    private class TestClient implements RHTTPClient
    {
        private final CountDownLatch latch;
        private final AtomicReference<RHTTPResponse> responseRef;

        private TestClient(CountDownLatch latch, AtomicReference<RHTTPResponse> response)
        {
            this.latch = latch;
            this.responseRef = response;
        }

        public String getTargetId()
        {
            return null;
        }

        public void connect() throws IOException
        {
        }

        public void disconnect() throws IOException
        {
        }

        public void deliver(RHTTPResponse response) throws IOException
        {
            responseRef.set(response);
            latch.countDown();
        }

        public void addListener(RHTTPListener listener)
        {
        }

        public void removeListener(RHTTPListener listener)
        {
        }

        public void addClientListener(ClientListener listener)
        {
        }

        public void removeClientListener(ClientListener listener)
        {
        }

        public String getHost()
        {
            return null;
        }

        public int getPort()
        {
            return 0;
        }

        public String getGatewayURI()
        {
            // TODO Auto-generated method stub
            return null;
        }

        public String getPath()
        {
            // TODO Auto-generated method stub
            return null;
        }
    }
}
