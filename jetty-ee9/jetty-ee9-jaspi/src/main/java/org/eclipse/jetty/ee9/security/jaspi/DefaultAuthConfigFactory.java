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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.RegistrationListener;
import org.eclipse.jetty.util.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 
 * A very basic {@link AuthConfigFactory} that allows for registering providers programmatically.
 * <p>Registrations are held in memory only. The persistent declarative representation of
 * registrations described by {@link AuthConfigFactory} is not implemented, so registrations do not
 * survive a restart.</p>
 */
public class DefaultAuthConfigFactory extends AuthConfigFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultAuthConfigFactory.class);
    private final Map<String, DefaultRegistrationContext> _registrations = new ConcurrentHashMap<>();
    private final Map<ListenerKey, CopyOnWriteArrayList<RegistrationListener>> _listeners = new ConcurrentHashMap<>();

    public DefaultAuthConfigFactory()
    {
    }

    @Override
    public AuthConfigProvider getConfigProvider(String layer, String appContext, RegistrationListener listener)
    {
        // Attached to the layer and appContext asked about, not to the registration selected, as a listener is attached even when no provider is.
        if (listener != null)
        {
            // Attached while holding the key's mapping, so a listener cannot be added to a list notifyListeners has already removed.
            _listeners.compute(new ListenerKey(layer, appContext), (key, listeners) ->
            {
                CopyOnWriteArrayList<RegistrationListener> attached = listeners == null ? new CopyOnWriteArrayList<>() : listeners;
                attached.addIfAbsent(listener);
                return attached;
            });
        }

        DefaultRegistrationContext registrationContext = _registrations.get(getKey(layer, appContext));
        if (registrationContext == null)
            registrationContext = _registrations.get(getKey(null, appContext));
        if (registrationContext == null)
            registrationContext = _registrations.get(getKey(layer, null));
        if (registrationContext == null)
            registrationContext = _registrations.get(getKey(null, null));
        if (registrationContext == null)
            return null;

        return registrationContext.provider();
    }

    @Override
    public String registerConfigProvider(String className, Map properties, String layer, String appContext, String description)
    {
        checkPermission();

        String key = getKey(layer, appContext);
        AuthConfigProvider configProvider = createConfigProvider(className, properties);
        _registrations.put(key, new DefaultRegistrationContext(configProvider, layer, appContext, description, true));
        notifyListeners(layer, appContext);
        return key;
    }

    @Override
    public String registerConfigProvider(AuthConfigProvider provider, String layer, String appContext, String description)
    {
        checkPermission();

        String key = getKey(layer, appContext);
        _registrations.put(key, new DefaultRegistrationContext(provider, layer, appContext, description, false));
        notifyListeners(layer, appContext);
        return key;
    }

    @Override
    public boolean removeRegistration(String registrationID)
    {
        checkPermission();

        DefaultRegistrationContext registrationContext = _registrations.remove(registrationID);
        if (registrationContext == null)
            return false;

        notifyListeners(registrationContext.layer(), registrationContext.appContext());
        return true;
    }

    /**
     * Notify the listeners attached for a layer and appContext matched by a registration that has
     * been made, replaced or removed, detaching each as it is notified.
     *
     * @param layer the message layer of the registration, or null for all layers.
     * @param appContext the application context of the registration, or null for all contexts.
     */
    private void notifyListeners(String layer, String appContext)
    {
        for (ListenerKey key : _listeners.keySet())
        {
            if ((layer != null && !layer.equals(key.layer())) || (appContext != null && !appContext.equals(key.appContext())))
                continue;

            List<RegistrationListener> listeners = _listeners.remove(key);
            if (listeners == null)
                continue;

            for (RegistrationListener listener : listeners)
            {
                try
                {
                    listener.notify(key.layer(), key.appContext());
                }
                catch (Throwable t)
                {
                    LOG.warn("Error from RegistrationListener", t);
                }
            }
        }
    }

    @Override
    public String[] detachListener(RegistrationListener listener, String layer, String appContext)
    {
        checkPermission();

        List<String> registrationIds = new ArrayList<>();
        for (Map.Entry<ListenerKey, CopyOnWriteArrayList<RegistrationListener>> entry : _listeners.entrySet())
        {
            ListenerKey key = entry.getKey();
            if ((layer == null || layer.equals(key.layer())) && (appContext == null || appContext.equals(key.appContext())))
            {
                if (entry.getValue().remove(listener))
                    registrationIds.add(getKey(key.layer(), key.appContext()));
            }
        }

        return registrationIds.toArray(new String[0]);
    }

    @Override
    public String[] getRegistrationIDs(AuthConfigProvider provider)
    {
        List<String> registrationIds = new ArrayList<>();
        for (DefaultRegistrationContext registration : _registrations.values())
        {
            // A null provider asks for every active registration, not only those registered with a null provider.
            if (provider == null || provider == registration.provider())
                registrationIds.add(getKey(registration.layer(), registration.appContext()));
        }

        return registrationIds.toArray(new String[0]);
    }

    @Override
    public RegistrationContext getRegistrationContext(String registrationID)
    {
        return _registrations.get(registrationID);
    }

    /**
     * There is no persistent declarative representation of registrations to reprocess, and nothing
     * but a registration call can change the provider class or properties of a registration, so
     * this does nothing.
     */
    @Override
    public void refresh()
    {
        checkPermission();
    }

    private record ListenerKey(String layer, String appContext)
    {
    }

    private static void checkPermission()
    {
        SecurityUtils.checkPermission(providerRegistrationSecurityPermission);
    }

    private static String getKey(String layer, String appContext)
    {
        return layer + "/" + appContext;
    }

    @SuppressWarnings("rawtypes")
    private AuthConfigProvider createConfigProvider(String className, Map properties)
    {
        try
        {
            // Javadoc specifies all AuthConfigProvider implementations must have this constructor, and that
            // to construct this we must pass a null value for the factory argument of the constructor.
            return (AuthConfigProvider)loadClass(className)
                .getConstructor(Map.class, AuthConfigFactory.class)
                .newInstance(properties, null);
        }
        catch (ReflectiveOperationException e)
        {
            throw new SecurityException(e);
        }
    }

    /**
     * Prefer the thread context classloader, so a web application can register a provider of its
     * own, falling back to the classloader of this class for one deployed with the server.
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

    private record DefaultRegistrationContext(AuthConfigProvider provider, String layer, String appContext, String description, boolean persistent)
        implements RegistrationContext
    {
        @Override
        public String getMessageLayer()
        {
            return layer;
        }

        @Override
        public String getAppContext()
        {
            return appContext;
        }

        @Override
        public String getDescription()
        {
            return description;
        }

        @Override
        public boolean isPersistent()
        {
            return persistent;
        }
    }
}
