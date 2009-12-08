// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.deploy;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
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
    private Properties _properties = new Properties();

    public FileConfigurationManager()
    {        
    }
    
    
    public void setFile (String filename) 
    throws MalformedURLException, IOException
    {
        _file = Resource.newResource(filename);
    }
    
    
    /** 
     * @see org.eclipse.jetty.deploy.ConfigurationManager#getProperties()
     */
    public Map<?,?> getProperties()
    {
        try
        {
            loadProperties();
            return _properties;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    
    private void loadProperties () 
    throws FileNotFoundException, IOException
    {
        if (_properties.isEmpty())
            _properties.load(_file.getInputStream());
    }
}
