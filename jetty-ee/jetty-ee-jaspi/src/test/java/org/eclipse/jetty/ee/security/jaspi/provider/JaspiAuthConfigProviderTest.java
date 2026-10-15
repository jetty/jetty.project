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

package org.eclipse.jetty.ee.security.jaspi.provider;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.MessagePolicy;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.config.ServerAuthContext;
import jakarta.security.auth.message.module.ServerAuthModule;
import org.eclipse.jetty.ee.security.jaspi.JaspiMessageInfo;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * The request policy a {@link ServerAuthModule} is initialized with, which the Servlet Container
 * Profile requires to say whether authentication is mandatory for the message being processed.
 */
public class JaspiAuthConfigProviderTest
{
    private static final String MESSAGE_LAYER = "HttpServlet";
    private static final String APP_CONTEXT = "server /ctx";

    @Test
    public void testAModuleNamedByClassIsInstantiatedPerAuthContext() throws Exception
    {
        ServerAuthConfig authConfig = new JaspiAuthConfigProvider(PolicyRecordingAuthModule.class.getName(), null)
            .getServerAuthConfig(MESSAGE_LAYER, APP_CONTEXT, null);

        ServerAuthContext mandatory = authConfig.getAuthContext("true", null, Map.of());
        ServerAuthContext optional = authConfig.getAuthContext("false", null, Map.of());

        assertThat(mandatory, not(sameInstance(optional)));
        assertThat(authConfig.getAuthContext("true", null, Map.of()), sameInstance(mandatory));

        // Asking for the other context does not change the policy this one's module holds.
        assertThat(isMandatory(authConfig, true), is(true));
        assertThat(isMandatory(authConfig, false), is(false));
        assertThat(isMandatory(authConfig, true), is(true));
    }

    @Test
    public void testAModuleGivenAsAnInstanceIsInitializedOncePerServerAuthConfig() throws Exception
    {
        PolicyRecordingAuthModule serverAuthModule = new PolicyRecordingAuthModule();
        JaspiAuthConfigProvider provider = new JaspiAuthConfigProvider(serverAuthModule);
        ServerAuthConfig authConfig = provider.getServerAuthConfig(MESSAGE_LAYER, APP_CONTEXT, null);

        // The one instance cannot hold a policy for each context, so its policy reports the message.
        assertThat(isMandatory(authConfig, true), is(true));
        assertThat(isMandatory(authConfig, false), is(false));
        assertThat(isMandatory(authConfig, true), is(true));

        assertThat(serverAuthModule.getInitializations(), is(1));

        // The configuration is kept, so resolving the provider again does not initialize the shared instance again.
        assertThat(provider.getServerAuthConfig(MESSAGE_LAYER, APP_CONTEXT, null), sameInstance(authConfig));
        assertThat(serverAuthModule.getInitializations(), is(1));
    }

    @Test
    public void testEachApplicationContextGetsItsOwnServerAuthConfig() throws Exception
    {
        JaspiAuthConfigProvider provider = new JaspiAuthConfigProvider(PolicyRecordingAuthModule.class.getName(), null);

        ServerAuthConfig authConfig = provider.getServerAuthConfig(MESSAGE_LAYER, APP_CONTEXT, null);
        ServerAuthConfig other = provider.getServerAuthConfig(MESSAGE_LAYER, "server /other", null);

        assertThat(other, not(sameInstance(authConfig)));
        assertThat(provider.getServerAuthConfig(MESSAGE_LAYER, APP_CONTEXT, null), sameInstance(authConfig));
    }

    @Test
    public void testANewCallbackHandlerForAnApplicationContextInitializesTheModuleAgain() throws Exception
    {
        PolicyRecordingAuthModule serverAuthModule = new PolicyRecordingAuthModule();
        JaspiAuthConfigProvider provider = new JaspiAuthConfigProvider(serverAuthModule);
        CallbackHandler started = callbacks ->
        {
        };
        CallbackHandler restarted = callbacks ->
        {
        };

        ServerAuthConfig authConfig = provider.getServerAuthConfig(MESSAGE_LAYER, APP_CONTEXT, started);
        // A context that starts again presents a new callback handler, and so a new LoginService, which the module must be given.
        ServerAuthConfig replacement = provider.getServerAuthConfig(MESSAGE_LAYER, APP_CONTEXT, restarted);

        assertThat(replacement, not(sameInstance(authConfig)));
        assertThat(serverAuthModule.getCallbackHandler(), sameInstance(restarted));
        assertThat(serverAuthModule.getInitializations(), is(2));
    }

