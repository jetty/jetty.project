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

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jetty.websocket.core.ExtensionConfig;

public class ExtensionConfigParser implements org.eclipse.jetty.websocket.api.extensions.ExtensionConfig.Parser
{
    /**
     * Parse a single parameterized name.
     *
     * @param parameterizedName the parameterized name
     * @return the ExtensionConfig
     */
    @Override
    public JettyExtensionConfig parse(String parameterizedName)
    {
        return new JettyExtensionConfig(ExtensionConfig.parse(parameterizedName));
    }

    /**
     * Parse enumeration of {@code Sec-WebSocket-Extensions} header values into a {@code ExtensionConfig} list
     *
     * @param valuesEnum the raw header values enum
     * @return the list of extension configs
     */
    @Override
    public List<org.eclipse.jetty.websocket.api.extensions.ExtensionConfig> parseEnum(Enumeration<String> valuesEnum)
    {
        List<org.eclipse.jetty.websocket.api.extensions.ExtensionConfig> configs = new ArrayList<>();
        for (ExtensionConfig config : ExtensionConfig.parseEnum(valuesEnum))
            configs.add(new JettyExtensionConfig(config));
        return configs;
    }

    /**
     * Parse 1 or more raw {@code Sec-WebSocket-Extensions} header values into a {@code ExtensionConfig} list
     *
     * @param rawSecWebSocketExtensions the raw header values
     * @return the list of extension configs
     */
    @Override
    public List<org.eclipse.jetty.websocket.api.extensions.ExtensionConfig> parseList(String... rawSecWebSocketExtensions)
    {
        List<org.eclipse.jetty.websocket.api.extensions.ExtensionConfig> configs = new ArrayList<>();
        for (ExtensionConfig config : ExtensionConfig.parseList(rawSecWebSocketExtensions))
            configs.add(new JettyExtensionConfig(config));
        return configs;
    }

    /**
     * Convert a list of {@code ExtensionConfig} to a header value
     *
     * @param configs the list of extension configs
     * @return the header value (null if no configs present)
     */
    @Override
    public String toHeaderValue(List<org.eclipse.jetty.websocket.api.extensions.ExtensionConfig> configs)
    {
        return ExtensionConfig.toHeaderValue(configs.stream()
                .map(c->new ExtensionConfig(c.getName(), c.getParameters()))
                .collect(Collectors.toList()));
    }
}
