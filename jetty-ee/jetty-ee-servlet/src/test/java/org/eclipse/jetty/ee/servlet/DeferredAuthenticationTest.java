//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee.servlet;

import java.security.Principal;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.security.Credential;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNull;

public class DeferredAuthenticationTest
{
    private Server _server;
    private LocalConnector _connector;
    private SecurityHandler.PathMapped _securityHandler;
    private ServletContextHandler _contextHandler;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        _contextHandler = new ServletContextHandler();
        _server.setHandler(_contextHandler);
        _contextHandler.setContextPath("/");

        _securityHandler = new SecurityHandler.PathMapped();
        _securityHandler.put("/private/*", Constraint.ANY_USER);
        _securityHandler.put("/public/*", Constraint.ALLOWED);
        _contextHandler.setSecurityHandler(_securityHandler);

        // Configure the loginService with a test user.
        UserStore userStore = new UserStore();
        userStore.addUser("test-user", Credential.getCredential("pwd"), new String[]{"user-role"});
        HashLoginService loginService = new HashLoginService();
        loginService.setUserStore(userStore);
        _securityHandler.setLoginService(loginService);
    }

    public void startServer(Authenticator authenticator, HttpServlet httpServlet) throws Exception
    {
        _securityHandler.setAuthenticator(authenticator);
        _contextHandler.addServlet(httpServlet, "/");
        _server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        _server.stop();
    }

    @RepeatedTest(100)
    public void testWriteOnDeferredAuthentication() throws Exception
    {
        AtomicInteger authenticatorCount = new AtomicInteger();
        AtomicReference<Boolean> authenticatedRef = new AtomicReference<>();
        AtomicReference<Principal> userPrincipalRef = new AtomicReference<>();
        AtomicReference<Throwable> servletErrorRef = new AtomicReference<>();
        AtomicInteger serviceCount = new AtomicInteger();
        startServer(new LoginAuthenticator()
        {
            @Override
            public String getAuthenticationType()
            {
                return "TEST";
            }

            @Override
            public AuthenticationState validateRequest(Request request, Response response, Callback callback)
            {
                authenticatorCount.incrementAndGet();
                if (!"authenticate".equals(request.getHttpURI().getQuery()))
                {
                    // Send 401 response as a challenge.
                    response.setStatus(HttpStatus.UNAUTHORIZED_401);
                    response.write(true, BufferUtil.toBuffer("this is a challenge"), callback);
                    return AuthenticationState.CHALLENGE;
                }

                if (AuthenticationState.Deferred.isDeferred(response))
                {
                    // If we are deferred we attempt to write, this should be ignored.
                    Response.writeError(request, response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500);
                    return AuthenticationState.SEND_FAILURE;
                }

                // Successful authentication.
                UserIdentity userIdentity = getLoginService().login("test-user", Credential.getCredential("pwd"), request, null);
                return new UserAuthenticationSucceeded(getAuthenticationType(), userIdentity);
            }
        }, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp)
            {
                try
                {
                    assertNull(req.getUserPrincipal());
                    boolean authenticated = req.authenticate(resp);
                    authenticatedRef.set(authenticated);
                    userPrincipalRef.set(req.getUserPrincipal());
                    if (authenticated)
                        resp.getWriter().println("success");
                }
                catch (Throwable t)
                {
                    servletErrorRef.set(t);
                }
                finally
                {
                    serviceCount.incrementAndGet();
                }
            }
        });

        // Authenticator is invoked twice, first time for deferred auth on getUserPrincipal() which tries to write and fails,
        // the second time for authenticate() which returns false and sends a 401 response.
        String response = _connector.getResponse("GET /public/foo HTTP/1.0\r\n\r\n");
        await().atMost(5, TimeUnit.SECONDS).until(() -> serviceCount.get() == 1);
        assertThat(response, containsString("401 Unauthorized"));
        assertThat(response, containsString("this is a challenge"));
        assertThat(authenticatorCount.get(), equalTo(2));
        System.out.println(response);
        System.out.println(authenticatedRef.get());
        assertThat(authenticatedRef.get(), equalTo(false));
        assertThat(userPrincipalRef.get(), equalTo(null));
        assertThat(servletErrorRef.get(), equalTo(null));

        // Authenticator is invoked twice, first time for deferred auth on getUserPrincipal() which tries to write and fails,
        // the second time for authenticate() which returns true and sends a 200 response.
/*
        response = _connector.getResponse("GET /public/foo?authenticate HTTP/1.0\r\n\r\n");
        await().atMost(5, TimeUnit.SECONDS).until(() -> serviceCount.get() == 2);
        assertThat(response, containsString("200 OK"));
        assertThat(response, containsString("success"));
        assertThat(authenticatorCount.get(), equalTo(4));
        assertThat(authenticatedRef.get(), equalTo(true));
        assertThat(userPrincipalRef.get().getName(), equalTo("test-user"));
        assertThat(servletErrorRef.get(), equalTo(null));
*/
    }
}