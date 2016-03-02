//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/**
 */
@ManagedObject("Abstract Provider for loading webapps")
public abstract class ScanningAppProvider extends AbstractLifeCycle implements AppProvider
{
    private static final Logger LOG = Log.getLogger(ScanningAppProvider.class);

    private Map<String, App> _appMap = new HashMap<String, App>();

    private DeploymentManager _deploymentManager;
    protected FilenameFilter _filenameFilter;
    private final List<Resource> _monitored= new CopyOnWriteArrayList<>();
    private boolean _recursive = false;
    private int _scanInterval = 10;
    private Scanner _scanner;

    /* ------------------------------------------------------------ */
    private final Scanner.DiscreteListener _scannerListener = new Scanner.DiscreteListener()
    {
        @Override
        public void fileAdded(String filename) throws Exception
        {
            ScanningAppProvider.this.fileAdded(filename);
        }

        @Override
        public void fileChanged(String filename) throws Exception
        {
            ScanningAppProvider.this.fileChanged(filename);
        }

        @Override
        public void fileRemoved(String filename) throws Exception
        {
            ScanningAppProvider.this.fileRemoved(filename);
        }
    };

    /* ------------------------------------------------------------ */
    protected ScanningAppProvider()
    {
    }
    
    /* ------------------------------------------------------------ */
    protected ScanningAppProvider(FilenameFilter filter)
    {
        _filenameFilter = filter;
    }

    /* ------------------------------------------------------------ */
    protected void setFilenameFilter(FilenameFilter filter)
    {
        if (isRunning())
            throw new IllegalStateException();
        _filenameFilter = filter;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return The index of currently deployed applications.
     */
    protected Map<String, App> getDeployedApps()
    {
        return _appMap;
    }

    /* ------------------------------------------------------------ */
    /**
     * Called by the Scanner.DiscreteListener to create a new App object.
     * Isolated in a method so that it is possible to override the default App
     * object for specialized implementations of the AppProvider.
     * 
     * @param filename
     *            The file that is the context.xml. It is resolved by
     *            {@link Resource#newResource(String)}
     * @return The App object for this particular context definition file.
     */
    protected App createApp(String filename)
    {
        return new App(_deploymentManager,this,filename);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        if (LOG.isDebugEnabled()) 
            LOG.debug(this.getClass().getSimpleName() + ".doStart()");
        if (_monitored.size()==0)
            throw new IllegalStateException("No configuration dir specified");

        LOG.info("Deployment monitor " + _monitored + " at interval " + _scanInterval);
        List<File> files = new ArrayList<>();
        for (Resource resource:_monitored)
        {
            if (resource.exists() && resource.getFile().canRead())
                files.add(resource.getFile());
            else
                LOG.warn("Does not exist: "+resource);
        }
        
        _scanner = new Scanner();
        _scanner.setScanDirs(files);
        _scanner.setScanInterval(_scanInterval);
        _scanner.setRecursive(_recursive);
        _scanner.setFilenameFilter(_filenameFilter);
        _scanner.setReportDirs(true);
        _scanner.addListener(_scannerListener);
        _scanner.start();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        if (_scanner!=null)
        {
            _scanner.stop();
            _scanner.removeListener(_scannerListener);
            _scanner = null;
        }
    }
    
    /* ------------------------------------------------------------ */
    protected boolean exists(String path)
    {
        return _scanner.exists(path);
    }

    /* ------------------------------------------------------------ */
    protected void fileAdded(String filename) throws Exception
    {
        if (LOG.isDebugEnabled()) 
            LOG.debug("added {}",filename);
        App app = ScanningAppProvider.this.createApp(filename);
        if (app != null)
        {
            _appMap.put(filename,app);
            _deploymentManager.addApp(app);
        }
    }

    /* ------------------------------------------------------------ */
    protected void fileChanged(String filename) throws Exception
    {
        if (LOG.isDebugEnabled()) 
            LOG.debug("changed {}",filename);
        App app = _appMap.remove(filename);
        if (app != null)
        {
            _deploymentManager.removeApp(app);
        }
        app = ScanningAppProvider.this.createApp(filename);
        if (app != null)
        {
            _appMap.put(filename,app);
            _deploymentManager.addApp(app);
        }
    }
    
    /* ------------------------------------------------------------ */
    protected void fileRemoved(String filename) throws Exception
    {
        if (LOG.isDebugEnabled()) 
            LOG.debug("removed {}",filename);
        App app = _appMap.remove(filename);
        if (app != null)
            _deploymentManager.removeApp(app);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Get the deploymentManager.
     * 
     * @return the deploymentManager
     */
    public DeploymentManager getDeploymentManager()
    {
        return _deploymentManager;
    }


    /* ------------------------------------------------------------ */
    public Resource getMonitoredDirResource()
    {
        if (_monitored.size()==0)
            return null;
        if (_monitored.size()>1)
            throw new IllegalStateException();
        return _monitored.get(0);
    }

    /* ------------------------------------------------------------ */
    public String getMonitoredDirName()
    {
        Resource resource=getMonitoredDirResource();
        return resource==null?null:resource.toString();
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute("scanning interval to detect changes which need reloaded")
    public int getScanInterval()
    {
        return _scanInterval;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute("recursive scanning supported")
    public boolean isRecursive()
    {
        return _recursive;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        _deploymentManager = deploymentManager;
    }
    
    /* ------------------------------------------------------------ */
    public void setMonitoredResources(List<Resource> resources)
    {
        _monitored.clear();
        _monitored.addAll(resources);
    }
    
    /* ------------------------------------------------------------ */
    public List<Resource> getMonitoredResources()
    {
        return Collections.unmodifiableList(_monitored);
    }
    
    /* ------------------------------------------------------------ */
    public void setMonitoredDirResource(Resource resource)
    {
        setMonitoredResources(Collections.singletonList(resource));
    }

    /* ------------------------------------------------------------ */
    public void addScannerListener(Scanner.Listener listener)
    {
        _scanner.addListener(listener);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param dir
     *            Directory to scan for context descriptors or war files
     */
    public void setMonitoredDirName(String dir)
    {
        setMonitoredDirectories(Collections.singletonList(dir));
    }

    /* ------------------------------------------------------------ */
    public void setMonitoredDirectories(Collection<String> directories)
    {
        try
        {
            List<Resource> resources = new ArrayList<>();
            for (String dir:directories)
                resources.add(Resource.newResource(dir));
            setMonitoredResources(resources);
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException(e);
        }
    }
    
    /* ------------------------------------------------------------ */
    protected void setRecursive(boolean recursive)
    {
        _recursive = recursive;
    }

    /* ------------------------------------------------------------ */
    public void setScanInterval(int scanInterval)
    {
        _scanInterval = scanInterval;
    }
}
