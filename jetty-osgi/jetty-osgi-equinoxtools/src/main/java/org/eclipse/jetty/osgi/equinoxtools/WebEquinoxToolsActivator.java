// ========================================================================
// Copyright (c) 2006-2011 Mort Bay Consulting Pty. Ltd.
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
package org.eclipse.jetty.osgi.equinoxtools;

import javax.servlet.ServletException;

import org.eclipse.jetty.osgi.equinoxtools.console.EquinoxChattingSupport;
import org.eclipse.jetty.osgi.equinoxtools.console.EquinoxConsoleContinuationServlet;
import org.eclipse.jetty.osgi.equinoxtools.console.EquinoxConsoleSyncServlet;
import org.eclipse.jetty.osgi.equinoxtools.console.EquinoxConsoleWebSocketServlet;
import org.eclipse.jetty.osgi.equinoxtools.console.WebConsoleSession;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.osgi.framework.console.ConsoleSession;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

/**
 * When started will register on the HttpService 3 servlets for 3 different styles of equinox consoles.
 */
public class WebEquinoxToolsActivator implements BundleActivator
{

    private static BundleContext context;
    public static BundleContext getContext()
    {
        return context;
    }

    private HttpService _httpService;
    private ServiceTracker _tracker;
    private EquinoxChattingSupport _equinoxChattingSupport;
    

    /*
     * (non-Javadoc)
     * 
     * @see org.osgi.framework.BundleActivator#start(org.osgi.framework.BundleContext)
     */
    public void start(BundleContext bundleContext) throws Exception
    {
        WebEquinoxToolsActivator.context = bundleContext;
                
        ServiceTrackerCustomizer httpServiceTrackerCustomizer = new ServiceTrackerCustomizer()
        {
            public void removedService(ServiceReference reference, Object service)
            {
                _httpService = null;
            }

            public void modifiedService(ServiceReference reference, Object service)
            {
                _httpService = (HttpService)context.getService(reference);
            }

            public Object addingService(ServiceReference reference)
            {
                _httpService = (HttpService)context.getService(reference);
                try
                {
                    //TODO; some effort to use the same console session on the 2 async console servlets?

                    //websocket:
//                    WebConsoleSession wsSession = new WebConsoleSession();
//                    WebEquinoxConsoleActivator.context.registerService(ConsoleSession.class.getName(), wsSession, null);
//                    EquinoxChattingSupport wsEquinoxChattingSupport = new EquinoxChattingSupport(wsSession);
                    _httpService.registerResources("/equinoxconsole/ws/index.html","/equinoxconsole/ws/index.html",null);
                    _httpService.registerServlet("/equinoxconsole/ws",new EquinoxConsoleWebSocketServlet(/*wsSession, wsEquinoxChattingSupport*/),null,null);
                    
                    //continuations:
//                    WebConsoleSession contSession = new WebConsoleSession();
//                    WebEquinoxConsoleActivator.context.registerService(ConsoleSession.class.getName(), contSession, null);
//                    EquinoxChattingSupport contEquinoxChattingSupport = new EquinoxChattingSupport(contSession);
                    _httpService.registerResources("/equinoxconsole/index.html","/equinoxconsole/index.html",null);
                    _httpService.registerServlet("/equinoxconsole",new EquinoxConsoleContinuationServlet(/*contSession, contEquinoxChattingSupport*/),null,null);
                    
                    //legacy synchroneous; keep it in a separate console session.
                    WebConsoleSession syncSession = new WebConsoleSession();
                    WebEquinoxToolsActivator.context.registerService(ConsoleSession.class.getName(), syncSession, null);
                    _httpService.registerServlet("/equinoxconsole/sync",new EquinoxConsoleSyncServlet(syncSession),null,null);
                }
                catch (ServletException e)
                {
                    Log.warn(e);
                }
                catch (NamespaceException e)
                {
                    Log.warn(e);
                }
                return _httpService;
            }
        };

        _tracker = new ServiceTracker(context,HttpService.class.getName(),httpServiceTrackerCustomizer);
        _tracker.open();
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
     */
    public void stop(BundleContext bundleContext) throws Exception
    {
        _tracker.close();
        WebEquinoxToolsActivator.context = null;
    }
    

}
