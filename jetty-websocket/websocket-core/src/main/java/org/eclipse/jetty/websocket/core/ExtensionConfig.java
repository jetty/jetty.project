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

package org.eclipse.jetty.websocket.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.util.ArrayTrie;
import org.eclipse.jetty.util.Trie;

/**
 * Represents an Extension Configuration, as seen during the connection Handshake process.
 */
public class ExtensionConfig
{
    private static Trie<ExtensionConfig> CACHE = new ArrayTrie<>(512);

    static
    {
        CACHE.put("identity", new ExtensionConfig("identity"));
        CACHE.put("permessage-deflate", new ExtensionConfig("permessage-deflate"));
        CACHE.put("permessage-deflate; client_max_window_bits", new ExtensionConfig("permessage-deflate; client_max_window_bits"));
    }

    /**
     * Parse a single parameterized name.
     *
     * @param parameterizedName the parameterized name
     * @return the ExtensionConfig
     */
    public static ExtensionConfig parse(String parameterizedName)
    {
        ExtensionConfig config = CACHE.get(parameterizedName);
        if (config != null)
            return config;
        return new ExtensionConfig(parameterizedName);
    }

    /**
     * Parse enumeration of {@code Sec-WebSocket-Extensions} header values into a {@code ExtensionConfig} list
     *
     * @param valuesEnum the raw header values enum
     * @return the list of extension configs
     */
    public static List<ExtensionConfig> parseEnum(Enumeration<String> valuesEnum)
    {
        List<ExtensionConfig> configs = new ArrayList<>();

        if (valuesEnum != null)
        {
            while (valuesEnum.hasMoreElements())
            {
                QuotedCSV csv = new QuotedCSV(valuesEnum.nextElement());
                Iterator<String> extTokenIter = csv.iterator();
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
     * Parse 1 or more raw {@code Sec-WebSocket-Extensions} header values into a {@code ExtensionConfig} list
     *
     * @param rawSecWebSocketExtensions the raw header values
     * @return the list of extension configs
     */
    public static List<ExtensionConfig> parseList(String... rawSecWebSocketExtensions)
    {
        List<ExtensionConfig> configs = new ArrayList<>();

        for (String rawValue : rawSecWebSocketExtensions)
        {
            QuotedCSV csv = new QuotedCSV(rawValue);
            Iterator<String> extTokenIter = csv.iterator();
            while (extTokenIter.hasNext())
            {
                String extToken = extTokenIter.next();
                configs.add(ExtensionConfig.parse(extToken));
            }
        }

        return configs;
    }

    /**
     * Convert a list of {@code ExtensionConfig} to a header value
     *
     * @param configs the list of extension configs
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
     *
     * @param copy the extension config to copy
     */
    public ExtensionConfig(ExtensionConfig copy)
    {
        this.name = copy.name;
        this.parameters = new HashMap<>();
        this.parameters.putAll(copy.parameters);
    }

    public ExtensionConfig(String name, Map<String, String> parameters)
    {
        this.name = name;
        this.parameters = new HashMap<>();
        this.parameters.putAll(parameters);
    }

    public ExtensionConfig(String parameterizedName)
    {
        ParamParser paramParser = new ParamParser(parameterizedName);
        List<String> keys = paramParser.parse();

        if (keys.size() > 1)
            throw new IllegalStateException("parameterizedName contains multiple ExtensionConfigs: " + parameterizedName);
        if (keys.isEmpty())
            throw new IllegalStateException("parameterizedName contains no ExtensionConfigs: " + parameterizedName);

        this.name = keys.get(0);
        this.parameters = new HashMap<>();
        this.parameters.putAll(paramParser.params.get(this.name));
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
        return Integer.parseInt(val);
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
                quoteIfNeeded(str, value);
            }
        }
        return str.toString();
    }

    public static void quoteIfNeeded(StringBuilder buf, String str)
    {
        if (str == null)
        {
            return;
        }
        // check for delimiters in input string
        int len = str.length();
        if (len == 0)
        {
            return;
        }
        int ch;
        for (int i = 0; i < len; i++)
        {
            ch = str.codePointAt(i);
            if (ch == ';' || ch == '=')
            {
                // found a special extension delimiter codepoints. we need to quote it.
                buf.append('"').append(str).append('"');
                return;
            }
        }

        // no special delimiters used, no quote needed.
        buf.append(str);
    }

    public final Set<String> getParameterKeys()
    {
        return Collections.unmodifiableSet(parameters.keySet());
    }

    /**
     * Return parameters found in request URI.
     *
     * @return the parameter map
     */
    public final Map<String, String> getParameters()
    {
        return Collections.unmodifiableMap(parameters);
    }

    public final void setParameter(String key)
    {
        parameters.put(key, null);
    }

    public final void setParameter(String key, int value)
    {
        parameters.put(key, Integer.toString(value));
    }

    public final void setParameter(String key, String value)
    {
        parameters.put(key, value);
    }

    @Override
    public String toString()
    {
        return getParameterizedName();
    }

    private static class ParamParser extends QuotedCSV
    {
        Map<String, Map<String, String>> params;

        public ParamParser(String rawParams)
        {
            super(false, rawParams);
        }

        @Override
        protected void parsedParam(StringBuffer buffer, int valueLength, int paramNameIdx, int paramValueIdx)
        {
            String extName = buffer.substring(0, valueLength);
            String paramName = "";
            String paramValue = null;

            if (paramValueIdx > 0)
            {
                paramName = buffer.substring(paramNameIdx, paramValueIdx - 1);
                paramValue = buffer.substring(paramValueIdx);
            }
            else if (paramNameIdx > 0)
            {
                paramName = buffer.substring(paramNameIdx);
            }

            Map<String, String> paramMap = getParamMap(extName);
            paramMap.put(paramName, paramValue);

            super.parsedParam(buffer, valueLength, paramNameIdx, paramValueIdx);
        }

        @Override
        protected void parsedValue(StringBuffer buffer)
        {
            String extName = buffer.toString();
            getParamMap(extName);
            super.parsedValue(buffer);
        }

        private Map<String, String> getParamMap(String extName)
        {
            if (params == null)
            {
                params = new HashMap<>();
            }

            Map<String, String> paramMap = params.get(extName);
            if (paramMap == null)
            {
                paramMap = new HashMap<>();
                params.put(extName, paramMap);
            }
            return paramMap;
        }

        public List<String> parse()
        {
            Iterator<String> iter = iterator();
            while (iter.hasNext())
            {
                iter.next();
            }

            return new ArrayList<>(params.keySet());
        }
    }
}
