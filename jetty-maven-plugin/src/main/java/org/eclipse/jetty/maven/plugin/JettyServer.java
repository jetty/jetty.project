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

package org.eclipse.jetty.maven.plugin;


import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * JettyServer
 * 
 * Maven jetty plugin version of a wrapper for the Server class.
 * 
 */
public class JettyServer extends org.eclipse.jetty.server.Server
{
    public static final JettyServer __instance = new JettyServer();
    
    private RequestLog requestLog;
    private ContextHandlerCollection contexts;
    
    
    
    /**
     * 
     */
    public JettyServer()
    {
        super();
        setStopAtShutdown(true);
        //make sure Jetty does not use URLConnection caches with the plugin
        Resource.setDefaultUseCaches(false);
    }

   
    public void setRequestLog (RequestLog requestLog)
    {
        this.requestLog = requestLog;
    }

    /**
     * @see org.eclipse.jetty.server.Server#doStart()
     */
    public void doStart() throws Exception
    {
        super.doStart();
    }

 
    /**
     * @see org.eclipse.jetty.server.handler.HandlerCollection#addHandler(org.eclipse.jetty.server.Handler)
     */
    public void addWebApplication(WebAppContext webapp) throws Exception
    {  
        contexts.addHandler (webapp);
    }

    
    /**
     * Set up the handler structure to receive a webapp.
     * Also put in a DefaultHandler so we get a nice page
     * than a 404 if we hit the root and the webapp's
     * context isn't at root.
     * @throws Exception
     */
    public void configureHandlers () throws Exception 
    {
        DefaultHandler defaultHandler = new DefaultHandler();
        RequestLogHandler requestLogHandler = new RequestLogHandler();
        if (this.requestLog != null)
            requestLogHandler.setRequestLog(this.requestLog);
        
        contexts = (ContextHandlerCollection)super.getChildHandlerByClass(ContextHandlerCollection.class);
        if (contexts==null)
        {   
            contexts = new ContextHandlerCollection();
            HandlerCollection handlers = (HandlerCollection)super.getChildHandlerByClass(HandlerCollection.class);
            if (handlers==null)
            {
                handlers = new HandlerCollection();               
                super.setHandler(handlers);                            
                handlers.setHandlers(new Handler[]{contexts, defaultHandler, requestLogHandler});
            }
            else
            {
                handlers.addHandler(contexts);
            }
        }  
    }
}
