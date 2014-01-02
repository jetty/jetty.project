//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.start;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.start.Props.Prop;

/**
 * Management of Properties.
 * <p>
 * This is larger in scope than the standard {@link java.util.Properties}, as it will also handle tracking the origin of each property, if it was overridden,
 * and also allowing for <code>${property}</code> expansion.
 */
public final class Props implements Iterable<Prop>
{
    public static class Prop
    {
        public String key;
        public String value;
        public String origin;
        public Prop overrides;

        public Prop(String key, String value, String origin)
        {
            this.key = key;
            this.value = value;
            this.origin = origin;
        }

        public Prop(String key, String value, String origin, Prop overrides)
        {
            this(key,value,origin);
            this.overrides = overrides;
        }
    }

    public static final String ORIGIN_SYSPROP = "<system-property>";

    private Map<String, Prop> props = new HashMap<>();

    public String cleanReference(String property)
    {
        String name = property.trim();
        if (name.startsWith("${") && name.endsWith("}"))
        {
            name = name.substring(2,name.length() - 1);
        }
        return name.trim();
    }

    public boolean containsKey(String key)
    {
        return props.containsKey(key);
    }

    public String expand(String str)
    {
        return expand(str,new Stack<String>());
    }

    public String expand(String str, Stack<String> seenStack)
    {
        if (str == null)
        {
            return str;
        }

        if (str.indexOf("${") < 0)
        {
            // Contains no potential expressions.
            return str;
        }

        if (props.isEmpty())
        {
            // This is a stupid programming error, we should have something, even system properties
            throw new PropsException("Props is empty: no properties declared!?");
        }

        Pattern pat = Pattern.compile("(?<=[^$]|^)(\\$\\{[^}]*\\})");
        Matcher mat = pat.matcher(str);
        StringBuilder expanded = new StringBuilder();
        int offset = 0;
        String property;
        String value;

        while (mat.find(offset))
        {
            property = cleanReference(mat.group(1));

            // Loop detection
            if (seenStack.contains(property))
            {
                StringBuilder err = new StringBuilder();
                err.append("Property expansion loop detected: ");
                int idx = seenStack.lastIndexOf(property);
                for (int i = idx; i < seenStack.size(); i++)
                {
                    err.append(seenStack.get(i));
                    err.append(" -> ");
                }
                err.append(property);
                throw new PropsException(err.toString());
            }

            seenStack.push(property);

            // find property name
            expanded.append(str.subSequence(offset,mat.start(1)));
            // get property value
            value = getString(property);
            if (value == null)
            {
                StartLog.debug("Unable to expand: %s",property);
                expanded.append(property);
            }
            else
            {
                // recursively expand
                value = expand(value,seenStack);
                expanded.append(value);
            }
            // update offset
            offset = mat.end(1);
        }

        // leftover
        expanded.append(str.substring(offset));

        // special case for "$$"
        if (expanded.indexOf("$$") >= 0)
        {
            return expanded.toString().replaceAll("\\$\\$","\\$");
        }

        return expanded.toString();
    }

    public Prop getProp(String key)
    {
        Prop prop = props.get(key);
        if (prop == null)
        {
            // try system property
            prop = getSystemProperty(key);
        }
        return prop;
    }

    public String getString(String key)
    {
        if (key == null)
        {
            throw new PropsException("Cannot get value for null key");
        }

        String name = cleanReference(key);

        if (name.length() == 0)
        {
            throw new PropsException("Cannot get value for empty key");
        }

        Prop prop = getProp(name);
        if (prop == null)
        {
            return null;
        }
        return prop.value;
    }

    public String getString(String key, String defVal)
    {
        String val = getString(key);
        if (val == null)
        {
            return defVal;
        }
        return val;
    }

    private Prop getSystemProperty(String key)
    {
        String value = System.getProperty(key);
        if (value == null)
        {
            return null;
        }
        return new Prop(key,value,ORIGIN_SYSPROP);
    }

    @Override
    public Iterator<Prop> iterator()
    {
        return props.values().iterator();
    }

    public void setProperty(Prop prop)
    {
        props.put(prop.key,prop);
    }

    public void setProperty(String key, String value, String origin)
    {
        Prop prop = props.get(key);
        if (prop == null)
        {
            prop = new Prop(key,value,origin);
        }
        else
        {
            prop = new Prop(key,value,origin,prop);
        }
        props.put(key,prop);
    }

    public int size()
    {
        return props.size();
    }

    public void store(OutputStream stream, String comments) throws IOException
    {
        Properties props = new Properties();
        // add all Props as normal properties, with expansion performed.
        for (Prop prop : this)
        {
            props.setProperty(prop.key,expand(prop.value));
        }
        // write normal properties file
        props.store(stream,comments);
    }
}
