//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common;

import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.websocket.core.ExtensionConfig;

/**
 * Represents an Extension Configuration, as seen during the connection Handshake process.
 */
public class JettyExtensionConfig implements org.eclipse.jetty.websocket.api.extensions.ExtensionConfig
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
