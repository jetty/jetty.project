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

package org.eclipse.jetty.websocket.javax.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.websocket.Extension;

import org.eclipse.jetty.websocket.core.ExtensionConfig;

public class JavaxWebSocketExtension implements Extension
{
    private static class JsrParameter implements Extension.Parameter
    {
        private String name;
        private String value;

        private JsrParameter(String key, String value)
        {
            this.name = key;
            this.value = value;
        }

        @Override
        public String getName()
        {
            return this.name;
        }

        @Override
        public String getValue()
        {
            return this.value;
        }
    }

    private final String name;
    private List<Parameter> parameters = new ArrayList<>();

    /**
     * A configured extension
     *
     * @param cfg the configuration for the extension
     */
    public JavaxWebSocketExtension(ExtensionConfig cfg)
    {
        this.name = cfg.getName();
        if (cfg.getParameters() != null)
        {
            for (Map.Entry<String, String> entry : cfg.getParameters().entrySet())
            {
                parameters.add(new JsrParameter(entry.getKey(), entry.getValue()));
            }
        }
    }

    /**
     * A potential (unconfigured) extension
     *
     * @param name the name of the extension
     */
    public JavaxWebSocketExtension(String name)
    {
        this.name = name;
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public List<Parameter> getParameters()
    {
        return parameters;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        JavaxWebSocketExtension that = (JavaxWebSocketExtension)o;

        return name != null ? name.equals(that.name) : that.name == null;
    }

    @Override
    public int hashCode()
    {
        return name != null ? name.hashCode() : 0;
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append(name);
        for (Parameter param : parameters)
        {
            str.append(';');
            str.append(param.getName());
            String value = param.getValue();
            if (value != null)
            {
                str.append('=');
                ExtensionConfig.quoteIfNeeded(str, value);
            }
        }
        return str.toString();
    }
}
