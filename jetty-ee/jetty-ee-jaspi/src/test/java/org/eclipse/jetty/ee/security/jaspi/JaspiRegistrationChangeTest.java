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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.MessagePolicy;
import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.callback.GroupPrincipalCallback;
import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.RegistrationListener;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.module.ServerAuthModule;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee.security.jaspi.provider.JaspiAuthConfigProvider;
import org.eclipse.jetty.ee.servlet.ServletContextHandler;
import org.eclipse.jetty.ee.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A context resolves the application context identifiers it answers to on every request, so a
 * registration can be made, replaced or removed while it is serving. These are the cases where
 * that must not leave the authenticator using a provider that has been removed, secured by a
 * provider that never saw the request, or its listener attached to a factory that outlives it.
 */
@Isolated("Sets the global AuthConfigFactory")
public class JaspiRegistrationChangeTest
{
    private static final String APP_CONTEXT = "server /ctx";
    private static final String ALIAS_APP_CONTEXT = "null /ctx";

    private final List<String> _secured = new CopyOnWriteArrayList<>();
    private final AtomicReference<Runnable> _duringRequest = new AtomicReference<>(() ->
    {
    });

    private TestAuthConfigFactory _authConfigFactory;
    private Server _server;
    private LocalConnector _connector;
    private ServletContextHandler _context;

