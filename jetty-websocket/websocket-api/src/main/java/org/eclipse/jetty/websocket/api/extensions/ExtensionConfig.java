//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.websocket.api.util.QuoteUtil;

/**
 * Represents an Extension Configuration, as seen during the connection Handshake process.
 */
public class ExtensionConfig
{
    /**
     * Parse a single parameterized name.
     * 
     * @param parameterizedName
     *            the parameterized name
     * @return the ExtensionConfig
     */
    public static ExtensionConfig parse(String parameterizedName)
    {
        return new ExtensionConfig(parameterizedName);
    }

    /**
     * Parse enumeration of <code>Sec-WebSocket-Extensions</code> header values into a {@link ExtensionConfig} list
     * 
     * @param valuesEnum
     *            the raw header values enum
     * @return the list of extension configs
     */
    public static List<ExtensionConfig> parseEnum(Enumeration<String> valuesEnum)
    {
        List<ExtensionConfig> configs = new ArrayList<>();

        if (valuesEnum != null)
        {
            while (valuesEnum.hasMoreElements())
            {
                Iterator<String> extTokenIter = QuoteUtil.splitAt(valuesEnum.nextElement(),",");
                while (extTokenIter.hasNext())
                {
                    String extToken = extTokenIter.next();
                    configs.add(ExtensionConfig.parse(extToken));
                }
            }
        }

        return configs;
    }

    /**
     * Parse 1 or more raw <code>Sec-WebSocket-Extensions</code> header values into a {@link ExtensionConfig} list
     * 
     * @param rawSecWebSocketExtensions
     *            the raw header values
     * @return the list of extension configs
     */
    public static List<ExtensionConfig> parseList(String... rawSecWebSocketExtensions)
    {
        List<ExtensionConfig> configs = new ArrayList<>();

        for (String rawValue : rawSecWebSocketExtensions)
        {
            Iterator<String> extTokenIter = QuoteUtil.splitAt(rawValue,",");
            while (extTokenIter.hasNext())
            {
                String extToken = extTokenIter.next();
                configs.add(ExtensionConfig.parse(extToken));
            }
        }

        return configs;
    }

    /**
     * Convert a list of {@link ExtensionConfig} to a header value
     * 
     * @param configs
     *            the list of extension configs
     * @return the header value (null if no configs present)
     */
    public static String toHeaderValue(List<ExtensionConfig> configs)
    {
        if ((configs == null) || (configs.isEmpty()))
        {
            return null;
        }
        StringBuilder parameters = new StringBuilder();
        boolean needsDelim = false;
        for (ExtensionConfig ext : configs)
        {
            if (needsDelim)
            {
                parameters.append(", ");
            }
            parameters.append(ext.getParameterizedName());
            needsDelim = true;
        }
        return parameters.toString();
    }

    private final String name;
    private final Map<String, String> parameters;

    /**
     * Copy constructor
     * @param copy the extension config to copy
     */
    public ExtensionConfig(ExtensionConfig copy)
    {
        this.name = copy.name;
        this.parameters = new HashMap<>();
        this.parameters.putAll(copy.parameters);
    }

    public ExtensionConfig(String parameterizedName)
    {
        Iterator<String> extListIter = QuoteUtil.splitAt(parameterizedName,";");
        this.name = extListIter.next();
        this.parameters = new HashMap<>();

        // now for parameters
        while (extListIter.hasNext())
        {
            String extParam = extListIter.next();
            Iterator<String> extParamIter = QuoteUtil.splitAt(extParam,"=");
            String key = extParamIter.next().trim();
            String value = null;
            if (extParamIter.hasNext())
            {
                value = extParamIter.next();
            }
            parameters.put(key,value);
        }
    }

    public String getName()
    {
        return name;
    }

    public final int getParameter(String key, int defValue)
    {
        String val = parameters.get(key);
        if (val == null)
        {
            return defValue;
        }
        return Integer.valueOf(val);
    }

    public final String getParameter(String key, String defValue)
    {
        String val = parameters.get(key);
        if (val == null)
        {
            return defValue;
        }
        return val;
    }

    public final String getParameterizedName()
    {
        StringBuilder str = new StringBuilder();
        str.append(name);
        for (String param : parameters.keySet())
        {
            str.append(';');
            str.append(param);
            String value = parameters.get(param);
            if (value != null)
            {
                str.append('=');
                QuoteUtil.quoteIfNeeded(str,value,";=");
            }
        }
        return str.toString();
    }

    public final Set<String> getParameterKeys()
    {
        return parameters.keySet();
    }

    /**
     * Return parameters found in request URI.
     * 
     * @return the parameter map
     */
    public final Map<String, String> getParameters()
    {
        return parameters;
    }

    /**
     * Initialize the parameters on this config from the other configuration.
     * 
     * @param other
     *            the other configuration.
     */
    public final void init(ExtensionConfig other)
    {
        this.parameters.clear();
        this.parameters.putAll(other.parameters);
    }

    public final void setParameter(String key)
    {
        parameters.put(key,null);
    }

    public final void setParameter(String key, int value)
    {
        parameters.put(key,Integer.toString(value));
    }

    public final void setParameter(String key, String value)
    {
        parameters.put(key,value);
    }

    @Override
    public String toString()
    {
        return getParameterizedName();
    }
}
