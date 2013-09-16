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

package org.eclipse.jetty.deploy.providers;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/**
 */
public abstract class ScanningAppProvider extends AbstractLifeCycle implements AppProvider
{
    private static final Logger LOG = Log.getLogger(ScanningAppProvider.class);

    private Map<String, App> _appMap = new HashMap<String, App>();

    private DeploymentManager _deploymentManager;
    protected final FilenameFilter _filenameFilter;
    private Resource _monitoredDir;
    private boolean _recursive = false;
    private int _scanInterval = 10;
    private Scanner _scanner;

    /* ------------------------------------------------------------ */
    private final Scanner.DiscreteListener _scannerListener = new Scanner.DiscreteListener()
    {
        public void fileAdded(String filename) throws Exception
        {
            ScanningAppProvider.this.fileAdded(filename);
        }

        public void fileChanged(String filename) throws Exception
        {
            ScanningAppProvider.this.fileChanged(filename);
        }

        public void fileRemoved(String filename) throws Exception
        {
            ScanningAppProvider.this.fileRemoved(filename);
        }
    };

    /* ------------------------------------------------------------ */
    protected ScanningAppProvider(FilenameFilter filter)
    {
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
        if (_monitoredDir == null)
        {
            throw new IllegalStateException("No configuration dir specified");
        }

        File scandir = _monitoredDir.getFile();
        LOG.info("Deployment monitor " + scandir + " at interval " + _scanInterval);
        _scanner = new Scanner();
        _scanner.setScanDirs(Collections.singletonList(scandir));
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
        return _monitoredDir;
    }

    /* ------------------------------------------------------------ */
    public String getMonitoredDirName()
    {
        return _monitoredDir.toString();
    }

    /* ------------------------------------------------------------ */
    public int getScanInterval()
    {
        return _scanInterval;
    }

    /* ------------------------------------------------------------ */
    public boolean isRecursive()
    {
        return _recursive;
    }

    /* ------------------------------------------------------------ */
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        _deploymentManager = deploymentManager;
    }
    
    /* ------------------------------------------------------------ */
    public void setMonitoredDirResource(Resource contextsDir)
    {
        _monitoredDir = contextsDir;
    }

    /* ------------------------------------------------------------ */
    public void addScannerListener(Scanner.Listener listener)
    {
        _scanner.addListener(listener);
    }

    /* ------------------------------------------------------------ */
    /**
     * @deprecated Use {@link #setMonitoredDirName(String)}
     */
    public void setMonitoredDir(String dir)
    {
        setMonitoredDirName(dir);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param dir
     *            Directory to scan for context descriptors or war files
     */
    public void setMonitoredDirName(String dir)
    {
        try
        {
            setMonitoredDirResource(Resource.newResource(dir));
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
