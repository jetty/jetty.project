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

package org.eclipse.jetty.websocket.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;

/**
 * Represents an Extension Configuration, as seen during the connection Handshake process.
 */
public class ExtensionConfig
{
    private static final Index<ExtensionConfig> CACHE = new Index.Builder<ExtensionConfig>()
        .caseSensitive(false)
        .with("identity", new ExtensionConfig("identity"))
        .with("permessage-deflate", new ExtensionConfig("permessage-deflate"))
        .with("permessage-deflate; client_max_window_bits", new ExtensionConfig("permessage-deflate; client_max_window_bits"))
        .build();

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
        paramParser.parse();
        this.name = paramParser.getName();
        this.parameters = paramParser.getParams();
    }

    public boolean isInternalExtension()
    {
        return name.startsWith("@");
    }

    public List<Map.Entry<String, String>> getInternalParameters()
    {
        return parameters.entrySet().stream().filter(entry -> entry.getKey().startsWith("@")).collect(Collectors.toList());
    }

    public void removeInternalParameters()
    {
        parameters.entrySet().removeIf(entry -> entry.getKey().startsWith("@"));
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

    public final String getParameterizedNameWithoutInternalParams()
    {
        StringBuilder str = new StringBuilder();
        str.append(name);
        for (String param : parameters.keySet())
        {
            if (param.startsWith("@"))
                continue;

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
        private final String parameterizedName;
        private String name;
        private final Map<String, String> params = new HashMap<>();

        public ParamParser(String parameterizedName)
        {
            super(false);
            this.parameterizedName = parameterizedName;
        }

        public String getName()
        {
            return name;
        }

        public Map<String, String> getParams()
        {
            return params;
        }

        @Override
        protected void parsedParam(StringBuffer buffer, int valueLength, int paramNameIdx, int paramValueIdx)
        {
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

            params.put(paramName, paramValue);
            super.parsedParam(buffer, valueLength, paramNameIdx, paramValueIdx);
        }

        @Override
        protected void parsedValue(StringBuffer buffer)
        {
            String extName = buffer.toString();
            if (name != null)
                throw new IllegalArgumentException("parameterizedName contains multiple ExtensionConfigs: " + parameterizedName);
            name = extName;
            super.parsedValue(buffer);
        }

        public void parse()
        {
            addValue(parameterizedName);
            if (StringUtil.isEmpty(name))
                throw new IllegalArgumentException("parameterizedName contains no ExtensionConfigs: " + parameterizedName);
        }
    }
}
