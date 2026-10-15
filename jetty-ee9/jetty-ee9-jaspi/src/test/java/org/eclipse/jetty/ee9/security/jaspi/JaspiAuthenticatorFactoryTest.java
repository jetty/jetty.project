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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.security.auth.Subject;

import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.RegistrationListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.eclipse.jetty.ee9.nested.Authentication;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.ee9.nested.ServletConstraint;
import org.eclipse.jetty.ee9.security.Authenticator;
import org.eclipse.jetty.ee9.security.ConstraintMapping;
import org.eclipse.jetty.ee9.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee9.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.ee9.security.jaspi.provider.JaspiAuthConfigProvider;
import org.eclipse.jetty.security.EmptyLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;

/**
 * The policy {@link JaspiAuthenticatorFactory#getAuthenticator(Server, jakarta.servlet.ServletContext,
 * Authenticator.AuthConfiguration, org.eclipse.jetty.security.IdentityService, LoginService)} applies
 * when choosing between the declared auth-method and JASPI, and the appContext it
 * gives the {@link JaspiAuthenticator} to look a provider up with.
 */
@Isolated("Sets the global AuthConfigFactory")
public class JaspiAuthenticatorFactoryTest
{
    private Server _server;
    private LocalConnector _connector;
    private RecordingAuthConfigFactory _authConfigFactory;
    private JaspiAuthenticatorFactory _jaspiAuthenticatorFactory;
    private List<Authenticator.Factory> _knownAuthenticatorFactories;
    private Authenticator _openIdAuthenticator;
    private Authenticator.Factory _openIdAuthenticatorFactory;

    @BeforeEach
    public void before()
    {
        AuthConfigFactory.setFactory(null);
        _authConfigFactory = new RecordingAuthConfigFactory();
        _jaspiAuthenticatorFactory = new JaspiAuthenticatorFactory();

        // A stand-in for OpenIdAuthenticatorFactory, which is not on this module's test classpath.
        _knownAuthenticatorFactories = new ConstraintSecurityHandler().getKnownAuthenticatorFactories();
        _openIdAuthenticator = new NoOpAuthenticator();
        _openIdAuthenticatorFactory = (server, context, configuration, identityService, loginService) ->
            Authenticator.OPENID_AUTH.equals(configuration.getAuthMethod()) ? _openIdAuthenticator : null;

        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
        _knownAuthenticatorFactories.remove(_openIdAuthenticatorFactory);
        AuthConfigFactory.setFactory(null);
    }

    @Test
    public void testNoAuthConfigFactoryUsesTheDeclaredAuthenticator()
    {
        assertThat(AuthConfigFactory.getFactory(), nullValue());
        assertThat(authenticatorFor(Authenticator.BASIC_AUTH, new EmptyLoginService()), instanceOf(BasicAuthenticator.class));
    }

    @Test
    public void testNoAuthConfigFactoryAndNoAuthMethodUsesNoAuthenticator()
    {
        assertThat(AuthConfigFactory.getFactory(), nullValue());
        assertThat(authenticatorFor(null, null), nullValue());
    }

    @Test
    public void testNoAuthConfigFactoryDoesNotTakeOverAnAuthMethodWithNoLoginService()
    {
        assertThat(AuthConfigFactory.getFactory(), nullValue());
        assertThat(authenticatorFor(Authenticator.BASIC_AUTH, null), instanceOf(BasicAuthenticator.class));
    }

    @Test
    public void testDeclaredAuthMethodWithALoginServiceServesWhileNoProviderIsRegistered() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        ContextHandler context = declaredBasicAuth(securedContext("/ctx"));
        _server.setHandler(context);
        _server.start();

