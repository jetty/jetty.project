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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.security.Constraint;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientAuthenticationTest extends AbstractHttpClientServerTest
{
    private String realm = "TestRealm";

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
        mapping.setPathSpec("/*");
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
        startBasic(new EmptyHandler());
        test_Authentication(new BasicAuthentication("http://localhost:" + connector.getLocalPort(), realm, "basic", "basic"));
    }

    @Test
    public void test_DigestAuthentication() throws Exception
    {
        startDigest(new EmptyHandler());
        test_Authentication(new DigestAuthentication("http://localhost:" + connector.getLocalPort(), realm, "digest", "digest"));
    }

    private void test_Authentication(Authentication authentication) throws Exception
    {
        AuthenticationStore authenticationStore = client.getAuthenticationStore();

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
        Request request = client.newRequest("localhost", connector.getLocalPort()).path("/test");
        ContentResponse response = request.send().get(555, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(401, response.status());
        Assert.assertEquals(1, requests.get());
        client.getRequestListeners().remove(requestListener);
        requests.set(0);

        authenticationStore.addAuthentication(authentication);

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
        response = request.send().get(555, TimeUnit.SECONDS);
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
}
