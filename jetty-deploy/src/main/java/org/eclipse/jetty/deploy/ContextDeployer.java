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

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * Context Deployer
 * 
 * This deployer scans a designated directory by
 * {@link #setConfigurationDir(String)} for the appearance/disappearance or
 * changes to xml configuration files. The scan is performed at startup and at
 * an optional hot deployment frequency specified by
 * {@link #setScanInterval(int)}. By default, the scanning is NOT recursive,
 * but can be made so by {@link #setRecursive(boolean)}.
 * 
 * Each configuration file is in {@link XmlConfiguration} format and represents
 * the configuration of a instance of {@link ContextHandler} (or a subclass
 * specified by the XML <code>Configure</code> element).
 * 
 * The xml should configure the context and the instance is deployed to the
 * {@link ContextHandlerCollection} specified by {@link #setContexts(Server)}.
 * 
 * Similarly, when one of these existing files is removed, the corresponding
 * context is undeployed; when one of these files is changed, the corresponding
 * context is undeployed, the (changed) xml config file reapplied to it, and
 * then (re)deployed.
 * 
 * Note that the context itself is NOT copied into the hot deploy directory. The
 * webapp directory or war file can exist anywhere. It is the xml config file
 * that points to it's location and deploys it from there.
 * 
 * It means, for example, that you can keep a "read-only" copy of your webapp
 * somewhere, and apply different configurations to it simply by dropping
 * different xml configuration files into the configuration directory.
 * 
 * @org.apache.xbean.XBean element="hotDeployer" description="Creates a hot deployer 
 * 						to watch a directory for changes at a configurable interval."
 * 
 */
public class ContextDeployer extends AbstractLifeCycle
{
    private int _scanInterval=10;
    private Scanner _scanner;
    private ScannerListener _scannerListener;
    private Resource _contextsDir;
    private Map _currentDeployments=new HashMap();
    private ContextHandlerCollection _contexts;
    private ConfigurationManager _configMgr;
    private boolean _recursive = false;
    private AttributesMap _contextAttributes = new AttributesMap();
    
    /* ------------------------------------------------------------ */
    protected class ScannerListener implements Scanner.DiscreteListener
    {
        /**
         * Handle a new deployment
         * 
         * @see org.eclipse.jetty.util.Scanner.FileAddedListener#fileAdded(java.lang.String)
         */
        public void fileAdded(String filename) throws Exception
        {
            deploy(filename);
        }

        /**
         * Handle a change to an existing deployment. Undeploy then redeploy.
         * 
         * @see org.eclipse.jetty.util.Scanner.FileChangedListener#fileChanged(java.lang.String)
         */
        public void fileChanged(String filename) throws Exception
        {
            redeploy(filename);
        }

        /**
         * Handle an undeploy.
         * 
         * @see org.eclipse.jetty.util.Scanner.FileRemovedListener#fileRemoved(java.lang.String)
         */
        public void fileRemoved(String filename) throws Exception
        {
            undeploy(filename);
        }
        @Override
        public String toString()
        {
            return "ContextDeployer$Scanner";
        }
    }

    /**
     * Constructor
     * 
     * @throws Exception
     */
    public ContextDeployer() 
    {
        _scanner=new Scanner();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the ContextHandlerColletion to which to deploy the contexts
     */
    public ContextHandlerCollection getContexts()
    {
        return _contexts;
    }

    /* ------------------------------------------------------------ */
    /**
     * Associate with a {@link ContextHandlerCollection}.
     * 
     * @param contexts
     *            the ContextHandlerColletion to which to deploy the contexts
     */
    public void setContexts(ContextHandlerCollection contexts)
    {
        if (isStarted()||isStarting())
            throw new IllegalStateException("Cannot set Contexts after deployer start");
        _contexts=contexts;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param seconds
     *            The period in second between scans for changed configuration
     *            files. A zero or negative interval disables hot deployment
     */
    public void setScanInterval(int seconds)
    {
        if (isStarted()||isStarting())
            throw new IllegalStateException("Cannot change scan interval after deployer start");
        _scanInterval=seconds;
    }

    /* ------------------------------------------------------------ */
    public int getScanInterval()
    {
        return _scanInterval;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param dir Directory to scan for context descriptors
     */
    public void setContextsDir(String dir)
    {
        try
        {
            _contextsDir=Resource.newResource(dir);
        }
        catch(Exception e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    /* ------------------------------------------------------------ */
    public String getContextsDir()
    {
        return _contextsDir==null?null:_contextsDir.toString();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param dir
     * @throws Exception
     * @deprecated use {@link #setContextsDir(String)}
     */
    @Deprecated
    public void setConfigurationDir(String dir) throws Exception
    {
        setConfigurationDir(Resource.newResource(dir));
    }

    /* ------------------------------------------------------------ */
    /**
     * @param file
     * @throws Exception
     * @deprecated use {@link #setContextsDir(String)}
     */
    @Deprecated
    public void setConfigurationDir(File file) throws Exception
    {
        setConfigurationDir(Resource.newResource(file.toURL()));
    }

    /* ------------------------------------------------------------ */
    /**
     * @param resource
     * @deprecated use {@link #setContextsDir(String)}
     */
    @Deprecated
    public void setConfigurationDir(Resource resource)
    {
        if (isStarted()||isStarting())
            throw new IllegalStateException("Cannot change hot deploy dir after deployer start");
        _contextsDir=resource;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param directory
     * @deprecated use {@link #setContextsDir(String)}
     */
    @Deprecated
    public void setDirectory(String directory) throws Exception
    {
        setConfigurationDir(directory);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return
     * @deprecated use {@link #setContextsDir(String)}
     */
    @Deprecated
    public String getDirectory()
    {
        return getConfigurationDir().getName();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return
     * @deprecated use {@link #setContextsDir(String)}
     */
    @Deprecated
    public Resource getConfigurationDir()
    {
        return _contextsDir;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param configMgr
     */
    public void setConfigurationManager(ConfigurationManager configMgr)
    {
        _configMgr=configMgr;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    public ConfigurationManager getConfigurationManager()
    {
        return _configMgr;
    }


    /* ------------------------------------------------------------ */
    public void setRecursive (boolean recursive)
    {
        _recursive=recursive;
    }

    /* ------------------------------------------------------------ */
    public boolean getRecursive ()
    {
        return _recursive;
    }

    /* ------------------------------------------------------------ */
    public boolean isRecursive()
    {
        return _recursive;
    }
    

    /* ------------------------------------------------------------ */
    /**
     * Set a contextAttribute that will be set for every Context deployed by this deployer.
     * @param name
     * @param value
     */
    public void setAttribute (String name, Object value)
    {
        _contextAttributes.setAttribute(name,value);
    }
    

    /* ------------------------------------------------------------ */
    /**
     * Get a contextAttribute that will be set for every Context deployed by this deployer.
     * @param name
     * @return
     */
    public Object getAttribute (String name)
    {
        return _contextAttributes.getAttribute(name);
    }
    

    /* ------------------------------------------------------------ */
    /**
     * Remove a contextAttribute that will be set for every Context deployed by this deployer.
     * @param name
     */
    public void removeAttribute(String name)
    {
        _contextAttributes.removeAttribute(name);
    }

    /* ------------------------------------------------------------ */
    private void deploy(String filename) throws Exception
    {
        ContextHandler context=createContext(filename);
        Log.info("Deploy "+filename+" -> "+ context);
        _contexts.addHandler(context);
        _currentDeployments.put(filename,context);
        if (_contexts.isStarted())
            context.start();
    }

    /* ------------------------------------------------------------ */
    private void undeploy(String filename) throws Exception
    {
        ContextHandler context=(ContextHandler)_currentDeployments.get(filename);
        Log.info("Undeploy "+filename+" -> "+context);
        if (context==null)
            return;
        context.stop();
        _contexts.removeHandler(context);
        _currentDeployments.remove(filename);
    }

    /* ------------------------------------------------------------ */
    private void redeploy(String filename) throws Exception
    {
        undeploy(filename);
        deploy(filename);
    }

    /* ------------------------------------------------------------ */
    /**
     * Start the hot deployer looking for webapps to deploy/undeploy
     * 
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        if (_contextsDir==null)
            throw new IllegalStateException("No configuration dir specified");

        if (_contexts==null)
            throw new IllegalStateException("No context handler collection specified for deployer");

        _scanner.setScanDir(_contextsDir.getFile());
        _scanner.setScanInterval(getScanInterval());
        _scanner.setRecursive(_recursive); //only look in the top level for deployment files?
        // Accept changes only in files that could be a deployment descriptor
        _scanner.setFilenameFilter(new FilenameFilter()
        {
            public boolean accept(File dir, String name)
            {
                try
                {
                    if (name.endsWith(".xml")&&dir.equals(getConfigurationDir().getFile()))
                        return true;
                    return false;
                }
                catch (IOException e)
                {
                    Log.warn(e);
                    return false;
                }
            }
        });
        _scannerListener=new ScannerListener();
        _scanner.addListener(_scannerListener);
        _scanner.scan();
        _scanner.start();
        _contexts.getServer().getContainer().addBean(_scanner);
    }

    /* ------------------------------------------------------------ */
    /**
     * Stop the hot deployer.
     * 
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        _scanner.removeListener(_scannerListener);
        _scanner.stop();
    }

    /* ------------------------------------------------------------ */
    /**
     * Create a WebAppContext for the webapp being hot deployed, then apply the
     * xml config file to it to configure it.
     * 
     * @param filename
     *            the config file found in the hot deploy directory
     * @return
     * @throws Exception
     */
    private ContextHandler createContext(String filename) throws Exception
    {
        // The config file can call any method on WebAppContext to configure
        // the webapp being deployed.
        Resource resource = Resource.newResource(filename);
        if (!resource.exists())
            return null;

        XmlConfiguration xmlConfiguration=new XmlConfiguration(resource.getURL());
        HashMap properties = new HashMap();
        properties.put("Server", _contexts.getServer());
        if (_configMgr!=null)
            properties.putAll(_configMgr.getProperties());
           
        xmlConfiguration.setProperties(properties);
        ContextHandler context=(ContextHandler)xmlConfiguration.configure();
        context.setAttributes(new AttributesMap(_contextAttributes));
        return context;
    }

}
