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

package org.eclipse.jetty.ee9.security.jaspi;

import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.ClientAuthConfig;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.config.ServerAuthContext;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.ee9.nested.ServletConstraint;
import org.eclipse.jetty.ee9.security.ConstraintMapping;
import org.eclipse.jetty.ee9.security.ConstraintSecurityHandler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.startsWith;

/**
 * A context that has no {@link jakarta.security.auth.message.config.AuthConfigProvider} registered
 * for its appContext must be served by ordinary constraint processing, even though the server has
 * an {@link AuthConfigFactory} and other contexts do use JASPI.
 */
@Isolated("Sets the global AuthConfigFactory")
public class JaspiNoProviderTest
{
    private Server _server;
    private LocalConnector _connector;
    private AuthConfigFactory _authConfigFactory;
    private ConstraintSecurityHandler _noProviderSecurity;

    @BeforeEach
    public void before() throws Exception
    {
        _authConfigFactory = new DefaultAuthConfigFactory();
        registerProviderFor("null /ctx");
        AuthConfigFactory.setFactory(_authConfigFactory);

        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler(contexts);

        JaspiAuthenticatorFactory jaspiAuthFactory = new JaspiAuthenticatorFactory();
        contexts.addHandler(newContext("/ctx", jaspiAuthFactory));
        ContextHandler noProvider = newContext("/noprovider", jaspiAuthFactory);
        contexts.addHandler(noProvider);
        _noProviderSecurity = (ConstraintSecurityHandler)noProvider.getHandler();

        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
        AuthConfigFactory.setFactory(null);
    }

    private void registerProviderFor(String appContext)
    {
        _authConfigFactory.registerConfigProvider("org.eclipse.jetty.ee9.security.jaspi.provider.JaspiAuthConfigProvider",
            Map.of("ServerAuthModule", "org.eclipse.jetty.ee9.security.jaspi.HttpHeaderAuthModule",
                "AppContextID", appContext),
            JaspiAuthenticatorFactory.MESSAGE_LAYER, appContext, "a test provider");
    }

    private ContextHandler newContext(String contextPath, JaspiAuthenticatorFactory jaspiAuthFactory)
    {
        ContextHandler context = new ContextHandler();
        context.setContextPath(contextPath);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        context.setHandler(security);
        security.setAuthenticatorFactory(jaspiAuthFactory);
        security.setHandler(new JaspiTest.TestHandler());

        ServletConstraint constraint = new ServletConstraint("All", "users");
        constraint.setAuthenticate(true);
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/jaspi/*");
        mapping.setConstraint(constraint);
        security.addConstraintMapping(mapping);

        return context;
    }

    @Test
    public void testContextWithNoProviderIsStillGivenAJaspiAuthenticator()
    {
        assertThat(_noProviderSecurity.getAuthenticator(), instanceOf(JaspiAuthenticator.class));
    }

    @Test
    public void testContextWithNoProviderServesAnUnconstrainedPath() throws Exception
    {
        String response = _connector.getResponse("GET /noprovider/test HTTP/1.0\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testContextWithNoProviderForbidsAConstrainedPath() throws Exception
    {
        String response = _connector.getResponse("""
            GET /noprovider/jaspi/test HTTP/1.0
            X-Forwarded-User: user

            """);
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));
    }

    @Test
    public void testContextWithAProviderIsAuthenticatedByTheModule() throws Exception
    {
        String response = _connector.getResponse("""
            GET /ctx/jaspi/test HTTP/1.0
            X-Forwarded-User: user

            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testProviderRegisteredAfterStartTakesEffectOnTheNextRequest() throws Exception
    {
        String request = """
            GET /noprovider/jaspi/test HTTP/1.0
            X-Forwarded-User: user

            """;
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 403 Forbidden"));

        registerProviderFor("null /noprovider");

        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testProviderWithNoModulesForTheRequestIsServedByConstraintProcessing() throws Exception
    {
        _authConfigFactory.registerConfigProvider(new NoModulesAuthConfigProvider(),
            JaspiAuthenticatorFactory.MESSAGE_LAYER, "null /noprovider", "a test provider");

        assertThat(_connector.getResponse("GET /noprovider/test HTTP/1.0\n\n"), startsWith("HTTP/1.1 200 OK"));
        assertThat(_connector.getResponse("""
            GET /noprovider/jaspi/test HTTP/1.0
            X-Forwarded-User: user

            """), startsWith("HTTP/1.1 403 Forbidden"));
    }

    /**
     * A provider whose ServerAuthConfig has no modules configured for the request, which the
     * generic {@code getAuthContext} javadoc has it report by returning null, though a396 requires
     * a context obtained under the Servlet Container Profile to be non-null.
     */
    private static class NoModulesAuthConfigProvider implements AuthConfigProvider
    {
        @Override
        public ClientAuthConfig getClientAuthConfig(String layer, String appContext, CallbackHandler handler)
        {
            return null;
        }

        @Override
        public ServerAuthConfig getServerAuthConfig(String layer, String appContext, CallbackHandler handler)
        {
            return new NoModulesAuthConfig(layer, appContext);
        }

        @Override
        public void refresh()
        {
        }
    }

    private static class NoModulesAuthConfig implements ServerAuthConfig
    {
        private final String _layer;
        private final String _appContext;

        private NoModulesAuthConfig(String layer, String appContext)
        {
            _layer = layer;
            _appContext = appContext;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public ServerAuthContext getAuthContext(String authContextId, Subject serviceSubject, Map properties)
        {
            return null;
        }

        @Override
        public String getMessageLayer()
        {
            return _layer;
        }

        @Override
        public String getAppContext()
        {
            return _appContext;
        }

        @Override
        public String getAuthContextID(MessageInfo messageInfo)
        {
            return _appContext;
        }

        @Override
        public boolean isProtected()
        {
            return false;
        }

        @Override
        public void refresh()
        {
        }
    }
}
