//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.IO;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientAuthenticationTest extends AbstractHttpClientServerTest
{
    @Test
    public void test_BasicAuthentication_WithChallenge() throws Exception
    {
        start(new BasicAuthenticationHandler());

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        String realm = "test";

        final AtomicInteger requests = new AtomicInteger();
        Request.Listener.Adapter requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.incrementAndGet();
            }
        };
        client.getRequestListeners().add(requestListener);

        // Request without Authentication causes a 401
        Request request = client.newRequest("localhost", connector.getLocalPort())
                .path("/test")
                .param("type", "Basic")
                .param("realm", realm);
        ContentResponse response = request.send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(401, response.status());
        Assert.assertEquals(1, requests.get());
        client.getRequestListeners().remove(requestListener);
        requests.set(0);

        String user = "jetty";
        String password = "rocks";
        authenticationStore.addAuthentication(new BasicAuthentication("http://localhost:" + connector.getLocalPort(), realm, user, password));

        requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.incrementAndGet();
            }
        };
        client.getRequestListeners().add(requestListener);

        // Request with authentication causes a 401 (no previous successful authentication) + 200
        request.param("user", user).param("password", password);
        response = request.send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertEquals(2, requests.get());
        client.getRequestListeners().remove(requestListener);
        requests.set(0);

        requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.incrementAndGet();
            }
        };
        client.getRequestListeners().add(requestListener);

        // Further requests do not trigger 401 because there is a previous successful authentication
        // Remove existing header to be sure it's added by the implementation
        request.header(HttpHeader.AUTHORIZATION.asString(), null);
        response = request.send().get(555, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertEquals(1, requests.get());
        client.getRequestListeners().remove(requestListener);
        requests.set(0);
    }

    private class BasicAuthenticationHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            try
            {
                String type = request.getParameter("type");
                String authorization = request.getHeader(HttpHeader.AUTHORIZATION.asString());
                if (authorization == null)
                {
                    String realm = request.getParameter("realm");
                    response.setStatus(401);
                    switch (type)
                    {
                        case "Basic":
                        {
                            response.setHeader("WWW-Authenticate", "Basic realm=\"" + realm + "\"");
                            break;
                        }
                        default:
                        {
                            throw new IllegalStateException();
                        }
                    }
                }
                else
                {
                    switch (type)
                    {
                        case "Basic":
                        {
                            String user = request.getParameter("user");
                            String password = request.getParameter("password");
                            String expected = "Basic " + B64Code.encode(user + ":" + password);
                            if (!expected.equals(authorization))
                                throw new IOException(expected + " != " + authorization);
                            IO.copy(request.getInputStream(), response.getOutputStream());
                            break;
                        }
                        default:
                        {
                            throw new IllegalStateException();
                        }
                    }
                }
            }
            finally
            {
                baseRequest.setHandled(true);
            }
        }
    }
}
