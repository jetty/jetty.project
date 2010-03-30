// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.deploy.providers;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.ConfigurationManager;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.util.FileID;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * AppProvider for Monitoring directories for contexts.
 * 
 * A Context may either be a WAR, a directory or an XML descriptor.
 * 
 * @deprecated - Use {@link ContextProvider} or {@link WebAppProvider}
 */
public class MonitoredDirAppProvider extends AbstractLifeCycle implements AppProvider
{
    class MonitoredFilenameFilter implements FilenameFilter
    {
        public boolean accept(File file, String name)
        {
            if (!file.exists())
                return false;
            
            String lowername = name.toLowerCase();

            if (_acceptContextXmlFiles && !file.isDirectory() && lowername.endsWith(".xml"))
            {
                return true;
            }

            if (_acceptWarFiles && !file.isDirectory() && lowername.endsWith(".war"))
            {
                return true;
            }
            
            if (_acceptDirectories && file.isDirectory())
            {
                return true;
            }

            return false;
        }
    }

    private boolean _acceptContextXmlFiles = true;
    private boolean _acceptWarFiles = true;
    private boolean _acceptDirectories = true;
    private Resource _monitoredDir;
    private Scanner _scanner;
    private int _scanInterval = 10;
    private boolean _recursive = false;
    private boolean _extractWars = false;
    private boolean _parentLoaderPriority = false;
    private String _defaultsDescriptor;
    private DeploymentManager _deploymentManager;
    private FilenameFilter _filenamefilter;
    private ConfigurationManager _configurationManager;
    
    private final Scanner.DiscreteListener _scannerListener = new Scanner.DiscreteListener()
    {
        public void fileAdded(String filename) throws Exception
        {
            if (Log.isDebugEnabled()) Log.debug("added ",  filename);
            addConfiguredContextApp(filename);
        }

        public void fileChanged(String filename) throws Exception
        {
            System.err.println("changed "+filename);
            // TODO should this not be an add/remove?
            if (Log.isDebugEnabled()) Log.debug("changed ",  filename);
            addConfiguredContextApp(filename);
        }

        public void fileRemoved(String filename) throws Exception
        {
            if (Log.isDebugEnabled()) Log.debug("removed ",  filename);
            
            // TODO: How to determine ID from filename that doesn't exist?
            // TODO: we probably need a map from discovered filename to resulting App
        }
    };
    
    public MonitoredDirAppProvider()
    {
        _filenamefilter = new MonitoredFilenameFilter();
    }

    protected MonitoredDirAppProvider(boolean xml,boolean war, boolean dir)
    {
        _acceptContextXmlFiles=xml;
        _acceptWarFiles=war;
        _acceptDirectories=dir;
        _filenamefilter = new MonitoredFilenameFilter();
    }

    protected MonitoredDirAppProvider(FilenameFilter filter, boolean xml,boolean war, boolean dir)
    {
        _acceptContextXmlFiles=xml;
        _acceptWarFiles=war;
        _acceptDirectories=dir;
        _filenamefilter = filter;
    }
    
    private App addConfiguredContextApp(String filename)
    {
        App app = new App(_deploymentManager,this,filename);
        _deploymentManager.addApp(app);
        return app;
    }

    /* ------------------------------------------------------------ */
    /** Create a context Handler for an App instance.
     * This method can create a {@link ContextHandler} from a context XML file
     * or a {@link WebAppContext} from a WAR file or directory, depending on the 
     * settings of the accept fields.
     * @see #setAcceptContextXmlFiles(boolean)
     * @see #setAcceptWarFiles(boolean)
     * @see #setAcceptDirectories(boolean)
     * @see org.eclipse.jetty.deploy.AppProvider#createContextHandler(org.eclipse.jetty.deploy.App)
     */
    public ContextHandler createContextHandler(final App app) throws Exception
    {
        Resource resource = Resource.newResource(app.getOriginId());
        File file=resource.getFile();
        
        if (!resource.exists())
            throw new IllegalStateException("App resouce does not exist "+resource);

        if (_acceptContextXmlFiles &&  FileID.isXmlFile(file))
        {
            // TODO - it is a bit wierd that this ignores 
            // _defaultsDescriptor, _extractWars and _parentLoaderPriority
            // This reflects that there really is the common base for Context
            // and WebApp deployers should probably not have these bits in them
            
            XmlConfiguration xmlc = new XmlConfiguration(resource.getURL());
            Map props = new HashMap();
            props.put("Server",_deploymentManager.getServer());
            if (getConfigurationManager() != null)
                props.putAll(getConfigurationManager().getProperties());
            xmlc.setProperties(props);
            return (ContextHandler)xmlc.configure();
        }

        String context = file.getName();
        
        if (_acceptWarFiles && FileID.isWebArchiveFile(file))
        {
            // Context Path is the same as the archive.
            context = context.substring(0,context.length() - 4);
        }
        else if (_acceptDirectories && file.isDirectory())
        {
            // must be a directory
        }
        else
            throw new IllegalStateException("unable to create ContextHandler for "+app);

        
        // special case of archive (or dir) named "root" is / context
        if (context.equalsIgnoreCase("root") || context.equalsIgnoreCase("root/"))
            context = URIUtil.SLASH;

        // Ensure "/" is Prepended to all context paths.
        if (context.charAt(0) != '/')
            context = "/" + context;

        // Ensure "/" is Not Trailing in context paths.
        if (context.endsWith("/") && context.length() > 0)
            context = context.substring(0,context.length() - 1);

        WebAppContext wah = new WebAppContext();
        wah.setContextPath(context);
        wah.setWar(file.getAbsolutePath());
        if (_defaultsDescriptor != null)
            wah.setDefaultsDescriptor(_defaultsDescriptor);
        wah.setExtractWAR(_extractWars);
        wah.setParentLoaderPriority(_parentLoaderPriority);

        return wah;
        
    }

