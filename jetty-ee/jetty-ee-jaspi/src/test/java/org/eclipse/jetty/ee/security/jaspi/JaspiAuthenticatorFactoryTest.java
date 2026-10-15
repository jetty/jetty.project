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

package org.eclipse.jetty.ee.security.jaspi;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.security.auth.Subject;

import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.RegistrationListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.eclipse.jetty.ee.security.jaspi.provider.JaspiAuthConfigProvider;
import org.eclipse.jetty.ee.servlet.ServletContextHandler;
import org.eclipse.jetty.ee.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.EmptyLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;

/**
 * The policy {@link JaspiAuthenticatorFactory#getAuthenticator(Server, org.eclipse.jetty.server.Context,
 * Authenticator.Configuration)} applies when choosing between the declared auth-method and Jakarta
 * Authentication, and the appContext it gives the {@link JaspiAuthenticator} to look a provider up with.
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
        _openIdAuthenticator = new Authenticator.NoOp();
        _openIdAuthenticatorFactory = (server, context, configuration) ->
            Authenticator.OPENID_AUTH.equals(configuration.getAuthenticationType()) ? _openIdAuthenticator : null;

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
        ServletContextHandler context = declaredBasicAuth(securedContext("/ctx"));
        _server.setHandler(context);
        _server.start();

        // The authenticator is in place for a provider that may yet be registered, but the declared auth-method serves requests until there is one.
        ConstraintSecurityHandler security = (ConstraintSecurityHandler)context.getSecurityHandler();
        assertThat(security.getAuthenticator(), instanceOf(JaspiAuthenticator.class));
        assertThat(security.getAuthenticationType(), is(Authenticator.BASIC_AUTH));
        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"),
            containsString("WWW-Authenticate: Basic"));
    }

    @Test
    public void testAProviderRegisteredBeforeStartIsUsedInsteadOfTheDeclaredAuthMethod() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        ServletContextHandler context = securedContext("/ctx");
        ConstraintSecurityHandler security = (ConstraintSecurityHandler)context.getSecurityHandler();
        security.setAuthenticationType(Authenticator.BASIC_AUTH);
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
    public void testAProviderRegisteredAfterStartIsUsedInsteadOfTheDeclaredAuthMethod() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        ServletContextHandler context = declaredBasicAuth(securedContext("/ctx"));
        _server.setHandler(context);
        _server.start();

        // Nothing is registered yet, so the declared auth-method serves the request.
        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"),
            containsString("WWW-Authenticate: Basic"));

        _authConfigFactory.registerServerAuthModule(new HttpHeaderAuthModule(), context.getServletContext());

        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"),
            startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testAModuleRegisteredFromAContextListenerIsUsedInsteadOfTheDeclaredAuthMethod() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        ServletContextHandler context = declaredBasicAuth(securedContext("/ctx"));
        context.addEventListener(new ServletContextListener()
        {
            @Override
            public void contextInitialized(ServletContextEvent event)
            {
                _authConfigFactory.registerServerAuthModule(new HttpHeaderAuthModule(), event.getServletContext());
            }
        });
        _server.setHandler(context);
        _server.start();

        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"),
            startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testTheDeclaredAuthMethodServesAgainWhenTheRegistrationIsRemoved() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        ServletContextHandler context = declaredBasicAuth(securedContext("/ctx"));
        _server.setHandler(context);
        _server.start();

        String registrationId = _authConfigFactory.registerServerAuthModule(new HttpHeaderAuthModule(), context.getServletContext());
        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"),
            startsWith("HTTP/1.1 200 OK"));

        assertThat(_authConfigFactory.removeRegistration(registrationId), is(true));

        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"),
            containsString("WWW-Authenticate: Basic"));
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
        ServletContextHandler context = newContext("/ctx");
        ConstraintSecurityHandler security = (ConstraintSecurityHandler)context.getSecurityHandler();
        security.setAuthenticationType(Authenticator.BASIC_AUTH);
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

        ServletContextHandler context = newContext("/ctx");
        ConstraintSecurityHandler security = (ConstraintSecurityHandler)context.getSecurityHandler();
        // With no factory set, SecurityHandler.doStart walks the discovered ones in order.
        security.setAuthenticatorFactory(null);
        security.setAuthenticationType(Authenticator.OPENID_AUTH);
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
        ServletContextHandler context = newContext("/ctx");
        context.setVirtualHosts(List.of("vhost.example.org"));
        assertThat(queriedAppContexts(context, "vhost.example.org"), is(List.of("vhost.example.org /ctx")));
    }

    @Test
    public void testAppContextDoesNotUseTheServiceSubject() throws Exception
    {
        // The service Subject does not name a virtual server, and registerServerAuthModule cannot see it, so a module registered for it would not be found.
        _jaspiAuthenticatorFactory.setServiceSubject(subjectNamed("subject.example.org"));
        _server.addBean(subjectNamed("bean.example.org"));
        assertThat(queriedAppContexts(newContext("/ctx"), "localhost"), is(List.of("server /ctx", "null /ctx")));
    }

    @Test
    public void testAppContextPrefersTheServerNameToTheVirtualHost() throws Exception
    {
        _jaspiAuthenticatorFactory.setServerName("named.example.org");
        ServletContextHandler context = newContext("/ctx");
        context.setVirtualHosts(List.of("vhost.example.org"));
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
        ServletContextHandler context = securedContext("/ctx");
        context.setVirtualHosts(List.of("vhost.example.org"));
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
        ServletContextHandler context = securedContext("/ctx");
        _server.setHandler(context);
        _server.start();
        _authConfigFactory.clearQueries();

        for (int i = 0; i < 3; i++)
        {
            assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"),
                startsWith("HTTP/1.1 403"));
        }
        assertThat(_authConfigFactory.getAppContexts(), is(List.of("server /ctx", "null /ctx")));

        _authConfigFactory.registerServerAuthModule(new HttpHeaderAuthModule(), context.getServletContext());

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
        ServletContextHandler context = securedContext(contextPath);
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

    @Test
    public void testAServerAuthModuleRegisteredForAContextIsFoundByItsAuthenticator() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        _server.addBean(subjectNamed("bean.example.org"));

        ServletContextHandler context = securedContext("/ctx");
        _server.setHandler(context);
        _server.start();

        // The appContext this registration is keyed on is the one the authenticator looks up.
        _authConfigFactory.registerServerAuthModule(new HttpHeaderAuthModule(), context.getServletContext());

        String response = _connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testAServerAuthModuleRegisteredForAContextIsFoundWhenAServerNameIsConfigured() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);
        _jaspiAuthenticatorFactory.setServerName("named.example.org");

        ServletContextHandler context = securedContext("/ctx");
        _server.setHandler(context);
        _server.start();

        _authConfigFactory.registerServerAuthModule(new HttpHeaderAuthModule(), context.getServletContext());

        String response = _connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testAServerAuthModuleRegisteredForAContextIsNotUsedByItsReplacement() throws Exception
    {
        AuthConfigFactory.setFactory(_authConfigFactory);

        ServletContextHandler context = securedContext("/ctx");
        _server.setHandler(context);
        _server.start();
        _authConfigFactory.registerServerAuthModule(new HttpHeaderAuthModule(), context.getServletContext());
        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"), startsWith("HTTP/1.1 200 OK"));

        // Another context at the same path registers no module of its own, so its requests must not be authenticated by the first one's module.
        _server.stop();
        _server.setHandler(securedContext("/ctx"));
        _server.start();

        assertThat(_authConfigFactory.getRegistrationIDs(null), emptyArray());
        assertThat(_connector.getResponse("GET /ctx/test HTTP/1.0\r\nX-Forwarded-User: user\r\n\r\n"), startsWith("HTTP/1.1 403"));
    }

    /**
     * Build a context, choose an authenticator for it as {@code SecurityHandler.doStart} would,
     * and return the choice without starting anything.
     */
    private Authenticator authenticatorFor(String authenticationType, LoginService loginService)
    {
        ServletContextHandler context = newContext("/ctx");
        ConstraintSecurityHandler security = (ConstraintSecurityHandler)context.getSecurityHandler();
        security.setAuthenticationType(authenticationType);
        if (loginService != null)
            security.setLoginService(loginService);
        _server.setHandler(context);

        return _jaspiAuthenticatorFactory.getAuthenticator(_server, context.getContext(), security);
    }

    /**
     * Start the server with one context and make a request to it, returning the appContexts the
     * {@link JaspiAuthenticator} asked the {@link AuthConfigFactory} about, in the order it asked.
     */
    private List<String> queriedAppContexts(ServletContextHandler context, String host) throws Exception
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
    private ServletContextHandler securedContext(String contextPath)
    {
        ServletContextHandler context = newContext(contextPath);
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/*");
        mapping.setConstraint(new Constraint.Builder().roles("users").build());
        ((ConstraintSecurityHandler)context.getSecurityHandler()).addConstraintMapping(mapping);
        return context;
    }

    /**
     * Declare an auth-method with a LoginService for a context, as a web.xml login-config does.
     */
    private static ServletContextHandler declaredBasicAuth(ServletContextHandler context)
    {
        ConstraintSecurityHandler security = (ConstraintSecurityHandler)context.getSecurityHandler();
        security.setAuthenticationType(Authenticator.BASIC_AUTH);
        security.setLoginService(new EmptyLoginService());
        return context;
    }

    private ServletContextHandler newContext(String contextPath)
    {
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath(contextPath);
        context.addServlet(new JaspiTest.TestServlet(), "/");

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        context.setSecurityHandler(security);
        security.setAuthenticatorFactory(_jaspiAuthenticatorFactory);
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