    @BeforeEach
    public void before() throws Exception
    {
        _authConfigFactory = new TestAuthConfigFactory();
        AuthConfigFactory.setFactory(_authConfigFactory);

        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        _context = new ServletContextHandler();
        _context.setContextPath("/ctx");
        _context.addServlet(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                _duringRequest.get().run();
                response.getWriter().println("All OK");
            }
        }, "/");
        _server.setHandler(_context);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        _context.setSecurityHandler(security);
        security.setAuthenticatorFactory(new JaspiAuthenticatorFactory());
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/jaspi/*");
        mapping.setConstraint(new Constraint.Builder().name("All").roles("users").build());
        security.addConstraintMapping(mapping);

        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
        AuthConfigFactory.setFactory(null);
    }

    /**
     * A registration made while a request is resolving the identifiers must not be left without a
     * listener attached to it, or nothing invalidates the authenticator when it is removed again.
     */
    @Test
    public void testRegistrationRemovedAfterRacingAnAllMissLookupStopsAuthenticating() throws Exception
    {
        _authConfigFactory.pauseNextAllMissLookup();
        CompletableFuture<String> inFlight = CompletableFuture.supplyAsync(this::response);
        _authConfigFactory.awaitPaused();

        String registrationId = register(ALIAS_APP_CONTEXT, "A");
        assertThat(response(), startsWith("HTTP/1.1 200 OK"));

        _authConfigFactory.resume();
        assertThat(inFlight.get(30, TimeUnit.SECONDS), startsWith("HTTP/1.1 403 Forbidden"));

        assertTrue(_authConfigFactory.removeRegistration(registrationId));
        assertThat(response(), startsWith("HTTP/1.1 403 Forbidden"));
    }

    /**
     * Resolving the ServerAuthConfig calls the provider, which may take any length of time, so a
     * registration can be removed while that call is in progress. The config it returns must not
     * be used afterwards: the listener that would have invalidated it has already been notified
     * and detached, so nothing would ever invalidate it again.
     */
    @Test
    public void testRegistrationRemovedWhileItsServerAuthConfigIsResolvedStopsAuthenticating() throws Exception
    {
        String registrationId = registerPausingProvider(APP_CONTEXT, "A");

        CompletableFuture<String> inFlight = CompletableFuture.supplyAsync(this::response);
        _authConfigFactory.awaitPaused();

        assertTrue(_authConfigFactory.removeRegistration(registrationId));

        _authConfigFactory.resume();
        inFlight.get(30, TimeUnit.SECONDS);

        assertThat(response(), startsWith("HTTP/1.1 403 Forbidden"));
    }

    /**
     * The factory is process wide, so an attachment left behind when the context stops holds the
     * authenticator, its ServerAuthConfig and the classloader of the application for good.
     */
    @Test
    public void testListenerIsDetachedWhenTheContextStops() throws Exception
    {
        register(ALIAS_APP_CONTEXT, "A");
        assertThat(response(), startsWith("HTTP/1.1 200 OK"));

        _server.stop();

        assertThat(_authConfigFactory.attachedIdentifiers(), empty());
    }

    /**
     * The authenticator is kept across a restart of its context, so what it resolved before the
     * stop must not stand after it: a registration removed while the context was stopped notified
     * no listener, and the restarted context would serve with the removed module for good.
     */
    @Test
    public void testRegistrationRemovedWhileTheContextWasStoppedStopsAuthenticating() throws Exception
    {
        String registrationId = register(ALIAS_APP_CONTEXT, "A");
        assertThat(response(), startsWith("HTTP/1.1 200 OK"));

        _server.stop();
        assertTrue(_authConfigFactory.removeRegistration(registrationId));
        _server.start();

        assertThat(response(), startsWith("HTTP/1.1 403 Forbidden"));
    }

    /**
     * Having found no provider is cached the same way, so the other direction must work too: a
     * provider registered while the context was stopped is used once it has started again.
     */
    @Test
    public void testAProviderRegisteredWhileTheContextWasStoppedIsUsedWhenItStartsAgain() throws Exception
    {
        assertThat(response(), startsWith("HTTP/1.1 403 Forbidden"));

        _server.stop();
        register(ALIAS_APP_CONTEXT, "A");
        _server.start();

        assertThat(response(), startsWith("HTTP/1.1 200 OK"));
    }

    /**
     * The listener is detached by one added to the context, which discards the listeners added to
     * it since it started, so a restarted context must attach that one again.
     */
    @Test
    public void testListenerIsDetachedWhenTheContextStopsAfterARestart() throws Exception
    {
        _server.stop();
        _server.start();

        register(ALIAS_APP_CONTEXT, "A");
        assertThat(response(), startsWith("HTTP/1.1 200 OK"));

        _server.stop();

        assertThat(_authConfigFactory.attachedIdentifiers(), empty());
    }

    /**
     * The Servlet Container Profile requires secureResponse to be called on the same
     * ServerAuthContext as the validateRequest of that request.
     */
    @Test
    public void testResponseIsSecuredByTheModuleThatValidatedTheRequest() throws Exception
    {
        register(ALIAS_APP_CONTEXT, "A");
        _duringRequest.set(() -> register(APP_CONTEXT, "B"));

        assertThat(response(), startsWith("HTTP/1.1 200 OK"));

        assertThat(_secured, contains("A"));
    }

    /**
     * A registration change has the authenticator resolve its provider again, which for a module
     * given as an instance is the same object: initializing it again would replace the request
     * policy and the callback handler under the messages already being processed with it.
     */
    @Test
    public void testARegistrationChangeDoesNotInitializeTheModuleAgain() throws Exception
    {
        TestAuthModule module = new TestAuthModule("A");
        register(ALIAS_APP_CONTEXT, module);
        assertThat(response(), startsWith("HTTP/1.1 200 OK"));

        // Registered and removed again for another identifier of this context, so the same provider is resolved before and after.
        assertTrue(_authConfigFactory.removeRegistration(register(APP_CONTEXT, "B")));
        assertThat(response(), startsWith("HTTP/1.1 200 OK"));

        assertThat(_secured, contains("A", "A"));
        assertThat(module.initializations(), is(1));
    }

    /**
     * The authenticator is kept across a restart of its context, which gives it a new callback
     * handler and so a new LoginService. A resolution in progress across the restart must not
     * leave the stopped generation's configuration, and its handler, serving the started one.
     */
    @Test
    public void testAConfigResolvedAcrossARestartIsNotServedAfterIt() throws Exception
    {
        TestAuthModule module = new TestAuthModule("A");
        registerPausingProvider(ALIAS_APP_CONTEXT, module);

        CompletableFuture<String> inFlight = CompletableFuture.supplyAsync(this::response);
        _authConfigFactory.awaitPaused();

        _context.stop();
        _context.start();

        _authConfigFactory.resume();
        inFlight.get(30, TimeUnit.SECONDS);
        CallbackHandler stopped = module.callbackHandler();

        assertThat(response(), startsWith("HTTP/1.1 200 OK"));
        assertThat(module.callbackHandler(), not(sameInstance(stopped)));
    }

    /**
     * A resolution in progress when the context stops carries on through the identifiers it has
     * left, and the listener it attaches to one of them is not detached by a stop that has already
     * run: the process wide factory would hold the authenticator, and the application classloader
     * through it, for good.
     */
    @Test
    public void testAResolutionInFlightWhenTheContextStopsDoesNotAttachTheListener() throws Exception
    {
        register(ALIAS_APP_CONTEXT, "A");
        _authConfigFactory.pauseAfterLookupOf(APP_CONTEXT);

        CompletableFuture<String> inFlight = CompletableFuture.supplyAsync(this::response);
        _authConfigFactory.awaitPaused();

        _context.stop();

        _authConfigFactory.resume();
        inFlight.get(30, TimeUnit.SECONDS);

        assertThat(_authConfigFactory.attachedIdentifiers(), empty());
    }

    /**
     * @return the id of a registration whose provider pauses the first ServerAuthConfig asked of
     * it, which is the window in which the registration can be removed.
     */
    private String registerPausingProvider(String appContext, ServerAuthModule serverAuthModule)
    {
        AuthConfigProvider provider = new JaspiAuthConfigProvider(serverAuthModule)
        {
            @Override
            public ServerAuthConfig getServerAuthConfig(String layer, String appContext, CallbackHandler handler) throws AuthException
            {
                _authConfigFactory.pause();
                return super.getServerAuthConfig(layer, appContext, handler);
            }
        };
        return _authConfigFactory.registerConfigProvider(provider, JaspiAuthenticatorFactory.MESSAGE_LAYER, appContext, "a test provider");
    }

    private String registerPausingProvider(String appContext, String moduleName)
    {
        return registerPausingProvider(appContext, new TestAuthModule(moduleName));
    }

    private String register(String appContext, ServerAuthModule serverAuthModule)
    {
        AuthConfigProvider provider = new JaspiAuthConfigProvider(serverAuthModule);
        return _authConfigFactory.registerConfigProvider(provider, JaspiAuthenticatorFactory.MESSAGE_LAYER, appContext, "a test provider");
    }

    private String register(String appContext, String moduleName)
    {
        return register(appContext, new TestAuthModule(moduleName));
    }

    private String response()
    {
        try
        {
            return _connector.getResponse("GET /ctx/jaspi/test HTTP/1.0\r\n\r\n");
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private class TestAuthModule implements ServerAuthModule
    {
        private final String _name;
        private final AtomicInteger _initializations = new AtomicInteger();
        private volatile CallbackHandler _callbackHandler;

        private TestAuthModule(String name)
        {
            _name = name;
        }

        private int initializations()
        {
            return _initializations.get();
        }

        private CallbackHandler callbackHandler()
        {
            return _callbackHandler;
        }

        @Override
        public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy, CallbackHandler callbackHandler, @SuppressWarnings("rawtypes") Map options)
        {
            _initializations.incrementAndGet();
            _callbackHandler = callbackHandler;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public Class[] getSupportedMessageTypes()
        {
            return new Class[]{HttpServletRequest.class, HttpServletResponse.class};
        }

        @Override
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
        {
            try
            {
                _callbackHandler.handle(new Callback[]
                {
                    new CallerPrincipalCallback(clientSubject, "user"),
                    new GroupPrincipalCallback(clientSubject, new String[]{"users"})
                });
                return AuthStatus.SUCCESS;
            }
            catch (Exception e)
            {
                throw new AuthException(e.getMessage());
            }
        }

        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject)
        {
            _secured.add(_name);
            return AuthStatus.SEND_SUCCESS;
        }

        @Override
        public void cleanSubject(MessageInfo messageInfo, Subject subject)
        {
        }
    }

    /**
     * A factory that can hold a lookup at the point where it has missed every identifier of the
     * context, and that remembers the listeners attached to it so a test can see what is left.
     */
    private static class TestAuthConfigFactory extends DefaultAuthConfigFactory
    {
        private final Set<RegistrationListener> _listeners = ConcurrentHashMap.newKeySet();
        private final CountDownLatch _paused = new CountDownLatch(1);
        private final CountDownLatch _resume = new CountDownLatch(1);
        private final AtomicBoolean _pauseNextAllMiss = new AtomicBoolean();
        private volatile String _pauseAfterLookupOf;

        @Override
        public AuthConfigProvider getConfigProvider(String layer, String appContext, RegistrationListener listener)
        {
            if (listener != null)
                _listeners.add(listener);

            AuthConfigProvider provider = super.getConfigProvider(layer, appContext, listener);
            if (provider == null && ALIAS_APP_CONTEXT.equals(appContext) && _pauseNextAllMiss.compareAndSet(true, false))
                pause();
            if (appContext.equals(_pauseAfterLookupOf))
            {
                _pauseAfterLookupOf = null;
                pause();
            }
            return provider;
        }

        /**
         * Hold the calling thread until the test resumes it, having let the test know it is held.
         */
        private void pause()
        {
            _paused.countDown();
            await(_resume);
        }

        private void pauseNextAllMissLookup()
        {
            _pauseNextAllMiss.set(true);
        }

        /**
         * Hold the lookup of an identifier once it has been made, which is the window in which
         * the identifiers left to try are resolved with the context already stopping.
         */
        private void pauseAfterLookupOf(String appContext)
        {
            _pauseAfterLookupOf = appContext;
        }

        private void awaitPaused()
        {
            await(_paused);
        }

        private void resume()
        {
            _resume.countDown();
        }

        /**
         * @return the identifiers the listeners seen by this factory are still attached to, detaching them.
         */
        private List<String> attachedIdentifiers()
        {
            List<String> attached = new ArrayList<>();
            for (RegistrationListener listener : _listeners)
            {
                attached.addAll(List.of(detachListener(listener, JaspiAuthenticatorFactory.MESSAGE_LAYER, null)));
            }
            return attached;
        }

        private static void await(CountDownLatch latch)
        {
            try
            {
                if (!latch.await(30, TimeUnit.SECONDS))
                    throw new IllegalStateException("timeout");
            }
            catch (InterruptedException e)
            {
                throw new IllegalStateException(e);
            }
        }
    }
}
