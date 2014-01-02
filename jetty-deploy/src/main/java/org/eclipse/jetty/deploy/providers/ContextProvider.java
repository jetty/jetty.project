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

package org.eclipse.jetty.deploy.providers;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Locale;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.ConfigurationManager;
import org.eclipse.jetty.deploy.util.FileID;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;

/** Context directory App Provider.
 * <p>This specialization of {@link ScanningAppProvider} is the
 * replacement for the old (and deprecated) <code>org.eclipse.jetty.deploy.ContextDeployer</code> and it will scan a directory
 * only for context.xml files.
 */
public class ContextProvider extends ScanningAppProvider
{
    private ConfigurationManager _configurationManager;
    private boolean _parentLoaderPriority = false;
    private String _defaultsDescriptor;

    public ContextProvider()
    {
        super(  new FilenameFilter()
        {
            public boolean accept(File dir, String name)
            {
                if (!dir.exists())
                    return false;
                String lowername = name.toLowerCase(Locale.ENGLISH);
                if (lowername.startsWith("."))
                    return false;
                
                return  (lowername.endsWith(".xml") && !new File(dir,name).isDirectory());
            }
        });
    }


    /* ------------------------------------------------------------ */
    public ConfigurationManager getConfigurationManager()
    {
        return _configurationManager;
    }
    
    /* ------------------------------------------------------------ */
    /** Set the configurationManager.
     * @param configurationManager the configurationManager to set
     */
    public void setConfigurationManager(ConfigurationManager configurationManager)
    {
        _configurationManager = configurationManager;
    }

    /* ------------------------------------------------------------ */
    public ContextHandler createContextHandler(App app) throws Exception
    {
        Resource resource = Resource.newResource(app.getOriginId());
        File file = resource.getFile();
        
        if (resource.exists() && FileID.isXmlFile(file))
        {
            XmlConfiguration xmlc = new XmlConfiguration(resource.getURL())
            {
                @Override
                public void initializeDefaults(Object context)
                {
                    super.initializeDefaults(context);
                    
                    if (context instanceof WebAppContext)
                    {
                        WebAppContext webapp = (WebAppContext)context;
                        webapp.setParentLoaderPriority(_parentLoaderPriority);
                        if (_defaultsDescriptor!=null)
                            webapp.setDefaultsDescriptor(_defaultsDescriptor);
                    }
                }
            };
            
            xmlc.getIdMap().put("Server",getDeploymentManager().getServer());
            if (getConfigurationManager() != null)
                xmlc.getProperties().putAll(getConfigurationManager().getProperties());
            return (ContextHandler)xmlc.configure();
        }
        
        throw new IllegalStateException("App resouce does not exist "+resource);
    }

    /* ------------------------------------------------------------ */
    /** Get the parentLoaderPriority.
     * @return the parentLoaderPriority
     */
    public boolean isParentLoaderPriority()
    {
        return _parentLoaderPriority;
    }

    /* ------------------------------------------------------------ */
    /** Set the parentLoaderPriority.
     * <p>If the context created is a WebAppContext, then set the 
     * default value for {@link WebAppContext#setParentLoaderPriority(boolean)}.
     * @param parentLoaderPriority the parentLoaderPriority to set
     */
    public void setParentLoaderPriority(boolean parentLoaderPriority)
    {
        _parentLoaderPriority = parentLoaderPriority;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the defaultsDescriptor.
     * @return the defaultsDescriptor
     */
    public String getDefaultsDescriptor()
    {
        return _defaultsDescriptor;
    }

    /* ------------------------------------------------------------ */
    /** Set the defaultsDescriptor.
     * <p>If the context created is a WebAppContext, then set the 
     * default value for {@link WebAppContext#setDefaultsDescriptor(String)}
     * @param defaultsDescriptor the defaultsDescriptor to set
     */
    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        _defaultsDescriptor = defaultsDescriptor;
    }
}
