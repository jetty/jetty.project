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

package org.eclipse.jetty.deploy;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.resource.Resource;

/**
 * FileConfigurationManager
 *
 * Supplies properties defined in a file.
 */
@ManagedObject("Configure deployed webapps via properties")
public class PropertiesConfigurationManager implements ConfigurationManager, Dumpable
{
    private String _properties;
    private final Map<String, String> _map = new HashMap<>();

    public PropertiesConfigurationManager(String properties)
    {
        if (properties != null)
        {
            try
            {
                setFile(properties);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    public PropertiesConfigurationManager()
    {
        this(null);
    }

    public void setFile(String resource) throws IOException
    {
        _properties = resource;
        _map.clear();
        loadProperties(_properties);
    }

    @ManagedAttribute("A file or URL of properties")
    public String getFile()
    {
        return _properties;
    }

    @ManagedOperation("Set a property")
    public void put(@Name("name") String name, @Name("value") String value)
    {
        _map.put(name, value);
    }

    @Override
    public Map<String, String> getProperties()
    {
        return _map;
    }

    private void loadProperties(String resource) throws FileNotFoundException, IOException
    {
        Resource file = Resource.newResource(resource);
        if (file != null && file.exists())
        {
            Properties properties = new Properties();
            properties.load(file.getInputStream());
            for (Map.Entry<Object, Object> entry : properties.entrySet())
                _map.put(entry.getKey().toString(), String.valueOf(entry.getValue()));
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s}", this.getClass(), hashCode(), _properties);
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, toString(), _map);
    }
}