    @Test
    public void testAModuleGivenAsAnInstanceGetsThePolicyOfEachConcurrentMessage() throws Exception
    {
        PolicyRecordingAuthModule serverAuthModule = new PolicyRecordingAuthModule();
        // Both messages are in the module before either reads the policy it was initialized with.
        serverAuthModule.setBarrier(new CyclicBarrier(2));
        ServerAuthConfig authConfig = new JaspiAuthConfigProvider(serverAuthModule)
            .getServerAuthConfig(MESSAGE_LAYER, APP_CONTEXT, null);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            Future<Boolean> mandatory = executor.submit(() -> isMandatory(authConfig, true));
            Future<Boolean> optional = executor.submit(() -> isMandatory(authConfig, false));

            assertThat(mandatory.get(10, TimeUnit.SECONDS), is(true));
            assertThat(optional.get(10, TimeUnit.SECONDS), is(false));
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    @Test
    public void testAModuleGivenAsAnInstanceIsInitializedWithTheGivenOptions() throws Exception
    {
        ServerAuthConfig authConfig = new JaspiAuthConfigProvider(new PolicyRecordingAuthModule(), Map.of("name", "value"))
            .getServerAuthConfig(MESSAGE_LAYER, APP_CONTEXT, null);

        assertThat(options(authConfig.getAuthContext("true", null, Map.of())), is(Map.of("name", "value")));
    }

    @Test
    @SuppressWarnings("removal")
    public void testAModuleNamedByClassWithNoOptionsIsGivenAnEmptyMap() throws Exception
    {
        ServerAuthConfig authConfig = new JaspiAuthConfigProvider(PolicyRecordingAuthModule.class.getName())
            .getServerAuthConfig(MESSAGE_LAYER, APP_CONTEXT, null);

        assertThat(options(authConfig.getAuthContext("true", null, Map.of())), is(Map.of()));
    }

    @Test
    public void testAModuleReEnteringTheContextKeepsThePolicyOfTheMessageItIsIn() throws Exception
    {
        PolicyRecordingAuthModule serverAuthModule = new PolicyRecordingAuthModule();
        ServerAuthConfig authConfig = new JaspiAuthConfigProvider(serverAuthModule)
            .getServerAuthConfig(MESSAGE_LAYER, APP_CONTEXT, null);
        // A module calling HttpServletRequest.logout() in validateRequest re-enters at cleanSubject.
        serverAuthModule.setReEntered(authConfig.getAuthContext("true", null, Map.of()));

        assertThat(isMandatory(authConfig, true), is(true));
    }

    /**
     * Process a message with the context the configuration gives it, as the authenticator does.
     *
     * @param authConfig the configuration to process the message with.
     * @param mandatory whether authentication is mandatory for the message.
     * @return what the module of that context was told about the message by its request policy.
     */
    private static boolean isMandatory(ServerAuthConfig authConfig, boolean mandatory) throws Exception
    {
        MessageInfo messageInfo = new TestMessageInfo();
        if (mandatory)
            messageInfo.getMap().put(JaspiMessageInfo.MANDATORY_KEY, "true");

        ServerAuthContext authContext = authConfig.getAuthContext(authConfig.getAuthContextID(messageInfo), null, Map.of());
        authContext.validateRequest(messageInfo, new Subject(), null);
        return Boolean.parseBoolean((String)messageInfo.getMap().get(PolicyRecordingAuthModule.MANDATORY));
    }

    /**
     * @param authContext the context to process a message with.
     * @return the options the module of that context was initialized with.
     */
    private static Map<?, ?> options(ServerAuthContext authContext) throws Exception
    {
        MessageInfo messageInfo = new TestMessageInfo();
        authContext.validateRequest(messageInfo, new Subject(), null);
        return (Map<?, ?>)messageInfo.getMap().get(PolicyRecordingAuthModule.OPTIONS);
    }

    public static class PolicyRecordingAuthModule implements ServerAuthModule
    {
        static final String MANDATORY = "mandatory";
        static final String OPTIONS = "options";

        private final AtomicInteger _initializations = new AtomicInteger();
        private volatile CallbackHandler _callbackHandler;
        private volatile CyclicBarrier _barrier;
        private volatile ServerAuthContext _reEntered;
        private MessagePolicy _requestPolicy;
        private Map<String, Object> _options;

        void setBarrier(CyclicBarrier barrier)
        {
            _barrier = barrier;
        }

        /**
         * @param authContext a context to call from inside {@link #validateRequest}, as a module
         * re-entering the container does.
         */
        void setReEntered(ServerAuthContext authContext)
        {
            _reEntered = authContext;
        }

        int getInitializations()
        {
            return _initializations.get();
        }

        CallbackHandler getCallbackHandler()
        {
            return _callbackHandler;
        }

        @Override
        public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy, CallbackHandler handler, Map<String, Object> options)
        {
            _initializations.incrementAndGet();
            _callbackHandler = handler;
            _requestPolicy = requestPolicy;
            _options = options;
        }

        @Override
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
        {
            await();
            ServerAuthContext reEntered = _reEntered;
            if (reEntered != null)
                reEntered.cleanSubject(messageInfo, clientSubject);
            messageInfo.getMap().put(MANDATORY, String.valueOf(_requestPolicy.isMandatory()));
            messageInfo.getMap().put(OPTIONS, _options);
            return AuthStatus.SUCCESS;
        }

        private void await()
        {
            CyclicBarrier barrier = _barrier;
            if (barrier == null)
                return;
            try
            {
                barrier.await(10, TimeUnit.SECONDS);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject)
        {
            return AuthStatus.SEND_SUCCESS;
        }

        @Override
        public void cleanSubject(MessageInfo messageInfo, Subject subject)
        {
        }

        @Override
        public Class<?>[] getSupportedMessageTypes()
        {
            return new Class<?>[0];
        }
    }

    private static class TestMessageInfo implements MessageInfo
    {
        private final Map<String, Object> _map = new HashMap<>();
        private Object _request;
        private Object _response;

        @Override
        public Map<String, Object> getMap()
        {
            return _map;
        }

        @Override
        public Object getRequestMessage()
        {
            return _request;
        }

        @Override
        public Object getResponseMessage()
        {
            return _response;
        }

        @Override
        public void setRequestMessage(Object request)
        {
            _request = request;
        }

        @Override
        public void setResponseMessage(Object response)
        {
            _response = response;
        }
    }
}
