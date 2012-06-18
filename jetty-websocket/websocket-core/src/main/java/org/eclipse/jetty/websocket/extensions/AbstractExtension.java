/*******************************************************************************
 * Copyright (c) 2011 Intalio, Inc.
 * ======================================================================
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *   The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 *
 *   The Apache License v2.0 is available at
 *   http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 *******************************************************************************/
package org.eclipse.jetty.websocket.extensions;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.util.QuotedStringTokenizer;

public class AbstractExtension implements Extension
{
    private final String name;
    private final Map<String, String> parameters = new HashMap<String, String>();

    public AbstractExtension(String name)
    {
        this.name = name;
    }

    public int getInitParameter(String name, int dft)
    {
        String v = parameters.get(name);
        if (v==null)
        {
            return dft;
        }
        return Integer.valueOf(v);
    }

    public String getInitParameter(String name,String dft)
    {
        if (!parameters.containsKey(name))
        {
            return dft;
        }
        return parameters.get(name);
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public String getParameterizedName()
    {
        StringBuilder name = new StringBuilder();
        name.append(name);
        for (String param : parameters.keySet())
        {
            name.append(';').append(param).append('=').append(QuotedStringTokenizer.quoteIfNeeded(parameters.get(param),";="));
        }
        return name.toString();
    }

    @Override
    public boolean init(Map<String, String> parameters)
    {
        parameters.putAll(parameters);
        return true;
    }

    @Override
    public String toString()
    {
        return getParameterizedName();
    }
}
