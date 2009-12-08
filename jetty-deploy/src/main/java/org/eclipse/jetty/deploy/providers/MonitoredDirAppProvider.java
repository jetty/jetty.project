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
import java.util.Collections;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;

/**
 * Backwards Compatible AppProvider for Monitoring a Contexts directory and deploying All Contexts.
 * 
 * Similar in scope to the original org.eclipse.jetty.deploy.ContextDeployer
 */
public class MonitoredDirAppProvider extends AbstractLifeCycle implements AppProvider, Scanner.DiscreteListener
{
    class ExtensionFilenameFilter implements FilenameFilter
    {
        boolean acceptXml = true;
        boolean acceptWar = true;

        public boolean accept(File dir, String name)
        {
            String lowername = name.toLowerCase();

            if (acceptXml && (lowername.endsWith(".xml")))
            {
                return true;
            }

            if (acceptWar && (lowername.endsWith(".war")))
            {
                return true;
            }

            return false;
        }
    }

    private Resource monitoredDir;
    private Scanner scanner;
    private int scanInterval = 10;
    private boolean recursive = false;
    private boolean extractWars = false;
    private boolean parentLoaderPriority = false;
    private String defaultsDescriptor;
    private DeploymentManager deploymgr;
    private ExtensionFilenameFilter filenamefilter;

    public MonitoredDirAppProvider()
    {
        scanner = new Scanner();
        filenamefilter = new ExtensionFilenameFilter();
    }

    private void addConfiguredContextApp(String filename)
    {
        String originId = filename;
        App app = new App(deploymgr,originId,new File(filename));
        app.setExtractWars(this.extractWars);
        app.setParentLoaderPriority(this.parentLoaderPriority);
        app.setDefaultsDescriptor(this.defaultsDescriptor);
        this.deploymgr.addApp(app);
    }

    public void fileAdded(String filename) throws Exception
    {
        Log.info("fileAdded(" + filename + ")");
        addConfiguredContextApp(filename);
    }

    public void fileChanged(String filename) throws Exception
    {
        Log.info("fileChanged(" + filename + ")");
        addConfiguredContextApp(filename);
    }

    public void fileRemoved(String filename) throws Exception
    {
        // TODO: How to determine ID from filename that doesn't exist?
        /*
        Log.info("fileRemoved(" + filename + ")");
        addConfiguredContextApp(filename);
         */
    }

    public String getDefaultsDescriptor()
    {
        return defaultsDescriptor;
    }

    public Resource getMonitoredDir()
    {
        return monitoredDir;
    }

    public int getScanInterval()
    {
        return scanInterval;
    }

    public boolean isExtractWars()
    {
        return extractWars;
    }

    public boolean isParentLoaderPriority()
    {
        return parentLoaderPriority;
    }

    public boolean isRecursive()
    {
        return recursive;
    }

    public void setAcceptContextXmlFiles(boolean flag)
    {
        filenamefilter.acceptXml = flag;
    }

    public void setAcceptWarFiles(boolean flag)
    {
        filenamefilter.acceptWar = flag;
    }

    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        this.defaultsDescriptor = defaultsDescriptor;
    }

    public void setExtractWars(boolean extractWars)
    {
        this.extractWars = extractWars;
    }

    public void setMonitoredDir(Resource contextsDir)
    {
        this.monitoredDir = contextsDir;
    }

    /**
     * @param dir
     *            Directory to scan for context descriptors or war files
     */
    public void setMonitoredDir(String dir)
    {
        try
        {
            monitoredDir = Resource.newResource(dir);
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    public void setParentLoaderPriority(boolean parentLoaderPriority)
    {
        this.parentLoaderPriority = parentLoaderPriority;
    }

    public void setRecursive(boolean recursive)
    {
        this.recursive = recursive;
    }

    public void setScanInterval(int scanInterval)
    {
        this.scanInterval = scanInterval;
    }

    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        this.deploymgr = deploymentManager;
    }

    @Override
    protected void doStart() throws Exception
    {
        Log.info(this.getClass().getSimpleName() + ".doStart()");
        if (monitoredDir == null)
        {
            throw new IllegalStateException("No configuration dir specified");
        }

        File scandir = monitoredDir.getFile();
        Log.info("ScanDir: " + scandir);
        this.scanner.setScanDirs(Collections.singletonList(scandir));
        this.scanner.setScanInterval(scanInterval);
        this.scanner.setRecursive(recursive);
        this.scanner.setFilenameFilter(filenamefilter);
        this.scanner.addListener(this);
        this.scanner.scan();
        this.scanner.start();
        Log.info("Started scanner: " + scanner);
    }

    @Override
    protected void doStop() throws Exception
    {
        Log.info(this.getClass().getSimpleName() + ".doStop()");
        this.scanner.removeListener(this);
        this.scanner.stop();
    }
}
