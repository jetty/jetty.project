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

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.DigestAuthentication;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientAuthenticationTest extends AbstractHttpClientServerTest
{
    private String realm = "TestRealm";

    public HttpClientAuthenticationTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    public void startBasic(Handler handler) throws Exception
    {
        start(new BasicAuthenticator(), handler);
    }

    public void startDigest(Handler handler) throws Exception
    {
        start(new DigestAuthenticator(), handler);
    }

    private void start(Authenticator authenticator, Handler handler) throws Exception
    {
        server = new Server();
        File realmFile = MavenTestingUtils.getTestResourceFile("realm.properties");
        LoginService loginService = new HashLoginService(realm, realmFile.getAbsolutePath());
        server.addBean(loginService);

        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();

        Constraint constraint = new Constraint();
        constraint.setAuthenticate(true);
        constraint.setRoles(new String[]{"*"});
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/secure");
        mapping.setConstraint(constraint);

        securityHandler.addConstraintMapping(mapping);
        securityHandler.setAuthenticator(authenticator);
        securityHandler.setLoginService(loginService);
        securityHandler.setStrict(false);

        securityHandler.setHandler(handler);
        start(securityHandler);
    }

    @Test
    public void test_BasicAuthentication() throws Exception
    {
        startBasic(new EmptyServerHandler());
        test_Authentication(new BasicAuthentication(scheme + "://localhost:" + connector.getLocalPort(), realm, "basic", "basic"));
    }

    @Test
    public void test_DigestAuthentication() throws Exception
    {
        startDigest(new EmptyServerHandler());
        test_Authentication(new DigestAuthentication(scheme + "://localhost:" + connector.getLocalPort(), realm, "digest", "digest"));
    }

    private void test_Authentication(Authentication authentication) throws Exception
    {
        AuthenticationStore authenticationStore = client.getAuthenticationStore();

        final AtomicInteger requests = new AtomicInteger();
        Request.Listener.Empty requestListener = new Request.Listener.Empty()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.incrementAndGet();
            }
        };
        client.getRequestListeners().add(requestListener);

        // Request without Authentication causes a 401
        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        ContentResponse response = request.send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(401, response.status());
        Assert.assertEquals(1, requests.get());
        client.getRequestListeners().remove(requestListener);
        requests.set(0);

        authenticationStore.addAuthentication(authentication);

        requestListener = new Request.Listener.Empty()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.incrementAndGet();
            }
        };
        client.getRequestListeners().add(requestListener);

        // Request with authentication causes a 401 (no previous successful authentication) + 200
        response = request.send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertEquals(2, requests.get());
        client.getRequestListeners().remove(requestListener);
        requests.set(0);

        requestListener = new Request.Listener.Empty()
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
        response = request.send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertEquals(1, requests.get());
        client.getRequestListeners().remove(requestListener);
        requests.set(0);
    }

    @Test
    public void test_BasicAuthentication_ThenRedirect() throws Exception
    {
        startBasic(new AbstractHandler()
        {
            private final AtomicInteger requests = new AtomicInteger();

            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (requests.incrementAndGet() == 1)
                    response.sendRedirect(scheme + "://" + request.getServerName() + ":" + request.getServerPort() + request.getRequestURI());
            }
        });

        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(scheme + "://localhost:" + connector.getLocalPort(), realm, "basic", "basic"));

        final AtomicInteger requests = new AtomicInteger();
        Request.Listener.Empty requestListener = new Request.Listener.Empty()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.incrementAndGet();
            }
        };
        client.getRequestListeners().add(requestListener);

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/secure")
                .send()
                .get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertEquals(3, requests.get());
        client.getRequestListeners().remove(requestListener);
    }

    @Test
    public void test_Redirect_ThenBasicAuthentication() throws Exception
    {
        startBasic(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (request.getRequestURI().endsWith("/redirect"))
                    response.sendRedirect(scheme + "://" + request.getServerName() + ":" + request.getServerPort() + "/secure");
            }
        });

        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(scheme + "://localhost:" + connector.getLocalPort(), realm, "basic", "basic"));

        final AtomicInteger requests = new AtomicInteger();
        Request.Listener.Empty requestListener = new Request.Listener.Empty()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.incrementAndGet();
            }
        };
        client.getRequestListeners().add(requestListener);

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/redirect")
                .send()
                .get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertEquals(3, requests.get());
        client.getRequestListeners().remove(requestListener);
    }

    @Test
    public void test_BasicAuthentication_WithAuthenticationRemoved() throws Exception
    {
        startBasic(new EmptyServerHandler());

        final AtomicInteger requests = new AtomicInteger();
        Request.Listener.Empty requestListener = new Request.Listener.Empty()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.incrementAndGet();
            }
        };
        client.getRequestListeners().add(requestListener);

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        BasicAuthentication authentication = new BasicAuthentication(scheme + "://localhost:" + connector.getLocalPort(), realm, "basic", "basic");
        authenticationStore.addAuthentication(authentication);

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        ContentResponse response = request.send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertEquals(2, requests.get());
        requests.set(0);

        authenticationStore.removeAuthentication(authentication);

        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        response = request.send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertEquals(1, requests.get());
        requests.set(0);

        Authentication.Result result = authenticationStore.findAuthenticationResult(request.uri());
        Assert.assertNotNull(result);
        authenticationStore.removeAuthenticationResult(result);

        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        response = request.send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(401, response.status());
        Assert.assertEquals(1, requests.get());
        requests.set(0);
    }
}
