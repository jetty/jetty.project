//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.security.jaspi;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.security.auth.AuthPermission;

import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.RegistrationListener;

/** 
 * A very basic {@link AuthConfigFactory} that allows for registering providers programmatically.
 */
public class DefaultAuthConfigFactory extends AuthConfigFactory
{
    private final Map<String, AuthConfigProvider> providers = new LinkedHashMap<>();
    
    public DefaultAuthConfigFactory()
    {
    }
    
    @Override
    public AuthConfigProvider getConfigProvider(String layer, String appContext, RegistrationListener listener)
    {
        String key = getKey(layer, appContext);
        return providers.get(key);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public String registerConfigProvider(String className, Map properties, String layer, String appContext,
            String description)
    {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) 
        {
            sm.checkPermission(new AuthPermission("registerAuthConfigProvider"));
        }
        String key = getKey(layer, appContext);
        providers.put(key, new JaspiAuthConfigProvider(properties, className));
        return key;
    }

    @Override
    public String registerConfigProvider(AuthConfigProvider provider, String layer, String appContext,
            String description)
    {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) 
        {
            sm.checkPermission(new AuthPermission("registerAuthConfigProvider"));
        }
        String key = getKey(layer, appContext);
        providers.put(key, provider);
        return key;
    }

    @Override
    public boolean removeRegistration(String registrationID)
    {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) 
        {
            sm.checkPermission(new AuthPermission("removeAuthRegistration"));
        }
        return providers.remove(registrationID) != null;
    }

    @Override
    public String[] detachListener(RegistrationListener listener, String layer, String appContext)
    {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String[] getRegistrationIDs(AuthConfigProvider provider)
    {
        return providers.keySet().toArray(new String[]{});
    }

    @Override
    public RegistrationContext getRegistrationContext(String registrationID)
    {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void refresh()
    {
        throw new UnsupportedOperationException("Not implemented");
    }

    private String getKey(String layer, String appContext)
    {
        return layer + "/" + appContext;
    }

}
