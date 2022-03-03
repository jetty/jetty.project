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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.autobahn;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

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
 *     # Change to websocket-jakarta-tests directory first.
 *     $ cd jetty-websocket/websocket-jakarta-tests/
 *     $ wstest --mode=fuzzingclient --spec=fuzzingclient.json
 *
 *     # Report output is configured (in the fuzzingclient.json) at location:
 *     $ ls target/reports/servers/
 * </pre>
 */
public class JakartaAutobahnServer
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
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        JakartaWebSocketServletContainerInitializer.configure(context, (servletContext, container) ->
            container.addEndpoint(JakartaAutobahnSocket.class));

        server.start();
        server.join();
    }
}
