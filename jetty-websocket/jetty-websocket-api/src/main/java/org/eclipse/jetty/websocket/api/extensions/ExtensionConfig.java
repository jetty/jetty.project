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

package org.eclipse.jetty.websocket.api.extensions;

import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Represents an Extension Configuration, as seen during the connection Handshake process.
 */
public interface ExtensionConfig
{
    interface Parser
    {
        ExtensionConfig parse(String parameterizedName);
        List<ExtensionConfig> parseEnum(Enumeration<String> valuesEnum);
        List<ExtensionConfig> parseList(String... rawSecWebSocketExtensions);
        String toHeaderValue(List<ExtensionConfig> configs);
    }

    private static ExtensionConfig.Parser getParser()
    {
        return ServiceLoader.load(ExtensionConfig.Parser.class).findFirst().get();
    }

    static ExtensionConfig parse(String parameterizedName)
    {
        return getParser().parse(parameterizedName);
    }

    static List<ExtensionConfig> parseEnum(Enumeration<String> valuesEnum)
    {
        return getParser().parseEnum(valuesEnum);
    }

    static List<ExtensionConfig> parseList(String... rawSecWebSocketExtensions)
    {
        return getParser().parseList(rawSecWebSocketExtensions);
    }

    static String toHeaderValue(List<ExtensionConfig> configs)
    {
        return getParser().toHeaderValue(configs);
    }

    String getName();

    int getParameter(String key, int defValue);

    String getParameter(String key, String defValue);

    String getParameterizedName();

    Set<String> getParameterKeys();

    /**
     * Return parameters found in request URI.
     *
     * @return the parameter map
     */
    Map<String, String> getParameters();

    void setParameter(String key);

    void setParameter(String key, int value);

    void setParameter(String key, String value);
}
