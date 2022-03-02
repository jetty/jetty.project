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

package org.eclipse.jetty.ee9.security.jaspi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.RegistrationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 
 * A very basic {@link AuthConfigFactory} that allows for registering providers programmatically.
 */
public class DefaultAuthConfigFactory extends AuthConfigFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultAuthConfigFactory.class);
    private final Map<String, DefaultRegistrationContext> _registrations = new ConcurrentHashMap<>();

    public DefaultAuthConfigFactory()
    {
    }

    @Override
    public AuthConfigProvider getConfigProvider(String layer, String appContext, RegistrationListener listener)
    {
        DefaultRegistrationContext registrationContext = _registrations.get(getKey(layer, appContext));
        if (registrationContext == null)
            registrationContext = _registrations.get(getKey(null, appContext));
        if (registrationContext == null)
            registrationContext = _registrations.get(getKey(layer, null));
        if (registrationContext == null)
            registrationContext = _registrations.get(getKey(null, null));
        if (registrationContext == null)
            return null;

        // TODO: according to the javadoc you're supposed to register listener even if there is no context available.
        if (listener != null)
            registrationContext.addListener(listener);
        return registrationContext.getProvider();
    }

    @Override
    public String registerConfigProvider(String className, Map properties, String layer, String appContext, String description)
    {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null)
            sm.checkPermission(AuthConfigFactory.providerRegistrationSecurityPermission);

        String key = getKey(layer, appContext);
        AuthConfigProvider configProvider = createConfigProvider(className, properties);
        DefaultRegistrationContext context = new DefaultRegistrationContext(configProvider, layer, appContext, description, true);
        DefaultRegistrationContext oldContext = _registrations.put(key, context);
        if (oldContext != null)
            oldContext.notifyListeners();
        return key;
    }

    @Override
    public String registerConfigProvider(AuthConfigProvider provider, String layer, String appContext, String description)
    {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) 
            sm.checkPermission(AuthConfigFactory.providerRegistrationSecurityPermission);

        String key = getKey(layer, appContext);
        DefaultRegistrationContext context = new DefaultRegistrationContext(provider, layer, appContext, description, false);
        DefaultRegistrationContext oldContext = _registrations.put(key, context);
        if (oldContext != null)
            oldContext.notifyListeners();
        return key;
    }

    @Override
    public boolean removeRegistration(String registrationID)
    {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) 
            sm.checkPermission(AuthConfigFactory.providerRegistrationSecurityPermission);

        DefaultRegistrationContext registrationContext = _registrations.remove(registrationID);
        if (registrationContext == null)
            return false;

        registrationContext.notifyListeners();
        return true;
    }

    @Override
    public String[] detachListener(RegistrationListener listener, String layer, String appContext)
    {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null)
            sm.checkPermission(AuthConfigFactory.providerRegistrationSecurityPermission);

        List<String> registrationIds = new ArrayList<>();
        for (DefaultRegistrationContext registration : _registrations.values())
        {
            if ((layer == null || layer.equals(registration.getMessageLayer())) && (appContext == null || appContext.equals(registration.getAppContext())))
            {
                if (registration.removeListener(listener))
                    registrationIds.add(getKey(registration.getMessageLayer(), registration.getAppContext()));
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
            if (provider == registration.getProvider())
                registrationIds.add(getKey(registration.getMessageLayer(), registration.getAppContext()));
        }

        return registrationIds.toArray(new String[0]);
    }

    @Override
    public RegistrationContext getRegistrationContext(String registrationID)
    {
        return _registrations.get(registrationID);
    }

    @Override
    public void refresh()
    {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null)
            sm.checkPermission(AuthConfigFactory.providerRegistrationSecurityPermission);

        // TODO: maybe we should re-construct providers created from classname.
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
            return (AuthConfigProvider)Class.forName(className)
                .getConstructor(Map.class, AuthConfigFactory.class)
                .newInstance(properties, null);
        }
        catch (ReflectiveOperationException e)
        {
            throw new SecurityException(e);
        }
    }

    private static class DefaultRegistrationContext implements RegistrationContext
    {
        private final String _layer;
        private final String _appContext;
        private final boolean _persistent;
        private final AuthConfigProvider _provider;
        private final String _description;
        private final List<RegistrationListener> _listeners = new CopyOnWriteArrayList<>();

        public DefaultRegistrationContext(AuthConfigProvider provider, String layer, String appContext, String description, boolean persistent)
        {
            _provider = provider;
            _layer = layer;
            _appContext = appContext;
            _description = description;
            _persistent = persistent;
        }

        public AuthConfigProvider getProvider()
        {
            return _provider;
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
        public String getDescription()
        {
            return _description;
        }

        @Override
        public boolean isPersistent()
        {
            return false;
        }

        public void addListener(RegistrationListener listener)
        {
            _listeners.add(listener);
        }

        public void notifyListeners()
        {
            for (RegistrationListener listener : _listeners)
            {
                try
                {
                    listener.notify(_layer, _appContext);
                }
                catch (Throwable t)
                {
                    LOG.warn("Error from RegistrationListener", t);
                }
            }
        }

        public boolean removeListener(RegistrationListener listener)
        {
            return _listeners.remove(listener);
        }
    }
}
