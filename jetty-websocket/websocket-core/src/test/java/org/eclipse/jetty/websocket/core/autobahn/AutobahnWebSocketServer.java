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

package org.eclipse.jetty.websocket.core.autobahn;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.RFC6455Handshaker;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;

/**
 * WebSocket Server for use with <a href="https://github.com/crossbario/autobahn-testsuite">autobahn websocket testsuite</a> (wstest).
 * <p>
 *     Installing Autobahn:
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
 *     Upgrading an existing installation of autobahntestsuite
 * </p>
 * <pre>
 *     $ sudo pip install -U autobahntestsuite
 * </pre>
 * <p>
 *     Running Autobahn Fuzzing Client (against this server implementation):
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
        Server server = new Server();

        ServerConnector connector = new ServerConnector(
                server,
                new HttpConnectionFactory()
        );
        connector.addBean(new WebSocketPolicy(WebSocketBehavior.SERVER));
        connector.addBean(new RFC6455Handshaker());

        connector.setPort(9001); // same port as found in fuzzing-client.json
        connector.setIdleTimeout(10000);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");
        server.setHandler(context);
        WebSocketNegotiator negotiator =  
                new AutobahnWebSocketNegotiator(new DecoratedObjectFactory(), new WebSocketExtensionRegistry(), connector.getByteBufferPool());

        WebSocketUpgradeHandler handler = new WebSocketUpgradeHandler(negotiator);
        context.setHandler(handler);
        handler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().println("Hello World!");
                baseRequest.setHandled(true);
            }
        });

        server.start();
        server.join();
    }
}
