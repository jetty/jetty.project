// ========================================================================
// Copyright (c) 2009 Intalio, Inc.
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
package org.eclipse.jetty.osgi.boot.internal.webapp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jetty.osgi.boot.BundleWebAppProvider;
import org.eclipse.jetty.osgi.boot.JettyBootstrapActivator;
import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.osgi.boot.OSGiWebappConstants;
import org.eclipse.jetty.osgi.boot.ServiceProvider;
import org.eclipse.jetty.osgi.boot.internal.serverfactory.DefaultJettyAtJettyHomeHelper;
import org.eclipse.jetty.osgi.boot.internal.serverfactory.IManagedJettyServerRegistry;
import org.eclipse.jetty.osgi.boot.internal.serverfactory.ServerInstanceWrapper;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.webapp.WebAppContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;

/**
 * When a {@link ContextHandler} service is activated we look into it and if the
 * corresponding webapp is actually not configured then we go and register it.
 * <p>
 * The idea is to always go through this class when we deploy a new webapp on
 * jetty.
 * </p>
 * <p>
 * We are exposing each web-application as an OSGi service. This lets us update
 * the webapps and stop/start them directly at the OSGi layer. It also give us
 * many ways to declare those services: Declarative Services for example. <br/>
 * It is a bit different from the way the HttpService works where we would have
 * a WebappService and we woud register a webapp onto it. <br/>
 * It does not go against RFC-66 nor does it prevent us from supporting the
 * WebappContainer semantics.
 * </p>
 */
public class JettyContextHandlerServiceTracker implements ServiceListener
{
    private static Logger __logger = Log.getLogger(JettyContextHandlerServiceTracker.class.getName());
    
    public static final String FILTER = "(objectclass=" + ServiceProvider.class.getName() + ")";
    

    /**
     * The index is the bundle-symbolic-name/path/to/context/file when there is
     * such thing
     */
    private Map<String, ServiceReference> _indexByContextFile = new HashMap<String, ServiceReference>();

    /** in charge of detecting changes in the osgi contexts home folder. */
    private Scanner _scanner;

    
    //track all instances of deployers of webapps as bundles       
    ServiceTracker _serviceTracker;
        
    /**
     * @param registry
     */
    public JettyContextHandlerServiceTracker() throws Exception
    {
        //track all instances of deployers of webapps
        Bundle myBundle = FrameworkUtil.getBundle(this.getClass());
        _serviceTracker = new ServiceTracker(myBundle.getBundleContext(), FrameworkUtil.createFilter(FILTER),null);
        _serviceTracker.open();
    }

    public void stop() throws Exception
    {
        if (_scanner != null)
        {
            _scanner.stop();
        }
        // the class that created the server is also in charge of stopping it.
        // nothing to stop in the WebappRegistrationHelper

    }

    /**
     * @param contextHome Parent folder where the context files can override the
     *            context files defined in the web bundles: equivalent to the
     *            contexts folder in a traditional jetty installation. when
     *            null, just do nothing.
     */
    protected void setupContextHomeScanner(File contextHome) throws IOException
    {
        if (contextHome == null) { return; }
        final String osgiContextHomeFolderCanonicalPath = contextHome.getCanonicalPath();
        _scanner = new Scanner();
        _scanner.setRecursive(true);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.addListener(new Scanner.DiscreteListener()
        {
            public void fileAdded(String filename) throws Exception
            {
                // adding a file does not create a new app,
                // it just reloads it with the new custom file.
                // well, if the file does not define a context handler,
                // then in fact it does remove it.
                reloadJettyContextHandler(filename, osgiContextHomeFolderCanonicalPath);
            }

            public void fileChanged(String filename) throws Exception
            {
                reloadJettyContextHandler(filename, osgiContextHomeFolderCanonicalPath);
            }

            public void fileRemoved(String filename) throws Exception
            {
                // removing a file does not remove the app:
                // it just goes back to the default embedded in the bundle.
                // well, if there was no default then it does remove it.
                reloadJettyContextHandler(filename, osgiContextHomeFolderCanonicalPath);
            }
        });
    }
    
    public Map<ServiceReference, ServiceProvider> getDeployers(String managedServerName)
    {
        if (managedServerName == null)
            managedServerName = OSGiServerConstants.MANAGED_JETTY_SERVER_DEFAULT_NAME;
        
        Map<ServiceReference, ServiceProvider> candidates = new HashMap<ServiceReference, ServiceProvider>();
        
        ServiceReference[] references = _serviceTracker.getServiceReferences();
        if (references != null)
        {
            for (ServiceReference ref:references)
            {
                String name = (String)ref.getProperty(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);
                if (managedServerName.equalsIgnoreCase(name))
                {
                    ServiceProvider candidate = (ServiceProvider)_serviceTracker.getService(ref);
                    if (candidate != null)
                        candidates.put(ref, candidate);
                }
            }
        }
       return candidates;
    }

