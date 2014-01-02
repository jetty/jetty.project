//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientProxyTest extends AbstractHttpClientServerTest
{
    public HttpClientProxyTest(SslContextFactory sslContextFactory)
    {
        // Avoid TLS otherwise CONNECT requests are sent instead of proxied requests
        super(null);
    }

    @Test
    public void testProxiedRequest() throws Exception
    {
        final String serverHost = "server";
        final int status = HttpStatus.NO_CONTENT_204;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (!URI.create(baseRequest.getUri().toString()).isAbsolute())
                    response.setStatus(HttpServletResponse.SC_USE_PROXY);
                else if (serverHost.equals(request.getServerName()))
                    response.setStatus(status);
                else
                    response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
            }
        });

        int proxyPort = connector.getLocalPort();
        int serverPort = proxyPort + 1; // Any port will do for these tests - just not the same as the proxy
        client.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyPort));

        ContentResponse response = client.newRequest(serverHost, serverPort)
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(status, response.getStatus());
    }

    @Test
    public void testAuthenticatedProxiedRequest() throws Exception
    {
        final String user = "foo";
        final String password = "bar";
        final String credentials = B64Code.encode(user + ":" + password, StandardCharsets.ISO_8859_1);
        final String serverHost = "server";
        final String realm = "test_realm";
        final int status = HttpStatus.NO_CONTENT_204;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                String authorization = request.getHeader(HttpHeader.PROXY_AUTHORIZATION.asString());
                if (authorization == null)
                {
                    response.setStatus(HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407);
                    response.setHeader(HttpHeader.PROXY_AUTHENTICATE.asString(), "Basic realm=\"" + realm + "\"");
                }
                else
                {
                    String prefix = "Basic ";
                    if (authorization.startsWith(prefix))
                    {
                        String attempt = authorization.substring(prefix.length());
                        if (credentials.equals(attempt))
                            response.setStatus(status);
                    }
                }
            }
        });

        String proxyHost = "localhost";
        int proxyPort = connector.getLocalPort();
        int serverPort = proxyPort + 1; // Any port will do for these tests - just not the same as the proxy
        client.getProxyConfiguration().getProxies().add(new HttpProxy(proxyHost, proxyPort));

        ContentResponse response1 = client.newRequest(serverHost, serverPort)
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        // No Authentication available => 407
        Assert.assertEquals(HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407, response1.getStatus());

        // Add authentication...
        URI uri = URI.create(scheme + "://" + proxyHost + ":" + proxyPort);
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(uri, realm, user, password));
        final AtomicInteger requests = new AtomicInteger();
        client.getRequestListeners().add(new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.incrementAndGet();
            }
        });
        // ...and perform the request again => 407 + 204
        ContentResponse response2 = client.newRequest(serverHost, serverPort)
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(status, response2.getStatus());
        Assert.assertEquals(2, requests.get());

        // Now the authentication result is cached => 204
        requests.set(0);
        ContentResponse response3 = client.newRequest(serverHost, serverPort)
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(status, response3.getStatus());
        Assert.assertEquals(1, requests.get());
    }
}