        // The authenticator is in place for a provider that may yet be registered, but the declared auth-method serves requests until there is one.
        ConstraintSecurityHandler security = (ConstraintSecurityHandler)context.getHandler();
        assertThat(security.getAuthenticator(), instanceOf(JaspiAuthenticator.class));
        assertThat(security.getAuthMethod(), is(Authenticator.BASIC_AUTH));
        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"),
            containsString("WWW-Authenticate: Basic"));
    }

    @Test
    public void testAProviderRegisteredAfterStartIsUsedInsteadOfTheDeclaredAuthMethod() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        _server.setHandler(declaredBasicAuth(securedContext("/ctx")));
        _server.start();

        // Nothing is registered yet, so the declared auth-method serves the request.
        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"),
            containsString("WWW-Authenticate: Basic"));

        registerProviderFor("null /ctx");

        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"),
            startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testTheDeclaredAuthMethodServesAgainWhenTheRegistrationIsRemoved() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        _server.setHandler(declaredBasicAuth(securedContext("/ctx")));
        _server.start();

        String registrationId = _authConfigFactory.registerConfigProvider(new JaspiAuthConfigProvider(new HttpHeaderAuthModule()),
            JaspiAuthenticatorFactory.MESSAGE_LAYER, "null /ctx", null);
        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"),
            startsWith("HTTP/1.1 200 OK"));

        assertThat(_authConfigFactory.removeRegistration(registrationId), is(true));

        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"),
            containsString("WWW-Authenticate: Basic"));
    }

    @Test
    public void testAProviderRegisteredBeforeStartIsUsedInsteadOfTheDeclaredAuthMethod() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        ContextHandler context = securedContext("/ctx");
        ConstraintSecurityHandler security = (ConstraintSecurityHandler)context.getHandler();
        security.setAuthMethod(Authenticator.BASIC_AUTH);
        security.setLoginService(new EmptyLoginService());
        _server.setHandler(context);

        _authConfigFactory.registerConfigProvider(new JaspiAuthConfigProvider(new HttpHeaderAuthModule()),
            JaspiAuthenticatorFactory.MESSAGE_LAYER, "null /ctx", null);
        _server.start();

        assertThat(security.getAuthenticator(), instanceOf(JaspiAuthenticator.class));
        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\n\r\n"), not(containsString("WWW-Authenticate: Basic")));
        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"), startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testDeclaredAuthMethodWithNoLoginServiceIsTakenOverByJakartaAuthentication()
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        String logs = capturingLogs(() ->
            assertThat(authenticatorFor(Authenticator.BASIC_AUTH, null), instanceOf(JaspiAuthenticator.class)));
        assertThat(logs, containsString("No LoginService for auth-method BASIC"));
    }

    @Test
    public void testContextWithADeclaredAuthMethodAndNoLoginServiceStarts() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        ContextHandler context = newContext("/ctx");
        ConstraintSecurityHandler security = (ConstraintSecurityHandler)context.getHandler();
        security.setAuthMethod(Authenticator.BASIC_AUTH);
        _server.setHandler(context);

        _server.start();

        assertThat(security.getAuthenticator(), instanceOf(JaspiAuthenticator.class));
        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\n\r\n"), startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testAnAuthMethodThisFactoryDoesNotKnowIsLeftToAnotherFactory()
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        assertThat(authenticatorFor(Authenticator.OPENID_AUTH, null), nullValue());
    }

    @Test
    public void testADeclaredOpenIdAuthMethodIsNotClaimedWhenThisFactoryIsVisitedFirst() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);

        // TypeUtil.serviceStream does not sort, so appending puts the stub after the discovered jaspi factory either way.
        assertThat(_knownAuthenticatorFactories, hasItem(instanceOf(JaspiAuthenticatorFactory.class)));
        _knownAuthenticatorFactories.add(_openIdAuthenticatorFactory);

        ContextHandler context = newContext("/ctx");
        ConstraintSecurityHandler security = (ConstraintSecurityHandler)context.getHandler();
        // With no factory set, SecurityHandler.doStart walks the discovered ones in order.
        security.setAuthenticatorFactory(null);
        security.setAuthMethod(Authenticator.OPENID_AUTH);
        _server.setHandler(context);

        _server.start();

        assertThat(security.getAuthenticator(), sameInstance(_openIdAuthenticator));
    }

    @Test
    public void testAppContextsOfAContextWithNoVirtualHost() throws Exception
    {
        // "server" names a context with no virtual server name, and a module computes "null" for itself, so that spelling is answered to as well.
        assertThat(queriedAppContexts(newContext("/ctx"), "localhost"), is(List.of("server /ctx", "null /ctx")));
        assertThat(_authConfigFactory.getLayers(), everyItem(is("HttpServlet")));
    }

    @Test
    public void testAppContextUsesTheServerName() throws Exception
    {
        // The configured name is preferred, but the identifiers a module registering for itself would use are still answered to.
        _jaspiAuthenticatorFactory.setServerName("named.example.org");
        assertThat(queriedAppContexts(newContext("/ctx"), "localhost"),
            is(List.of("named.example.org /ctx", "server /ctx", "null /ctx")));
    }

    @Test
    public void testAppContextUsesTheVirtualHost() throws Exception
    {
        ContextHandler context = newContext("/ctx");
        context.setVirtualHosts(new String[]{"vhost.example.org"});
        assertThat(queriedAppContexts(context, "vhost.example.org"), is(List.of("vhost.example.org /ctx")));
    }

    @Test
    public void testAppContextDoesNotUseTheServiceSubject() throws Exception
    {
        // The service Subject does not name a virtual server, so a module registered for one of its principals would not be found by any other lookup.
        _jaspiAuthenticatorFactory.setServiceSubject(subjectNamed("subject.example.org"));
        _server.addBean(subjectNamed("bean.example.org"));
        assertThat(queriedAppContexts(newContext("/ctx"), "localhost"), is(List.of("server /ctx", "null /ctx")));
    }

    @Test
    public void testAppContextPrefersTheServerNameToTheVirtualHost() throws Exception
    {
        _jaspiAuthenticatorFactory.setServerName("named.example.org");
        ContextHandler context = newContext("/ctx");
        context.setVirtualHosts(new String[]{"vhost.example.org"});
        assertThat(queriedAppContexts(context, "vhost.example.org"),
            is(List.of("named.example.org /ctx", "vhost.example.org /ctx")));
    }

    @Test
    public void testAppContextOfTheRootContext() throws Exception
    {
        assertThat(queriedAppContexts(newContext("/"), "localhost"), is(List.of("server ", "null ")));
    }

    @Test
    public void testAModuleRegisteredWithTheAppContextItComputesIsUsed() throws Exception
    {
        assertModuleRegisteredByAThirdPartyIsUsed("/ctx");
    }

    @Test
    public void testAModuleRegisteredWithTheAppContextItComputesForTheRootContextIsUsed() throws Exception
    {
        assertModuleRegisteredByAThirdPartyIsUsed("/");
    }

    @Test
    public void testAModuleRegisteredWithTheAppContextItComputesIsUsedWhenAServerNameIsConfigured() throws Exception
    {
        _jaspiAuthenticatorFactory.setServerName("named.example.org");
        assertModuleRegisteredByAThirdPartyIsUsed("/ctx");
    }

    @Test
    public void testAProviderRegisteredForServerAndTheContextPathIsUsedWhenThereIsNoVirtualHost() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        _server.setHandler(securedContext("/ctx"));
        registerProviderFor("server /ctx");
        _server.start();

        assertThat(appContextsOfAnAuthenticatedRequest(), is(List.of("server /ctx")));
    }

    @Test
    public void testServerAndTheContextPathIsPreferredToTheAppContextOfTheProfile() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        _server.setHandler(securedContext("/ctx"));
        registerProviderFor("null /ctx");
        registerProviderFor("server /ctx");
        _server.start();

        assertThat(appContextsOfAnAuthenticatedRequest(), is(List.of("server /ctx")));
    }

    @Test
    public void testAProviderRegisteredForServerAndTheContextPathIsNotUsedByAContextWithAVirtualHost() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        ContextHandler context = securedContext("/ctx");
        context.setVirtualHosts(new String[]{"vhost.example.org"});
        _server.setHandler(context);
        registerProviderFor("server /ctx");
        _server.start();

        String response = _connector.getResponse("GET /ctx/test HTTP/1.1\r\nHost: vhost.example.org\r\n" +
            "X-Forwarded-User: user\r\nConnection: close\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
    }

    @Test
    public void testAProviderRegisteredAfterTheContextStartedIsUsed() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        _server.setHandler(securedContext("/ctx"));
        _server.start();

        // The authenticator is in place before any provider is registered, and authenticates nobody.
        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"), startsWith("HTTP/1.1 403"));

        registerProviderFor("null /ctx");
        assertThat(appContextsOfAnAuthenticatedRequest(), is(List.of("server /ctx", "null /ctx")));

        // A registration for the preferred identifier takes over from the one in use.
        registerProviderFor("server /ctx");
        assertThat(appContextsOfAnAuthenticatedRequest(), is(List.of("server /ctx")));
    }

    /**
     * A context that no provider is registered for looks one up once and keeps that answer, like
     * one that found a provider, rather than asking the factory again on every request. The miss
     * is stamped with the registration count that produced it, so the registration that answers it
     * invalidates it.
     */
    @Test
    public void testNoProviderIsLookedUpOnceAndFoundAgainWhenOneIsRegistered() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        _server.setHandler(securedContext("/ctx"));
        _server.start();
        _authConfigFactory.clearQueries();

        for (int i = 0; i < 3; i++)
        {
            assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"),
                startsWith("HTTP/1.1 403"));
        }
        assertThat(_authConfigFactory.getAppContexts(), is(List.of("server /ctx", "null /ctx")));

        registerProviderFor("null /ctx");

        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"),
            startsWith("HTTP/1.1 200 OK"));
    }

    private void registerProviderFor(String appContext)
    {
        _authConfigFactory.registerConfigProvider(new JaspiAuthConfigProvider(new HttpHeaderAuthModule()),
            JaspiAuthenticatorFactory.MESSAGE_LAYER, appContext, null);
    }

    /**
     * Make a request the registered module authenticates, and return the appContexts the
     * {@link JaspiAuthenticator} looked a provider up with while serving it, in the order it asked.
     */
    private List<String> appContextsOfAnAuthenticatedRequest() throws Exception
    {
        _authConfigFactory.clearQueries();
        String response = _connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        return _authConfigFactory.getAppContexts().stream().distinct().toList();
    }

    /**
     * Register a module the way the Servlet Container Profile tells a third party to, building the
     * application context identifier from the servlet API rather than from anything of Jetty's, and
     * check the authenticator of that context finds it.
     */
    private void assertModuleRegisteredByAThirdPartyIsUsed(String contextPath) throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        ContextHandler context = securedContext(contextPath);
        _server.setHandler(context);
        _server.start();

        ServletContext servletContext = context.getServletContext();
        String appContext = servletContext.getVirtualServerName() + " " + servletContext.getContextPath();
        _authConfigFactory.registerConfigProvider(new JaspiAuthConfigProvider(new HttpHeaderAuthModule()),
            JaspiAuthenticatorFactory.MESSAGE_LAYER, appContext, "a third party registration");

        String path = "/".equals(contextPath) ? "/test" : contextPath + "/test";
        String response = _connector.getResponse("GET " + path + " HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    /**
     * Build a context, choose an authenticator for it as {@code SecurityHandler.doStart} would,
     * and return the choice without starting anything.
     */
    private Authenticator authenticatorFor(String authMethod, LoginService loginService)
    {
        ContextHandler context = newContext("/ctx");
        ConstraintSecurityHandler security = (ConstraintSecurityHandler)context.getHandler();
        security.setAuthMethod(authMethod);
        if (loginService != null)
            security.setLoginService(loginService);
        _server.setHandler(context);

        return _jaspiAuthenticatorFactory.getAuthenticator(_server, context.getServletContext(), security,
            security.getIdentityService(), security.getLoginService());
    }

    /**
     * Start the server with one context and make a request to it, returning the appContexts the
     * {@link JaspiAuthenticator} asked the {@link AuthConfigFactory} about, in the order it asked.
     */
    private List<String> queriedAppContexts(ContextHandler context, String host) throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        _server.setHandler(context);
        _server.start();

        // Choosing the authenticator looks the identifiers up as well; the request is where the authenticator itself uses them.
        _authConfigFactory.clearQueries();

        String contextPath = context.getContextPath();
        String path = "/".equals(contextPath) ? "/test" : contextPath + "/test";
        String response = _connector.getResponse("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        return _authConfigFactory.getAppContexts().stream().distinct().toList();
    }

    /**
     * A context with a constraint on every path, so that an unauthenticated request to it is
     * forbidden rather than served.
     */
    private ContextHandler securedContext(String contextPath)
    {
        ContextHandler context = newContext(contextPath);
        ServletConstraint constraint = new ServletConstraint("All", "users");
        constraint.setAuthenticate(true);
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/*");
        mapping.setConstraint(constraint);
        ((ConstraintSecurityHandler)context.getHandler()).addConstraintMapping(mapping);
        return context;
    }

    /**
     * Declare an auth-method with a LoginService for a context, as a web.xml login-config does.
     */
    private static ContextHandler declaredBasicAuth(ContextHandler context)
    {
        ConstraintSecurityHandler security = (ConstraintSecurityHandler)context.getHandler();
        security.setAuthMethod(Authenticator.BASIC_AUTH);
        security.setLoginService(new EmptyLoginService());
        return context;
    }

    private ContextHandler newContext(String contextPath)
    {
        ContextHandler context = new ContextHandler();
        context.setContextPath(contextPath);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        context.setHandler(security);
        security.setAuthenticatorFactory(_jaspiAuthenticatorFactory);
        security.setHandler(new JaspiTest.TestHandler());
        return context;
    }

    private static Subject subjectNamed(String name)
    {
        Subject subject = new Subject();
        subject.getPrincipals().add(new UserPrincipal(name, null));
        return subject;
    }

    /**
     * Run an action with {@code System.err} replaced, which is where the default jetty-slf4j-impl
     * appender writes, and return what it logged.
     */
    private static String capturingLogs(Runnable action)
    {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream old = System.err;
        try
        {
            System.setErr(new PrintStream(captured, true));
            action.run();
        }
        finally
        {
            System.setErr(old);
        }
        return captured.toString();
    }

    private static class NoOpAuthenticator implements Authenticator
    {
        @Override
        public void setConfiguration(AuthConfiguration configuration)
        {
        }

        @Override
        public String getAuthMethod()
        {
            return null;
        }

        @Override
        public void prepareRequest(ServletRequest request)
        {
        }

        @Override
        public Authentication validateRequest(ServletRequest request, ServletResponse response, boolean mandatory)
        {
            return Authentication.UNAUTHENTICATED;
        }

        @Override
        public boolean secureResponse(ServletRequest request, ServletResponse response, boolean mandatory, Authentication.User validatedUser)
        {
            return true;
        }
    }

    private static class RecordingAuthConfigFactory extends DefaultAuthConfigFactory
    {
        private final List<String> _layers = new CopyOnWriteArrayList<>();
        private final List<String> _appContexts = new CopyOnWriteArrayList<>();

        @Override
        public AuthConfigProvider getConfigProvider(String layer, String appContext, RegistrationListener listener)
        {
            _layers.add(layer);
            _appContexts.add(appContext);
            return super.getConfigProvider(layer, appContext, listener);
        }

        List<String> getLayers()
        {
            return _layers;
        }

        List<String> getAppContexts()
        {
            return _appContexts;
        }

        void clearQueries()
        {
            _layers.clear();
            _appContexts.clear();
        }
    }
}
