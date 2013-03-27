//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
    private List<Parameter> parameters;

    /**
     * A configured extension
     */
    public JsrExtension(ExtensionConfig cfg)
    {
        this.name = cfg.getName();
        this.parameters = new ArrayList<>();
        if (cfg.getParameters() != null)
        {
            for (Map.Entry<String, String> entry : cfg.getParameters().entrySet())
            {
                parameters.add(new JsrParameter(entry.getKey(),entry.getValue()));
            }
        }
    }

    /**
     * A potential (unconfigured) extension
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
}
