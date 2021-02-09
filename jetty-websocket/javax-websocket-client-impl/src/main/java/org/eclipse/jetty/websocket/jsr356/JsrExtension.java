//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.jsr356;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.websocket.Extension;

import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.util.QuoteUtil;

public class JsrExtension implements Extension
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
    public JsrExtension(ExtensionConfig cfg)
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
    public JsrExtension(String name)
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

        JsrExtension that = (JsrExtension)o;

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
                QuoteUtil.quoteIfNeeded(str, value, ";=");
            }
        }
        return str.toString();
    }
}
