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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.DigestAuthentication;
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
        constraint.setRoles(new String[]{"**"}); //allow any authenticated user
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/secure");
        mapping.setConstraint(constraint);

        securityHandler.addConstraintMapping(mapping);
        securityHandler.setAuthenticator(authenticator);
        securityHandler.setLoginService(loginService);

        securityHandler.setHandler(handler);
        start(securityHandler);
    }

    @Test
    public void test_BasicAuthentication() throws Exception
    {
        startBasic(new EmptyServerHandler());
        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
        test_Authentication(new BasicAuthentication(uri, realm, "basic", "basic"));
    }

    @Test
    public void test_DigestAuthentication() throws Exception
    {
        startDigest(new EmptyServerHandler());
        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
        test_Authentication(new DigestAuthentication(uri, realm, "digest", "digest"));
    }

    private void test_Authentication(Authentication authentication) throws Exception
    {
        AuthenticationStore authenticationStore = client.getAuthenticationStore();

        final AtomicReference<CountDownLatch> requests = new AtomicReference<>(new CountDownLatch(1));
        Request.Listener.Adapter requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.get().countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        // Request without Authentication causes a 401
        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        Assert.assertNotNull(response);
        Assert.assertEquals(401, response.getStatus());
        Assert.assertTrue(requests.get().await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);

        authenticationStore.addAuthentication(authentication);

        requests.set(new CountDownLatch(2));
        requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.get().countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        // Request with authentication causes a 401 (no previous successful authentication) + 200
        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        response = request.timeout(5, TimeUnit.SECONDS).send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(requests.get().await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);

        requests.set(new CountDownLatch(1));
        requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.get().countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        // Further requests do not trigger 401 because there is a previous successful authentication
        // Remove existing header to be sure it's added by the implementation
        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        response = request.timeout(5, TimeUnit.SECONDS).send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(requests.get().await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);
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

        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(uri, realm, "basic", "basic"));

        final CountDownLatch requests = new CountDownLatch(3);
        Request.Listener.Adapter requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/secure")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(requests.await(5, TimeUnit.SECONDS));
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

        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(uri, realm, "basic", "basic"));

        final CountDownLatch requests = new CountDownLatch(3);
        Request.Listener.Adapter requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/redirect")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(requests.await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);
    }

    @Test
    public void test_BasicAuthentication_WithAuthenticationRemoved() throws Exception
    {
        startBasic(new EmptyServerHandler());

        final AtomicReference<CountDownLatch> requests = new AtomicReference<>(new CountDownLatch(2));
        Request.Listener.Adapter requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.get().countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
        BasicAuthentication authentication = new BasicAuthentication(uri, realm, "basic", "basic");
        authenticationStore.addAuthentication(authentication);

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(requests.get().await(5, TimeUnit.SECONDS));

        authenticationStore.removeAuthentication(authentication);

        requests.set(new CountDownLatch(1));
        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        response = request.timeout(5, TimeUnit.SECONDS).send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(requests.get().await(5, TimeUnit.SECONDS));

        Authentication.Result result = authenticationStore.findAuthenticationResult(request.getURI());
        Assert.assertNotNull(result);
        authenticationStore.removeAuthenticationResult(result);

        requests.set(new CountDownLatch(1));
        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        response = request.timeout(5, TimeUnit.SECONDS).send();
        Assert.assertNotNull(response);
        Assert.assertEquals(401, response.getStatus());
        Assert.assertTrue(requests.get().await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_BasicAuthentication_WithWrongPassword() throws Exception
    {
        startBasic(new EmptyServerHandler());

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
        BasicAuthentication authentication = new BasicAuthentication(uri, realm, "basic", "wrong");
        authenticationStore.addAuthentication(authentication);

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        Assert.assertNotNull(response);
        Assert.assertEquals(401, response.getStatus());
    }
}
