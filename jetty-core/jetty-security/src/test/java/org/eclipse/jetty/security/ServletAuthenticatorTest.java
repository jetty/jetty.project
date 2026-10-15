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

package org.eclipse.jetty.security;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the {@link SecurityHandler} gate that passes the authentication of a request to a
 * {@link ServletAuthenticator} in the servlet layer.
 */
public class ServletAuthenticatorTest
{
    private Server _server;
    private LocalConnector _connector;
    private SecurityHandler.PathMapped _securityHandler;
    private SecurityHandler _validatedSecurityHandler;
    private final AtomicInteger _validateRequests = new AtomicInteger();
    private final AtomicReference<Object> _mustValidateAttribute = new AtomicReference<>();
    private final AtomicReference<Object> _constraintAttribute = new AtomicReference<>();

    @BeforeEach
    public void before()
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        _securityHandler = new SecurityHandler.PathMapped();
        _securityHandler.put("/private/*", Constraint.ANY_USER);
        _securityHandler.put("/public/*", Constraint.ALLOWED);
        _securityHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                _mustValidateAttribute.set(request.getAttribute(ServletAuthenticator.MUST_VALIDATE_KEY));
                _constraintAttribute.set(request.getAttribute(ServletAuthenticator.CONSTRAINT_KEY));
                callback.succeeded();
                return true;
            }
        });
    }

    /**
     * @param servletLayer whether the context tells the {@link SecurityHandler} that it validates
     * the requests passed to it, as a servlet context does.
     */
    private void startServer(boolean servletLayer) throws Exception
    {
        if (servletLayer)
            _validatedSecurityHandler = _securityHandler;
        ContextHandler contextHandler = servletLayer ? new ValidatingContextHandler() : new ContextHandler("/ctx");
        contextHandler.setHandler(_securityHandler);
        _server.setHandler(contextHandler);
        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testConstrainedRequestIsPassedToTheServletLayer() throws Exception
    {
        ServletAuthenticator authenticator = new TestServletAuthenticator();
        _securityHandler.setAuthenticator(authenticator);
        startServer(true);

        String response = _connector.getResponse("GET /ctx/private/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));

        // The SecurityHandler did not authenticate, it only recorded its decision for the servlet layer.
        assertThat(_validateRequests.get(), is(0));
        assertThat(_mustValidateAttribute.get(), is(Boolean.TRUE));
        assertThat(_constraintAttribute.get(), instanceOf(Constraint.class));
        assertThat(((Constraint)_constraintAttribute.get()).getAuthorization(), is(Constraint.Authorization.ANY_USER));
    }

    @Test
    public void testUnconstrainedRequestIsAlsoPassedToTheServletLayer() throws Exception
    {
        _securityHandler.setAuthenticator(new TestServletAuthenticator());
        startServer(true);

        String response = _connector.getResponse("GET /ctx/public/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));

        assertThat(_validateRequests.get(), is(0));
        assertThat(_mustValidateAttribute.get(), is(Boolean.FALSE));
        assertThat(_constraintAttribute.get(), instanceOf(Constraint.class));
        assertThat(((Constraint)_constraintAttribute.get()).getAuthorization(), is(Constraint.Authorization.ALLOWED));
    }

    @Test
    public void testARequestTheServletAuthenticatorDoesNotDeferIsValidatedByTheSecurityHandler() throws Exception
    {
        _securityHandler.setAuthenticator(new TestServletAuthenticator()
        {
            @Override
            public boolean deferValidation(Request request)
            {
                return false;
            }
        });
        startServer(true);

        // The SecurityHandler authenticates and authorizes the request itself, as it does for an Authenticator that is not a ServletAuthenticator.
        assertThat(_connector.getResponse("GET /ctx/private/info HTTP/1.0\r\n\r\n"), containsString("HTTP/1.1 403 Forbidden"));
        assertThat(_validateRequests.get(), is(1));

        // Nothing is left for the servlet layer to find.
        assertThat(_connector.getResponse("GET /ctx/public/info HTTP/1.0\r\n\r\n"), containsString("HTTP/1.1 200 OK"));
        assertThat(_mustValidateAttribute.get(), nullValue());
        assertThat(_constraintAttribute.get(), nullValue());
    }

    @Test
    public void testNoServletLayerToValidateTheRequestsDoesNotStart()
    {
        _securityHandler.setAuthenticator(new TestServletAuthenticator());

        // The constraints would never be applied, as nothing would call the ServletAuthenticator.
        Throwable failure = assertThrows(Throwable.class, () -> startServer(false));
        assertThat(failure.getMessage(), containsString("does not validate the requests of this handler"));
        assertThat("the failure must name the context", failure.getMessage(), containsString("/ctx"));
        assertThat(_server.isStarted(), is(false));
    }

    @Test
    public void testAServletLayerValidatingAnotherSecurityHandlerDoesNotStart()
    {
        _securityHandler.setAuthenticator(new TestServletAuthenticator());

        Throwable failure = assertThrows(Throwable.class, () ->
        {
            ContextHandler contextHandler = new ValidatingContextHandler();
            _validatedSecurityHandler = new SecurityHandler.PathMapped();
            contextHandler.setHandler(_securityHandler);
            _server.setHandler(contextHandler);
            _server.start();
        });
        assertThat(failure.getMessage(), containsString("does not validate the requests of this handler"));
        assertThat("the failure must name the context", failure.getMessage(), containsString("/ctx"));
    }

    @Test
    public void testConstrainedRequestWithoutTheGateIsUnchanged() throws Exception
    {
        _securityHandler.setAuthenticator(new TestAuthenticator());
        startServer(false);

        // The SecurityHandler authenticates and authorizes the request itself.
        String response = _connector.getResponse("GET /ctx/private/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));
        assertThat(_validateRequests.get(), is(1));
    }

    @Test
    public void testUnconstrainedRequestWithoutTheGateHasNoAttributes() throws Exception
    {
        _securityHandler.setAuthenticator(new TestAuthenticator());
        startServer(false);

        String response = _connector.getResponse("GET /ctx/public/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));

        assertThat(_validateRequests.get(), is(0));
        assertThat(_mustValidateAttribute.get(), nullValue());
        assertThat(_constraintAttribute.get(), nullValue());
    }

    /**
     * A context that validates the requests of one SecurityHandler, as a servlet context validates
     * the requests of the SecurityHandler installed in it.
     */
    private class ValidatingContextHandler extends ContextHandler implements ServletAuthenticator.Validator
    {
        private ValidatingContextHandler()
        {
            super("/ctx");
        }

        @Override
        public boolean validatesRequestsFor(SecurityHandler securityHandler)
        {
            return securityHandler == _validatedSecurityHandler;
        }
    }

    private class TestAuthenticator implements Authenticator
    {
        @Override
        public void setConfiguration(Configuration configuration)
        {
        }

        @Override
        public String getAuthenticationType()
        {
            return "TEST";
        }

        @Override
        public AuthenticationState validateRequest(Request request, Response response, Callback callback)
        {
            _validateRequests.incrementAndGet();
            return null;
        }
    }

    private class TestServletAuthenticator extends TestAuthenticator implements ServletAuthenticator
    {
        @Override
        public AuthenticationState validateServletRequest(Request request, Response response, boolean mandatory)
        {
            throw new IllegalStateException("not called by the SecurityHandler");
        }

        @Override
        public void secureResponse(Request request, Response response)
        {
        }
    }
}
