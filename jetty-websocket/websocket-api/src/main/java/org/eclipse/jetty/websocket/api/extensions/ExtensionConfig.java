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

package org.eclipse.jetty.websocket.api.extensions;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.websocket.api.util.QuoteUtil;

/**
 * Represents an Extension Configuration, as seen during the connection Handshake process.
 */
public class ExtensionConfig
{
    public static ExtensionConfig parse(String parameterizedName)
    {
        Iterator<String> extListIter = QuoteUtil.splitAt(parameterizedName,";");
        String extToken = extListIter.next();

        ExtensionConfig ext = new ExtensionConfig(extToken);

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
            ext.setParameter(key,value);
        }

        return ext;
    }

    private final String name;
    private Map<String, String> parameters;

    public ExtensionConfig(String name)
    {
        this.name = name;
        this.parameters = new HashMap<>();
    }

    public String getName()
    {
        return name;
    }

    public int getParameter(String key, int defValue)
    {
        String val = parameters.get(key);
        if (val == null)
        {
            return defValue;
        }
        return Integer.valueOf(val);
    }

    public String getParameter(String key, String defValue)
    {
        String val = parameters.get(key);
        if (val == null)
        {
            return defValue;
        }
        return val;
    }

    public String getParameterizedName()
    {
        StringBuilder str = new StringBuilder();
        str.append(name);
        for (String param : parameters.keySet())
        {
            str.append(';');
            str.append(param);
            str.append('=');
            QuoteUtil.quoteIfNeeded(str,parameters.get(param),";=");
        }
        return str.toString();
    }

    public Set<String> getParameterKeys()
    {
        return parameters.keySet();
    }

    /**
     * Return parameters found in request URI.
     * 
     * @return the parameter map
     */
    public Map<String, String> getParameters()
    {
        return parameters;
    }

    /**
     * Initialize the parameters on this config from the other configuration.
     * 
     * @param other
     *            the other configuration.
     */
    public void init(ExtensionConfig other)
    {
        this.parameters.clear();
        this.parameters.putAll(other.parameters);
    }

    public void setParameter(String key, int value)
    {
        parameters.put(key,Integer.toString(value));
    }

    public void setParameter(String key, String value)
    {
        parameters.put(key,value);
    }

    @Override
    public String toString()
    {
        return getParameterizedName();
    }
}