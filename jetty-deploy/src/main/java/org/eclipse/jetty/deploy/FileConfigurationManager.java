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

package org.eclipse.jetty.deploy;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.eclipse.jetty.util.resource.Resource;

/**
 * FileConfigurationManager
 * 
 * Supplies properties defined in a file.
 */
public class FileConfigurationManager implements ConfigurationManager
{
    private Resource _file;
    private Map<String,String> _map = new HashMap<String,String>();

    public FileConfigurationManager()
    {
    }

    public void setFile(String filename) throws MalformedURLException, IOException
    {
        _file = Resource.newResource(filename);
    }

    /**
     * @see org.eclipse.jetty.deploy.ConfigurationManager#getProperties()
     */
    public Map<String, String> getProperties()
    {
        try
        {
            loadProperties();
            return _map;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private void loadProperties() throws FileNotFoundException, IOException
    {
        if (_map.isEmpty() && _file!=null)
        {
            Properties properties = new Properties();
            properties.load(_file.getInputStream());
            for (Map.Entry<Object, Object> entry : properties.entrySet())
                _map.put(entry.getKey().toString(),String.valueOf(entry.getValue()));
        }
    }
}
