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

package org.eclipse.jetty.ee10.security.jaspi.provider;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import javax.security.auth.callback.CallbackHandler;

import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.ClientAuthConfig;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.module.ServerAuthModule;
import org.eclipse.jetty.ee10.security.jaspi.JaspiAuthenticatorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 
 * <p>A Jetty implementation of the {@link AuthConfigProvider} to allow registration of a {@link ServerAuthModule}
 * directly without having to write a custom {@link AuthConfigProvider}.</p>
 * <p>If this is being constructed by an {@link AuthConfigFactory} after being passed in as a className, then
 * you will need to provide the property {@code ServerAuthModule} containing the fully qualified name of
 * the {@link ServerAuthModule} class you wish to use.</p>
 */
@SuppressWarnings("rawtypes")
public class JaspiAuthConfigProvider implements AuthConfigProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(JaspiAuthConfigProvider.class);
    private final Map providerProperties;
    private final ServerAuthModule serverAuthModule;

    /**
     * <p>Constructor with signature and implementation that's required by API.</p>
     * <p>The property map must contain the {@code ServerAuthModule} property containing the fully qualified name of
     * the {@link ServerAuthModule} class you wish to use. If this constructor is being used for self-registration an
     * optional property of {@code appContext} can be used specify the appContext value to register the provider.</p>
     *
     * @param properties A Map of initialization properties.
     * @param factory The {@link AuthConfigFactory} to register on.
     */
    public JaspiAuthConfigProvider(Map properties, AuthConfigFactory factory)
    {
        if (properties == null || !properties.containsKey("ServerAuthModule"))
            throw new IllegalArgumentException("Missing property 'ServerAuthModule', cannot create JaspiAuthConfigProvider");

        this.providerProperties = Map.copyOf(properties);
        this.serverAuthModule = createServerAuthModule((String)properties.get("ServerAuthModule"));

        // API requires self registration if factory is provided.
        if (factory != null)
            factory.registerConfigProvider(this, JaspiAuthenticatorFactory.MESSAGE_LAYER, (String)properties.get("appContext"), "Self Registration");
    }

    /**
     * @param className The fully qualified name of a {@link ServerAuthModule} class.
     */
    public JaspiAuthConfigProvider(String className)
    {
        this(className, null);
    }

    /**
     * @param className The fully qualified name of a {@link ServerAuthModule} class.
     * @param properties A Map of initialization properties.
     */
    public JaspiAuthConfigProvider(String className, Map properties)
    {
        this(createServerAuthModule(className), properties);
    }

    /**
     * @param serverAuthModule The instance of {@link ServerAuthModule} to use.
     */
    public JaspiAuthConfigProvider(ServerAuthModule serverAuthModule)
    {
        this.serverAuthModule = Objects.requireNonNull(serverAuthModule);
        this.providerProperties = Collections.emptyMap();
    }

    /**
     * @param serverAuthModule The instance of {@link ServerAuthModule} to use.
     * @param properties A Map of initialization properties.
     */
    public JaspiAuthConfigProvider(ServerAuthModule serverAuthModule, Map properties)
    {
        this.serverAuthModule = Objects.requireNonNull(serverAuthModule);
        this.providerProperties = properties == null ? Collections.emptyMap() : Map.copyOf(properties);
    }

    @Override
    public ClientAuthConfig getClientAuthConfig(String layer, String appContext, CallbackHandler handler)
    {
        return null;
    }

    @Override
    public ServerAuthConfig getServerAuthConfig(String layer, String appContext, CallbackHandler handler)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("getServerAuthConfig");
        return new SimpleAuthConfig(layer, appContext, handler, providerProperties, serverAuthModule);
    }

    @Override
    public void refresh()
    {
    }

    private static ServerAuthModule createServerAuthModule(String serverAuthModuleClassName)
    {
        try
        {
            return (ServerAuthModule)Class.forName(serverAuthModuleClassName).getDeclaredConstructor().newInstance();
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }
}