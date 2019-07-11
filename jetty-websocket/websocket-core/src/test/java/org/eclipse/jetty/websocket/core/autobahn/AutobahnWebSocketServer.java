//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.autobahn;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;

/**
 * WebSocket Server for use with <a href="https://github.com/crossbario/autobahn-testsuite">autobahn websocket testsuite</a> (wstest).
 * <p>
 * Installing Autobahn:
 * </p>
 * <pre>
 *    # For Debian / Ubuntu
 *    $ sudo apt-get install python python-dev python-twisted
 *    $ sudo apt-get install python-pip
 *    $ sudo pip install autobahntestsuite
 *
 *    # For Fedora / Redhat
 *    $ sudo yum install python python-dev python-pip twisted
 *    $ sudo yum install libffi-devel
 *    $ sudo pip install autobahntestsuite
 * </pre>
 * <p>
 * Upgrading an existing installation of autobahntestsuite
 * </p>
 * <pre>
 *     $ sudo pip install -U autobahntestsuite
 * </pre>
 * <p>
 * Running Autobahn Fuzzing Client (against this server implementation):
 * </p>
 * <pre>
 *     # Change to websocket-core first
 *     $ cd jetty-websocket/websocket-core
 *     $ wstest --mode=fuzzingclient --spec=fuzzingclient.json
 *
 *     # Report output is configured (in the fuzzingclient.json) at location:
 *     $ ls target/reports/servers/
 * </pre>
 */
public class AutobahnWebSocketServer
{
    public static void main(String[] args) throws Exception
    {
        int port = 9001; // same port as found in fuzzing-client.json
        if (args != null && args.length > 0)
            port = Integer.parseInt(args[0]);

        Server server = new Server(port);
        ServerConnector connector = new ServerConnector(server);
        connector.setIdleTimeout(10000);
        server.addConnector(connector);
        ContextHandler context = new ContextHandler("/");
        server.setHandler(context);

        WebSocketUpgradeHandler handler = new WebSocketUpgradeHandler((neg) -> new AutobahnFrameHandler());
        context.setHandler(handler);

        server.start();
        server.join();
    }
}
