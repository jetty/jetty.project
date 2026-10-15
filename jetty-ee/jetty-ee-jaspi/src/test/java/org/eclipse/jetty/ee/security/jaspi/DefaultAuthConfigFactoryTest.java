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

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.RegistrationListener;
import jakarta.servlet.ServletContext;
import org.eclipse.jetty.ee.security.jaspi.provider.JaspiAuthConfigProvider;
import org.eclipse.jetty.ee.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DefaultAuthConfigFactoryTest
{

    private static final String MESSAGE_LAYER = "HttpServlet";

    private final String jettyAuthConfigProvider = "org.eclipse.jetty.ee.security.jaspi.provider.JaspiAuthConfigProvider";
    private final String appContext = "server /test";

    private final Map<String, String> serverAuthModuleProperties = Map.of("ServerAuthModule",
            "org.eclipse.jetty.ee.security.jaspi.modules.BasicAuthenticationAuthModule", "AppContextID", appContext,
            "org.eclipse.jetty.ee.security.jaspi.modules.RealmName", "TestRealm");

    private final String serverAuthModuleClassName = "org.eclipse.jetty.ee.security.jaspi.modules.BasicAuthenticationAuthModule";
    
    @Test
    public void testRegisterConfigProviderByClassName() throws Exception
    {
        AuthConfigFactory factory = new DefaultAuthConfigFactory();
        String registrationId = factory.registerConfigProvider(jettyAuthConfigProvider,
                serverAuthModuleProperties, MESSAGE_LAYER, appContext, "a test provider");
        AuthConfigProvider registeredProvider = factory.getConfigProvider(MESSAGE_LAYER, appContext, null);
        assertThat(registeredProvider, instanceOf(JaspiAuthConfigProvider.class));
        assertThat(registeredProvider.getServerAuthConfig(MESSAGE_LAYER, appContext, null), notNullValue());

        assertThat(factory.getRegistrationContext(registrationId), notNullValue());
        assertThat(factory.getRegistrationIDs(registeredProvider), arrayContaining(registrationId));
    }

    @Test
    public void testRegisterAuthConfigProviderDirect() throws Exception
    {
        AuthConfigProvider provider = new JaspiAuthConfigProvider(
                serverAuthModuleClassName,
                serverAuthModuleProperties);

        AuthConfigFactory factory = new DefaultAuthConfigFactory();
        String registrationId = factory.registerConfigProvider(provider, MESSAGE_LAYER, appContext, "a test provider");

        AuthConfigProvider registeredProvider = factory.getConfigProvider(MESSAGE_LAYER, appContext, null);
        assertThat(registeredProvider, instanceOf(JaspiAuthConfigProvider.class));
        assertThat(registeredProvider.getServerAuthConfig(MESSAGE_LAYER, appContext, null), notNullValue());

        assertThat(factory.getRegistrationContext(registrationId), notNullValue());
        assertThat(factory.getRegistrationIDs(registeredProvider), arrayContaining(registrationId));
    }

    @Test
    public void testRemoveRegistration() throws Exception
    {
        // Arrange
        AuthConfigProvider provider = new JaspiAuthConfigProvider(
                serverAuthModuleClassName,
                serverAuthModuleProperties);

        AuthConfigFactory factory = new DefaultAuthConfigFactory();
        String registrationId = factory.registerConfigProvider(provider, MESSAGE_LAYER, appContext, "a test provider");
        
        DummyRegistrationListener dummyListener = new DummyRegistrationListener();
        assertThat(factory.getConfigProvider(MESSAGE_LAYER, appContext, dummyListener), notNullValue());
        
        // Act
        factory.removeRegistration(registrationId);
        
        // Assert config provider removed
        assertThat(factory.getConfigProvider(MESSAGE_LAYER, appContext, null), nullValue());
        
        // Assert listeners invoked
        assertThat(dummyListener.appContext, equalTo(appContext));
        assertThat(dummyListener.layer, equalTo(MESSAGE_LAYER));

    }
    
    @Test
    public void testGetRegistrationIDsWithNullProvider() throws Exception
    {
        AuthConfigFactory factory = new DefaultAuthConfigFactory();
        String byClassName = factory.registerConfigProvider(jettyAuthConfigProvider,
                serverAuthModuleProperties, MESSAGE_LAYER, appContext, "a test provider");
        String byInstance = factory.registerConfigProvider(new JaspiAuthConfigProvider(
                serverAuthModuleClassName, serverAuthModuleProperties), MESSAGE_LAYER, "server /other", "a test provider");

        // A null provider asks for every active registration.
        assertThat(factory.getRegistrationIDs(null), arrayContainingInAnyOrder(byClassName, byInstance));

        // An unregistered provider has no registrations.
        assertThat(factory.getRegistrationIDs(new JaspiAuthConfigProvider(
                serverAuthModuleClassName, serverAuthModuleProperties)), emptyArray());
    }

    @Test
    public void testRegistrationIsPersistentOnlyWhenRegisteredByClassName() throws Exception
    {
        AuthConfigFactory factory = new DefaultAuthConfigFactory();

        String byClassName = factory.registerConfigProvider(jettyAuthConfigProvider,
                serverAuthModuleProperties, MESSAGE_LAYER, appContext, "a test provider");
        assertThat(factory.getRegistrationContext(byClassName).isPersistent(), is(true));

        String byInstance = factory.registerConfigProvider(new JaspiAuthConfigProvider(
                serverAuthModuleClassName, serverAuthModuleProperties), MESSAGE_LAYER, "server /other", "a test provider");
        assertThat(factory.getRegistrationContext(byInstance).isPersistent(), is(false));
    }

    @Test
    public void testReRegistrationReplacesTheRegistrationAndNotifiesItsListener() throws Exception
    {
        AuthConfigFactory factory = new DefaultAuthConfigFactory();
        String registrationId = factory.registerConfigProvider(jettyAuthConfigProvider,
                serverAuthModuleProperties, MESSAGE_LAYER, appContext, "a test provider");

        DummyRegistrationListener dummyListener = new DummyRegistrationListener();
        assertThat(factory.getConfigProvider(MESSAGE_LAYER, appContext, dummyListener), notNullValue());

        String replacementId = factory.registerConfigProvider(jettyAuthConfigProvider,
                serverAuthModuleProperties, MESSAGE_LAYER, appContext, "a replacement provider");

        // At most one registration exists for a layer and appContext, and its identifier is unchanged.
        assertThat(replacementId, equalTo(registrationId));
        assertThat(factory.getRegistrationIDs(null), arrayContaining(registrationId));
        assertThat(factory.getRegistrationContext(registrationId).getDescription(), equalTo("a replacement provider"));

        // The listener of the replaced registration is notified.
        assertThat(dummyListener.layer, equalTo(MESSAGE_LAYER));
        assertThat(dummyListener.appContext, equalTo(appContext));
    }

    @Test
    public void testListenerIsAttachedWhenNoProviderIsSelected() throws Exception
    {
        AuthConfigFactory factory = new DefaultAuthConfigFactory();

        DummyRegistrationListener dummyListener = new DummyRegistrationListener();
        assertThat(factory.getConfigProvider(MESSAGE_LAYER, appContext, dummyListener), nullValue());

        factory.registerConfigProvider(jettyAuthConfigProvider,
                serverAuthModuleProperties, MESSAGE_LAYER, appContext, "a test provider");

        assertThat(dummyListener.layer, equalTo(MESSAGE_LAYER));
        assertThat(dummyListener.appContext, equalTo(appContext));
    }

    @Test
    public void testListenerAttachedWhenNoProviderIsSelectedCanBeDetached() throws Exception
    {
        AuthConfigFactory factory = new DefaultAuthConfigFactory();

        DummyRegistrationListener dummyListener = new DummyRegistrationListener();
        assertThat(factory.getConfigProvider(MESSAGE_LAYER, appContext, dummyListener), nullValue());
        assertThat(factory.detachListener(dummyListener, MESSAGE_LAYER, appContext), arrayContaining(MESSAGE_LAYER + "/" + appContext));

        factory.registerConfigProvider(jettyAuthConfigProvider,
                serverAuthModuleProperties, MESSAGE_LAYER, appContext, "a test provider");

        assertThat(dummyListener.layer, nullValue());
        assertThat(dummyListener.appContext, nullValue());
    }

    @Test
    public void testListenerIsNotifiedOfARegistrationForAllLayersAndContexts() throws Exception
    {
        AuthConfigFactory factory = new DefaultAuthConfigFactory();

        DummyRegistrationListener dummyListener = new DummyRegistrationListener();
        assertThat(factory.getConfigProvider(MESSAGE_LAYER, appContext, dummyListener), nullValue());

        factory.registerConfigProvider(new JaspiAuthConfigProvider(
                serverAuthModuleClassName, serverAuthModuleProperties), null, null, "a test provider");

        // The listener is notified for the layer and appContext it asked about.
        assertThat(dummyListener.layer, equalTo(MESSAGE_LAYER));
        assertThat(dummyListener.appContext, equalTo(appContext));
    }

    @Test
    public void testAListenerAttachedWhileARegistrationChangesIsNotified() throws Exception
    {
        AuthConfigFactory factory = new DefaultAuthConfigFactory();

        // Attaching a listener to a layer and appContext that already has one compares the two.
        DummyRegistrationListener attached = new DummyRegistrationListener();
        factory.getConfigProvider(MESSAGE_LAYER, appContext, attached);

        PausingRegistrationListener attaching = new PausingRegistrationListener();
        Thread attachingThread = new Thread(() -> factory.getConfigProvider(MESSAGE_LAYER, appContext, attaching));
        attachingThread.start();
        assertTrue(attaching.comparing.await(10, TimeUnit.SECONDS), "the listener was not being attached");

        // The registration changes while that listener is being attached.
        Thread registeringThread = new Thread(() -> factory.registerConfigProvider(jettyAuthConfigProvider,
                serverAuthModuleProperties, MESSAGE_LAYER, appContext, "a test provider"));
        registeringThread.start();

        // The change cannot notify the listeners until the attachment has finished, so this waits for as long as the two take to overlap.
        attached.notified.await(1, TimeUnit.SECONDS);
        attaching.release.countDown();
        attachingThread.join();
        registeringThread.join();

        assertTrue(attaching.notified.await(10, TimeUnit.SECONDS), "the attached listener was not notified");
    }

    @Test
    public void testRefreshDoesNotReplaceRegistrations() throws Exception
    {
        AuthConfigFactory factory = new DefaultAuthConfigFactory();
        factory.registerConfigProvider(jettyAuthConfigProvider,
                serverAuthModuleProperties, MESSAGE_LAYER, appContext, "a test provider");

        DummyRegistrationListener dummyListener = new DummyRegistrationListener();
        AuthConfigProvider provider = factory.getConfigProvider(MESSAGE_LAYER, appContext, dummyListener);
        assertThat(provider, notNullValue());

        factory.refresh();

        assertThat(factory.getConfigProvider(MESSAGE_LAYER, appContext, null), sameInstance(provider));
        assertThat(dummyListener.layer, nullValue());
        assertThat(dummyListener.appContext, nullValue());
    }

    @Test
    public void testRemoveServerAuthModule() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler("/test");
        ServletContext servletContext = contextHandler.getServletContext();

        AuthConfigFactory factory = new DefaultAuthConfigFactory();
        String registrationId = factory.registerServerAuthModule(new HttpHeaderAuthModule(), servletContext);
        assertThat(factory.getRegistrationIDs(null), arrayContaining(registrationId));

        factory.removeServerAuthModule(servletContext);
        assertThat(factory.getRegistrationIDs(null), emptyArray());
    }

    @Test
    public void testRemoveServerAuthModuleDoesNotRemoveTheRegistrationOfAReplacement() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler("/test");
        AuthConfigFactory factory = new DefaultAuthConfigFactory();
        factory.registerServerAuthModule(new HttpHeaderAuthModule(), contextHandler.getServletContext());

        // A context deployed at the same path has the same application context identifier, so its registration replaces the one above.
        ServletContextHandler redeployedContextHandler = new ServletContextHandler("/test");
        String registrationId = factory.registerServerAuthModule(new HttpHeaderAuthModule(), redeployedContextHandler.getServletContext());

        factory.removeServerAuthModule(contextHandler.getServletContext());
        assertThat(factory.getRegistrationIDs(null), arrayContaining(registrationId));

        factory.removeServerAuthModule(redeployedContextHandler.getServletContext());
        assertThat(factory.getRegistrationIDs(null), emptyArray());
    }

    @Test
    public void testServerAuthModuleRegistrationIsRemovedWhenTheContextStops() throws Exception
    {
        Server server = new Server();
        ServletContextHandler contextHandler = new ServletContextHandler("/test");
        server.setHandler(contextHandler);
        server.start();

        AuthConfigFactory factory = new DefaultAuthConfigFactory();
        String registrationId = factory.registerServerAuthModule(new HttpHeaderAuthModule(), contextHandler.getServletContext());
        assertThat(factory.getRegistrationIDs(null), arrayContaining(registrationId));

        server.stop();
        assertThat(factory.getRegistrationIDs(null), emptyArray());
    }

    @Test
    public void testAStoppingContextDoesNotRemoveTheRegistrationOfItsReplacement() throws Exception
    {
        Server server = new Server();
        ServletContextHandler contextHandler = new ServletContextHandler("/test");
        server.setHandler(contextHandler);
        server.start();

        AuthConfigFactory factory = new DefaultAuthConfigFactory();
        factory.registerServerAuthModule(new HttpHeaderAuthModule(), contextHandler.getServletContext());

        // A context deployed at the same path has the same application context identifier, so its registration replaces the one above.
        Server redeployedServer = new Server();
        ServletContextHandler redeployedContextHandler = new ServletContextHandler("/test");
        redeployedServer.setHandler(redeployedContextHandler);
        redeployedServer.start();
        String registrationId = factory.registerServerAuthModule(new HttpHeaderAuthModule(), redeployedContextHandler.getServletContext());

        server.stop();
        assertThat(factory.getRegistrationIDs(null), arrayContaining(registrationId));

        redeployedServer.stop();
        assertThat(factory.getRegistrationIDs(null), emptyArray());
    }

    static class DummyRegistrationListener implements RegistrationListener
    {
        final CountDownLatch notified = new CountDownLatch(1);
        String layer;
        String appContext;
        
        @Override
        public void notify(String layer, String appContext)
        {
            this.layer = layer;
            this.appContext = appContext;
            notified.countDown();
        }
    }

    /**
     * A listener that pauses while it is compared with the listeners already attached, which is
     * after the list it is being attached to has been looked up.
     */
    static class PausingRegistrationListener extends DummyRegistrationListener
    {
        final CountDownLatch comparing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean equals(Object other)
        {
            comparing.countDown();
            try
            {
                release.await(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
            return this == other;
        }

        @Override
        public int hashCode()
        {
            return System.identityHashCode(this);
        }
    }
}
