//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HttpClientProxyTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(NonSslScenarioProvider.class) // Avoid TLS otherwise CONNECT requests are sent instead of proxied requests
    public void testProxiedRequest(Scenario scenario) throws Exception
    {
        final String serverHost = "server";
        final int status = HttpStatus.NO_CONTENT_204;
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (!URI.create(baseRequest.getHttpURI().toString()).isAbsolute())
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
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(status, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(NonSslScenarioProvider.class) // Avoid TLS otherwise CONNECT requests are sent instead of proxied requests
    public void testProxyAuthentication(Scenario scenario) throws Exception
    {
        final String user = "foo";
        final String password = "bar";
        final String credentials = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.ISO_8859_1));
        final String serverHost = "server";
        final String realm = "test_realm";
        final int status = HttpStatus.NO_CONTENT_204;
        start(scenario, new AbstractHandler()
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
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        // No Authentication available => 407
        assertEquals(HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407, response1.getStatus());

        // Add authentication...
        URI uri = URI.create(scenario.getScheme() + "://" + proxyHost + ":" + proxyPort);
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
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(status, response2.getStatus());
        assertEquals(2, requests.get());

        // Now the authentication result is cached => 204
        requests.set(0);
        ContentResponse response3 = client.newRequest(serverHost, serverPort)
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(status, response3.getStatus());
        assertEquals(1, requests.get());
    }

    @ParameterizedTest
    @ArgumentsSource(NonSslScenarioProvider.class) // Avoid TLS otherwise CONNECT requests are sent instead of proxied requests
    public void testProxyAuthenticationWithRedirect(Scenario scenario) throws Exception
    {
        String user = "foo";
        String password = "bar";
        String credentials = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.ISO_8859_1));
        String proxyHost = "localhost";
        String serverHost = "server";
        int serverPort = HttpScheme.HTTP.is(scenario.getScheme()) ? 80 : 443;
        String realm = "test_realm";
        int status = HttpStatus.NO_CONTENT_204;
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (target.startsWith("/proxy"))
                {
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
                            {
                                // Change also the host, to verify that proxy authentication works in this case too.
                                response.sendRedirect(scenario.getScheme() + "://127.0.0.1:" + serverPort + "/server");
                            }
                        }
                    }
                }
                else if (target.startsWith("/server"))
                {
                    response.setStatus(status);
                }
                else
                {
                    response.sendError(HttpStatus.INTERNAL_SERVER_ERROR_500);
                }
            }
        });

        int proxyPort = connector.getLocalPort();
        client.getProxyConfiguration().getProxies().add(new HttpProxy(proxyHost, proxyPort));

        ContentResponse response1 = client.newRequest(serverHost, serverPort)
            .scheme(scenario.getScheme())
            .path("/proxy")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        // No Authentication available => 407.
        assertEquals(HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407, response1.getStatus());

        // Add authentication...
        URI uri = URI.create(scenario.getScheme() + "://" + proxyHost + ":" + proxyPort);
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
        // ...and perform the request again => 407 + 302 + 204.
        ContentResponse response2 = client.newRequest(serverHost, serverPort)
            .scheme(scenario.getScheme())
            .path("/proxy")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(status, response2.getStatus());
        assertEquals(3, requests.get());

        // Now the authentication result is cached => 204.
        requests.set(0);
        ContentResponse response3 = client.newRequest(serverHost, serverPort)
            .scheme(scenario.getScheme())
            .path("/server")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(status, response3.getStatus());
        assertEquals(1, requests.get());
    }

    @ParameterizedTest
    @ArgumentsSource(NonSslScenarioProvider.class) // Avoid TLS otherwise CONNECT requests are sent instead of proxied requests
    public void testProxyAuthenticationWithServerAuthentication(Scenario scenario) throws Exception
    {
        String proxyRealm = "proxyRealm";
        String serverRealm = "serverRealm";
        int status = HttpStatus.NO_CONTENT_204;
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                String authorization = request.getHeader(HttpHeader.PROXY_AUTHORIZATION.asString());
                if (authorization == null)
                {
                    response.setStatus(HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407);
                    response.setHeader(HttpHeader.PROXY_AUTHENTICATE.asString(), "Basic realm=\"" + proxyRealm + "\"");
                }
                else
                {
                    authorization = request.getHeader(HttpHeader.AUTHORIZATION.asString());
                    if (authorization == null)
                    {
                        response.setStatus(HttpStatus.UNAUTHORIZED_401);
                        response.setHeader(HttpHeader.WWW_AUTHENTICATE.asString(), "Basic realm=\"" + serverRealm + "\"");
                    }
                    else
                    {
                        response.setStatus(status);
                    }
                }
            }
        });

        String proxyHost = "localhost";
        int proxyPort = connector.getLocalPort();
        String serverHost = "server";
        int serverPort = proxyPort + 1;
        URI proxyURI = URI.create(scenario.getScheme() + "://" + proxyHost + ":" + proxyPort);
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(proxyURI, proxyRealm, "proxyUser", "proxyPassword"));
        URI serverURI = URI.create(scenario.getScheme() + "://" + serverHost + ":" + serverPort);
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(serverURI, serverRealm, "serverUser", "serverPassword"));
        client.getProxyConfiguration().getProxies().add(new HttpProxy(proxyHost, proxyPort));
        final AtomicInteger requests = new AtomicInteger();
        client.getRequestListeners().add(new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.incrementAndGet();
            }
        });
        // Make a request, expect 407 + 401 + 204.
        ContentResponse response1 = client.newRequest(serverHost, serverPort)
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(status, response1.getStatus());
        assertEquals(3, requests.get());

        // Make again the request, only the server authentication is cached, expect 407 + 204.
        requests.set(0);
        ContentResponse response2 = client.newRequest(serverHost, serverPort)
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(status, response2.getStatus());
        assertEquals(2, requests.get());
    }

    @ParameterizedTest
    @ArgumentsSource(NonSslScenarioProvider.class) // Avoid TLS otherwise CONNECT requests are sent instead of proxied requests
    public void testProxyAuthenticationWithExplicitAuthorizationHeader(Scenario scenario) throws Exception
    {
        String proxyRealm = "proxyRealm";
        String serverRealm = "serverRealm";
        int status = HttpStatus.NO_CONTENT_204;
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                String authorization = request.getHeader(HttpHeader.PROXY_AUTHORIZATION.asString());
                if (authorization == null)
                {
                    response.setStatus(HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407);
                    response.setHeader(HttpHeader.PROXY_AUTHENTICATE.asString(), "Basic realm=\"" + proxyRealm + "\"");
                }
                else
                {
                    authorization = request.getHeader(HttpHeader.AUTHORIZATION.asString());
                    if (authorization == null)
                    {
                        response.setStatus(HttpStatus.UNAUTHORIZED_401);
                        response.setHeader(HttpHeader.WWW_AUTHENTICATE.asString(), "Basic realm=\"" + serverRealm + "\"");
                    }
                    else
                    {
                        response.setStatus(status);
                    }
                }
            }
        });

        String proxyHost = "localhost";
        int proxyPort = connector.getLocalPort();
        String serverHost = "server";
        int serverPort = proxyPort + 1;
        URI proxyURI = URI.create(scenario.getScheme() + "://" + proxyHost + ":" + proxyPort);
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(proxyURI, proxyRealm, "proxyUser", "proxyPassword"));
        client.getProxyConfiguration().getProxies().add(new HttpProxy(proxyHost, proxyPort));
        final AtomicInteger requests = new AtomicInteger();
        client.getRequestListeners().add(new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.incrementAndGet();
            }
        });
        // Make a request, expect 407 + 204.
        ContentResponse response1 = client.newRequest(serverHost, serverPort)
            .scheme(scenario.getScheme())
            .header(HttpHeader.AUTHORIZATION, "Basic foobar")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(status, response1.getStatus());
        assertEquals(2, requests.get());

        // Make again the request, authentication is cached, expect 204.
        requests.set(0);
        ContentResponse response2 = client.newRequest(serverHost, serverPort)
            .scheme(scenario.getScheme())
            .header(HttpHeader.AUTHORIZATION, "Basic foobar")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(status, response2.getStatus());
        assertEquals(1, requests.get());
    }
}
