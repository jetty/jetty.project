//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests.core;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.tests.AutobahnServer;

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
public class CoreAutobahnServer implements AutobahnServer
{
    public static void main(String[] args) throws Exception
    {
        int port = 9001; // same port as found in fuzzing-client.json
        if (args != null && args.length > 0)
            port = Integer.parseInt(args[0]);

        CoreAutobahnServer server = new CoreAutobahnServer();
        server.startAutobahnServer(port);
        server.join();
    }

    private Server _server;

    public void startAutobahnServer(int port) throws Exception
    {
        _server = new Server();
        ServerConnector connector = new ServerConnector(_server);
        connector.setPort(port);
        _server.addConnector(connector);

        WebSocketComponents components = new WebSocketComponents();
        WebSocketUpgradeHandler handler = new WebSocketUpgradeHandler(components);
        handler.addMapping("/*", new TestWebSocketNegotiator(new AutobahnFrameHandler()));
        _server.setHandler(handler);

        _server.start();
    }

    @Override
    public void stopAutobahnServer() throws Exception
    {
        _server.stop();
    }

    public void join() throws InterruptedException
    {
        _server.join();
    }
}
