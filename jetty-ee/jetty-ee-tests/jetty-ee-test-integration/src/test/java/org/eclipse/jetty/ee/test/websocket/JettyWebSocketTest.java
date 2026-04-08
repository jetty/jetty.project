//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee11.test.websocket;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee11.test.support.XmlBasedJettyServer;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class JettyWebSocketTest
{
    public WorkDir workDir;
    private XmlBasedJettyServer server;

    @BeforeEach
    public void setUpServer() throws Exception
    {
        server = new XmlBasedJettyServer(workDir);
        server.addXmlConfiguration("basic-server.xml");
        server.addXmlConfiguration("login-service.xml");
        server.addTargetFileAsXmlConfiguration("configs/etc/jetty-deployer-standard.xml");
        server.addTargetFileAsXmlConfiguration("configs/etc/jetty-deployment-scanner.xml");
        server.addTargetFileAsXmlConfiguration("configs/etc/jetty-ee11-deploy.xml");
        server.addXmlConfiguration("NIOHttp.xml");

        server.addWebApp("jetty-ee11-demo-jetty-websocket.war");

        server.load();
        server.start();
    }

    @AfterEach
    public void tearDownServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testChatEndpoint() throws Exception
    {
        URI uri = WSURI.toWebsocket(server.getServerURI().resolve("/jetty-ee11-demo-jetty-websocket/jetty.websocket/foo"));

        WebSocketClient client = new WebSocketClient();

        try
        {
            JettySimpleEchoSocket socket = new JettySimpleEchoSocket();

            client.start();

            ClientUpgradeRequest request = new ClientUpgradeRequest(uri);
            request.setSubProtocols("chat");
            client.connect(socket, request);
            // wait for closed socket connection.
            assertTrue(socket.awaitClose(5, TimeUnit.SECONDS));
        }
        finally
        {
            client.stop();
        }
    }
}
