// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.File;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

public class SslContentExchangeTest
	extends ContentExchangeTest
{

    protected void configureServer(Server server)
        throws Exception
    {
        setProtocol("https");
        
        SslSelectChannelConnector connector = new SslSelectChannelConnector();
        String keystore = new File("src/test/resources/keystore").getAbsolutePath();   
        connector.setKeystore(keystore);
        connector.setPassword("storepwd");
        connector.setKeyPassword("keypwd");
        server.addConnector(connector);
                
        Handler handler = new TestHandler(getBasePath());

        ServletContextHandler root = new ServletContextHandler();
        root.setContextPath("/");
        root.setResourceBase(getBasePath());
        ServletHolder servletHolder = new ServletHolder( new DefaultServlet() );
        servletHolder.setInitParameter( "gzip", "true" );
        root.addServlet( servletHolder, "/*" );    

        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[]{handler, root});
        server.setHandler( handlers ); 
    }
}
