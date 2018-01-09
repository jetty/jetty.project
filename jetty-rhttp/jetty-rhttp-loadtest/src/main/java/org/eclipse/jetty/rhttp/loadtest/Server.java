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

package org.eclipse.jetty.rhttp.loadtest;

import org.eclipse.jetty.rhttp.gateway.GatewayServer;
import org.eclipse.jetty.rhttp.gateway.StandardTargetIdRetriever;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.nio.SelectChannelConnector;

/**
 * @version $Revision$ $Date$
 */
public class Server
{
    public static void main(String[] args) throws Exception
    {
        int port = 8080;
        if (args.length > 0)
            port = Integer.parseInt(args[0]);

        GatewayServer server = new GatewayServer();
        Connector connector = new SelectChannelConnector();
        connector.setLowResourceMaxIdleTime(connector.getMaxIdleTime());
        connector.setPort(port);
        server.addConnector(connector);
        server.setTargetIdRetriever(new StandardTargetIdRetriever());
        server.start();
        server.getServer().dumpStdErr();
        Runtime.getRuntime().addShutdownHook(new Shutdown(server));
    }

    private static class Shutdown extends Thread
    {
        private final GatewayServer server;

        public Shutdown(GatewayServer server)
        {
            this.server = server;
        }

        @Override
        public void run()
        {
            try
            {
                server.stop();
            }
            catch (Exception ignored)
            {
            }
        }
    }
}
