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

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.security.auth.callback.CallbackHandler;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.ClientAuthConfig;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.module.ServerAuthModule;
import org.eclipse.jetty.ee.security.jaspi.JaspiAuthenticatorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 
 * <p>A Jetty implementation of the {@link AuthConfigProvider} to allow registration of a {@link ServerAuthModule}
 * directly without having to write a custom {@link AuthConfigProvider}.</p>
 * <p>If this is being constructed by an {@link AuthConfigFactory} after being passed in as a className, then
 * you will need to provide the property {@code ServerAuthModule} containing the fully qualified name of
 * the {@link ServerAuthModule} class you wish to use.</p>
 * <p>A module given as an instance holds the one {@link CallbackHandler} it was initialized with, so a
 * provider registered for more than one application context leaves them all with the handler of the
 * last context to resolve it.</p>
 */
public class JaspiAuthConfigProvider implements AuthConfigProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(JaspiAuthConfigProvider.class);
    private final Map<String, Object> providerProperties;
    private final Constructor<? extends ServerAuthModule> serverAuthModuleConstructor;
    private final ServerAuthModule serverAuthModule;
    private final Map<ConfigKey, CachedConfig> authConfigs = new HashMap<>();

    /**
     * <p>Constructor with signature and implementation that's required by API.</p>
     * <p>The property map must contain the {@code ServerAuthModule} property containing the fully qualified name of
     * the {@link ServerAuthModule} class you wish to use. If this constructor is being used for self-registration an
     * optional property of {@code appContext} can be used specify the appContext value to register the provider.</p>
     *
     * @param properties A Map of initialization properties.
     * @param factory The {@link AuthConfigFactory} to register on.
     */
    public JaspiAuthConfigProvider(Map<String, String> properties, AuthConfigFactory factory)
    {
        if (properties == null || !properties.containsKey("ServerAuthModule"))
            throw new IllegalArgumentException("Missing property 'ServerAuthModule', cannot create JaspiAuthConfigProvider");

        this.providerProperties = Map.copyOf(properties);
        this.serverAuthModuleConstructor = findServerAuthModuleConstructor(properties.get("ServerAuthModule"));
        this.serverAuthModule = null;

        // API requires self registration if factory is provided.
        if (factory != null)
            factory.registerConfigProvider(this, JaspiAuthenticatorFactory.MESSAGE_LAYER, properties.get("appContext"), "Self Registration");
    }

    /**
     * @param className The fully qualified name of a {@link ServerAuthModule} class.
     * @deprecated use {@link #JaspiAuthConfigProvider(String, Map)}.
     */
    @Deprecated(since = "13.0.0", forRemoval = true)
    public JaspiAuthConfigProvider(String className)
    {
        this(className, null);
    }

    /**
     * @param className The fully qualified name of a {@link ServerAuthModule} class.
     * @param properties A Map of initialization properties.
     */
    public JaspiAuthConfigProvider(String className, Map<String, ?> properties)
    {
        this.serverAuthModuleConstructor = findServerAuthModuleConstructor(className);
        this.serverAuthModule = null;
        this.providerProperties = properties == null ? Collections.emptyMap() : Map.copyOf(properties);
    }

    /**
     * @param serverAuthModule The instance of {@link ServerAuthModule} to use, which is shared by
     * every authentication context this provider configures.
     */
    public JaspiAuthConfigProvider(ServerAuthModule serverAuthModule)
    {
        this(serverAuthModule, null);
    }

    /**
     * @param serverAuthModule The instance of {@link ServerAuthModule} to use, which is shared by
     * every authentication context this provider configures and is initialized with {@code properties}.
     * @param properties A Map of initialization properties.
     */
    public JaspiAuthConfigProvider(ServerAuthModule serverAuthModule, Map<String, ?> properties)
    {
        this.serverAuthModuleConstructor = null;
        this.serverAuthModule = Objects.requireNonNull(serverAuthModule);
        this.providerProperties = properties == null ? Collections.emptyMap() : Map.copyOf(properties);
    }

    @Override
    public ClientAuthConfig getClientAuthConfig(String layer, String appContext, CallbackHandler handler)
    {
        return null;
    }

    @Override
    public synchronized ServerAuthConfig getServerAuthConfig(String layer, String appContext, CallbackHandler handler) throws AuthException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("getServerAuthConfig");

        // Kept against the application context, rebuilt only for a new callback handler, so resolving again does not initialize the module under a message being processed.
        ConfigKey key = new ConfigKey(layer, appContext);
        CachedConfig cached = authConfigs.get(key);
        if (cached != null && cached.handler() == handler)
            return cached.authConfig();

        // A module named by class gets an instance per context, so each context's request policy holds; a module given as an instance must be the one used, so it is shared.
        ServerAuthConfig authConfig = serverAuthModule != null
            ? new SimpleAuthConfig(layer, appContext, handler, providerProperties, serverAuthModule, serverAuthModule)
            : new SimpleAuthConfig(layer, appContext, handler, providerProperties,
                createServerAuthModule(serverAuthModuleConstructor), createServerAuthModule(serverAuthModuleConstructor));

        authConfigs.put(key, new CachedConfig(handler, authConfig));
        return authConfig;
    }

    @Override
    public void refresh()
    {
    }

    private static Constructor<? extends ServerAuthModule> findServerAuthModuleConstructor(String serverAuthModuleClassName)
    {
        try
        {
            return loadClass(serverAuthModuleClassName).asSubclass(ServerAuthModule.class).getDeclaredConstructor();
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Prefer the thread context classloader, so a web application can name a module of its own,
     * falling back to the classloader of this class for one deployed with the server.
     */
    private static Class<?> loadClass(String className) throws ClassNotFoundException
    {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null)
        {
            try
            {
                return contextClassLoader.loadClass(className);
            }
            catch (ClassNotFoundException ignored)
            {
            }
        }
        return Class.forName(className);
    }

    private static ServerAuthModule createServerAuthModule(Constructor<? extends ServerAuthModule> serverAuthModuleConstructor)
    {
        try
        {
            return serverAuthModuleConstructor.newInstance();
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private record ConfigKey(String layer, String appContext)
    {
    }

    private record CachedConfig(CallbackHandler handler, ServerAuthConfig authConfig)
    {
    }
}