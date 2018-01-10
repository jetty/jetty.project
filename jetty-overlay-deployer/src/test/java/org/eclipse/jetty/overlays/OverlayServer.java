//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.overlays;

import java.io.File;

import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.jndi.NamingUtil;
import org.eclipse.jetty.overlays.OverlayedAppProvider;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;

public class OverlayServer
{
    public static void main(String[] args) throws Exception
    {
        // NamingUtil.__log.setDebugEnabled(true);
        String jetty_home = System.getProperty("jetty.home","target/test-classes/home");
        System.setProperty("jetty.home",jetty_home);
        
        Server server = new Server();
        server.setAttribute("org.eclipse.jetty.webapp.configuration",
                new String[]
                {
                    org.eclipse.jetty.webapp.WebInfConfiguration.class.getCanonicalName(),
                    org.eclipse.jetty.webapp.WebXmlConfiguration.class.getCanonicalName(),
                    org.eclipse.jetty.webapp.MetaInfConfiguration.class.getCanonicalName(),
                    org.eclipse.jetty.webapp.FragmentConfiguration.class.getCanonicalName(),
                    org.eclipse.jetty.plus.webapp.EnvConfiguration.class.getCanonicalName(),
                    org.eclipse.jetty.plus.webapp.PlusConfiguration.class.getCanonicalName(),
                    org.eclipse.jetty.webapp.JettyWebXmlConfiguration.class.getCanonicalName(),
                }
        );
        
        // Setup Connectors
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(8080);
        server.addConnector(connector);
        
        HandlerCollection handlers = new HandlerCollection();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        RequestLogHandler requestLogHandler = new RequestLogHandler();
        handlers.setHandlers(new Handler[]
        { contexts, new DefaultHandler(), requestLogHandler });
        
        StatisticsHandler stats = new StatisticsHandler();
        stats.setHandler(handlers);
        
        server.setHandler(stats);

        // Setup deployers
        DeploymentManager deployer = new DeploymentManager();
        deployer.setContexts(contexts);
        server.addBean(deployer);   
        
        OverlayedAppProvider provider = new OverlayedAppProvider();

        provider.setNodeName("nodeA");
        provider.setScanDir(new File(jetty_home + "/overlays"));
        provider.setScanInterval(2);
        
        deployer.addAppProvider(provider);

        server.setStopAtShutdown(true);
        //server.setSendServerVersion(true);
        
        // Uncomment to work with JNDI examples
        // new org.eclipse.jetty.plus.jndi.Transaction(new com.atomikos.icatch.jta.UserTransactionImp());
        


        
        server.start();
        server.join();
    }

}