    @Override
    protected void doStart() throws Exception
    {
        if (_monitoredDir == null)
        {
            throw new IllegalStateException("No configuration dir specified");
        }

        File scandir = _monitoredDir.getFile();
        Log.info("Deployment monitor " + scandir+ " at interval "+_scanInterval);
        _scanner=new Scanner();
        _scanner.setScanDirs(Collections.singletonList(scandir));
        _scanner.setScanInterval(_scanInterval);
        _scanner.setRecursive(_recursive);
        _scanner.setFilenameFilter(_filenamefilter);
        _scanner.setReportDirs(_acceptDirectories);
        _scanner.addListener(_scannerListener);
        _scanner.start();
    }

    @Override
    protected void doStop() throws Exception
    {
        _scanner.stop();
        _scanner.removeListener(_scannerListener);
        _scanner=null;
    }

    public ConfigurationManager getConfigurationManager()
    {
        return _configurationManager;
    }

    public String getDefaultsDescriptor()
    {
        return _defaultsDescriptor;
    }

    /* ------------------------------------------------------------ */
    /** Get the deploymentManager.
     * @return the deploymentManager
     */
    public DeploymentManager getDeploymentManager()
    {
        return _deploymentManager;
    }

    public Resource getMonitoredDir()
    {
        return _monitoredDir;
    }

    public int getScanInterval()
    {
        return _scanInterval;
    }

    /* ------------------------------------------------------------ */
    /** Get the acceptContextXmlFiles.
     * @return the acceptContextXmlFiles
     */
    public boolean isAcceptContextXmlFiles()
    {
        return _acceptContextXmlFiles;
    }

    /* ------------------------------------------------------------ */
    /** Get the acceptDirectories.
     * @return the acceptDirectories
     */
    public boolean isAcceptDirectories()
    {
        return _acceptDirectories;
    }

    /* ------------------------------------------------------------ */
    /** Get the acceptWarFiles.
     * @return the acceptWarFiles
     */
    public boolean isAcceptWarFiles()
    {
        return _acceptWarFiles;
    }

    public boolean isExtractWars()
    {
        return _extractWars;
    }

    public boolean isParentLoaderPriority()
    {
        return _parentLoaderPriority;
    }

    public boolean isRecursive()
    {
        return _recursive;
    }

    public void setAcceptContextXmlFiles(boolean flag)
    {
        if (isRunning())
            throw new IllegalStateException();
        _acceptContextXmlFiles = flag;
    }

    public void setAcceptDirectories(boolean flag)
    {
        if (isRunning())
            throw new IllegalStateException();
        _acceptDirectories = flag;
    }

    public void setAcceptWarFiles(boolean flag)
    {
        if (isRunning())
            throw new IllegalStateException();
        _acceptWarFiles = flag;
    }

    public void setConfigurationManager(ConfigurationManager configurationManager)
    {
        _configurationManager = configurationManager;
    }

    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        _defaultsDescriptor = defaultsDescriptor;
    }

    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        _deploymentManager = deploymentManager;
    }

    public void setExtractWars(boolean extractWars)
    {
        _extractWars = extractWars;
    }

    public void setMonitoredDir(Resource contextsDir)
    {
        _monitoredDir = contextsDir;
    }

    /**
     * @param dir
     *            Directory to scan for context descriptors or war files
     */
    public void setMonitoredDir(String dir)
    {
        try
        {
            _monitoredDir = Resource.newResource(dir);
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException(e);
        }
    }
    
    public void setParentLoaderPriority(boolean parentLoaderPriority)
    {
        _parentLoaderPriority = parentLoaderPriority;
    }

    public void setRecursive(boolean recursive)
    {
        _recursive = recursive;
    }

    public void setScanInterval(int scanInterval)
    {
        _scanInterval = scanInterval;
    }
}
