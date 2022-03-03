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

package org.eclipse.jetty.ee9.websocket.common;

import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.websocket.core.ExtensionConfig;

/**
 * Represents an Extension Configuration, as seen during the connection Handshake process.
 */
public class JettyExtensionConfig implements org.eclipse.jetty.ee9.websocket.api.ExtensionConfig
{

    private final ExtensionConfig config;

    /**
     * Copy constructor
     *
     * @param copy the extension config to copy
     */
    public JettyExtensionConfig(JettyExtensionConfig copy)
    {
        this(copy.config);
    }

    public JettyExtensionConfig(ExtensionConfig config)
    {
        this.config = config;
    }

    public JettyExtensionConfig(String parameterizedName)
    {
        this.config = new ExtensionConfig(parameterizedName);
    }

    public JettyExtensionConfig(String name, Map<String, String> parameters)
    {
        this.config = new ExtensionConfig(name, parameters);
    }

    public ExtensionConfig getCoreConfig()
    {
        return config;
    }

    @Override
    public String getName()
    {
        return config.getName();
    }

    @Override
    public final int getParameter(String key, int defValue)
    {
        return config.getParameter(key, defValue);
    }

    @Override
    public final String getParameter(String key, String defValue)
    {
        return config.getParameter(key, defValue);
    }

    @Override
    public final String getParameterizedName()
    {
        return config.getParameterizedName();
    }

    @Override
    public final Set<String> getParameterKeys()
    {
        return config.getParameterKeys();
    }

    /**
     * Return parameters found in request URI.
     *
     * @return the parameter map
     */
    @Override
    public final Map<String, String> getParameters()
    {
        return config.getParameters();
    }

    @Override
    public final void setParameter(String key)
    {
        config.setParameter(key);
    }

    @Override
    public final void setParameter(String key, int value)
    {
        config.setParameter(key, value);
    }

    @Override
    public final void setParameter(String key, String value)
    {
        config.setParameter(key, value);
    }

    @Override
    public String toString()
    {
        return config.toString();
    }
}
