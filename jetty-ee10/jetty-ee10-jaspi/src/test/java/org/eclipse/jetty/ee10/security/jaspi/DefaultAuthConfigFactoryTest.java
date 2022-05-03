//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.security.jaspi;

import java.util.Map;

import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.RegistrationListener;
import org.eclipse.jetty.ee10.security.jaspi.provider.JaspiAuthConfigProvider;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class DefaultAuthConfigFactoryTest
{

    private static final String MESSAGE_LAYER = "HttpServlet";

    private final String jettyAuthConfigProvider = "org.eclipse.jetty.ee10.security.jaspi.provider.JaspiAuthConfigProvider";
    private final String appContext = "server /test";

    private final Map<String, String> serverAuthModuleProperties = Map.of("ServerAuthModule",
            "org.eclipse.jetty.ee10.security.jaspi.modules.BasicAuthenticationAuthModule", "AppContextID", appContext,
            "org.eclipse.jetty.ee10.security.jaspi.modules.RealmName", "TestRealm");

    private final String serverAuthModuleClassName = "org.eclipse.jetty.ee10.security.jaspi.modules.BasicAuthenticationAuthModule";
    
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
    
    static class DummyRegistrationListener implements RegistrationListener 
    {
        String layer;
        String appContext;
        
        @Override
        public void notify(String layer, String appContext)
        {
            this.layer = layer;
            this.appContext = appContext;
        }
    }
}