    /**
     * Receives notification that a service has had a lifecycle change.
     * 
     * @param ev The <code>ServiceEvent</code> object.
     */
    /** 
     * @see org.osgi.framework.ServiceListener#serviceChanged(org.osgi.framework.ServiceEvent)
     */
    public void serviceChanged(ServiceEvent ev)
    {
        ServiceReference sr = ev.getServiceReference();
        switch (ev.getType())
        {
            case ServiceEvent.MODIFIED:
            case ServiceEvent.UNREGISTERING:
            {
                BundleContext context = FrameworkUtil.getBundle(JettyBootstrapActivator.class).getBundleContext();
                ContextHandler contextHandler = (ContextHandler) context.getService(sr);
              
                
                //Get a jetty deployer targetted to the named server instance, or the default one if not named
                String serverName = (String)sr.getProperty(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);    
                Map<ServiceReference, ServiceProvider> candidates = getDeployers(serverName);
                if (candidates != null)
                {
                    boolean removed = false;
                    Iterator<Entry<ServiceReference, ServiceProvider>> itor = candidates.entrySet().iterator();
                    while (!removed && itor.hasNext())
                    {
                        Entry<ServiceReference, ServiceProvider> e = itor.next();
                        removed = e.getValue().serviceRemoved(sr, contextHandler);
                    }
                }
            }
            if (ev.getType() == ServiceEvent.UNREGISTERING)
            {
                break;
            }
            else
            {
                // modified, meaning: we reload it. now that we stopped it;
                // we can register it.
            }
            case ServiceEvent.REGISTERED:
            {
                System.err.println("New Service registered that could be webapp/context");
                Bundle contributor = sr.getBundle();
                BundleContext context = FrameworkUtil.getBundle(JettyBootstrapActivator.class).getBundleContext();
                ContextHandler contextHandler = (ContextHandler) context.getService(sr);
                if (contextHandler.getServer() != null)
                {
                    // is configured elsewhere.
                    System.err.println("Already configured");
                    return;
                }

                System.err.println("Service registered from bundle: "+contributor.getSymbolicName());
                System.err.println("war="+sr.getProperty("war"));
                
                //Get a jetty deployer targetted to the named server instance, or the default one if not named
                String serverName = (String)sr.getProperty(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);    
                Map<ServiceReference, ServiceProvider> candidates = getDeployers(serverName);
                if (candidates != null)
                {
                    System.err.println("Got some candidates");
                    boolean added = false;
                    Iterator<Entry<ServiceReference, ServiceProvider>> itor = candidates.entrySet().iterator();
                    while (!added && itor.hasNext())
                    {
                        Entry<ServiceReference, ServiceProvider> e = itor.next();
                        System.err.println("Trying ServiceProvider "+e.getValue());
                        added = e.getValue().serviceAdded(sr, contextHandler);
                    }
                }
                break;
            }
        }
    }

  


    /**
     * @param sr
     * @return The key for a context file within the osgi contexts home folder.
     */
    private String getSymbolicNameAndContextFileKey(ServiceReference sr)
    {
        String contextFilePath = (String) sr.getProperty(OSGiWebappConstants.SERVICE_PROP_CONTEXT_FILE_PATH);
        if (contextFilePath != null) { return sr.getBundle().getSymbolicName() + "/" + contextFilePath; }
        return null;
    }

    /**
     * Called by the scanner when one of the context files is changed.
     * 
     * @param contextFileFully
     */
    public void reloadJettyContextHandler(String canonicalNameOfFileChanged, String osgiContextHomeFolderCanonicalPath)
    {
        String key = getNormalizedRelativePath(canonicalNameOfFileChanged, osgiContextHomeFolderCanonicalPath);
        if (key == null) { return; }
        ServiceReference sr = _indexByContextFile.get(key);
        if (sr == null)
        {
            // nothing to do?
            return;
        }
        serviceChanged(new ServiceEvent(ServiceEvent.MODIFIED, sr));
    }

    /**
     * @param canFilename
     * @return
     */
    private String getNormalizedRelativePath(String canFilename, String osgiContextHomeFolderCanonicalPath)
    {
        if (!canFilename.startsWith(osgiContextHomeFolderCanonicalPath))
        {
            // why are we here: this does not look like a child of the osgi
            // contexts home.
            // warning?
            return null;
        }
        return canFilename.substring(osgiContextHomeFolderCanonicalPath.length()).replace('\\', '/');
    }
}
