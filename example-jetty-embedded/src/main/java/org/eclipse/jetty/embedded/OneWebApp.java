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

package org.eclipse.jetty.embedded;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.webapp.WebAppContext;

public class OneWebApp
{
    public static void main(String[] args) throws Exception
    {
        Server server = new Server();

        Connector connector = new SelectChannelConnector();
        connector.setPort(Integer.getInteger("jetty.port",8080).intValue());
        server.setConnectors(new Connector[]
        { connector });


        //If you're running this from inside Eclipse, then Server.getVersion will not provide
        //the correct number as there is no manifest. Use the command line instead to provide the path to the
        //test webapp
        String war = args.length > 0?args[0]: "../test-jetty-webapp/target/test-jetty-webapp-"+Server.getVersion();
        String path = args.length > 1?args[1]:"/";

        System.err.println(war + " " + path);

        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath(path);
        webapp.setWar(war);
        
        //If the webapp contains security constraints, you will need to configure a LoginService
        if (war.contains("test-jetty-webapp"))
        {
            org.eclipse.jetty.security.HashLoginService loginService = new org.eclipse.jetty.security.HashLoginService();
            loginService.setName("Test Realm");
            loginService.setConfig("src/test/resources/realm.properties");
            webapp.getSecurityHandler().setLoginService(loginService);
        }

        server.setHandler(webapp);

        server.start();
        server.join();
    }
}
