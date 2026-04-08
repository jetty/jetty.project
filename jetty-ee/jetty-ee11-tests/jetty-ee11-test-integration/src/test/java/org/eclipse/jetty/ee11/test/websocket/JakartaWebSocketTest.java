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

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.eclipse.jetty.ee11.test.support.XmlBasedJettyServer;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class JakartaWebSocketTest
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

        server.addWebApp("servlet5-demo-jakarta-websocket.war");

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
        URI uri = WSURI.toWebsocket(server.getServerURI().resolve("/servlet5-demo-jakarta-websocket/jakarta.websocket"));

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();

        // to encourage client container to shutdown with server ...
        server.getServer().addBean(container, true);

        JakartaSimpleEchoSocket socket = new JakartaSimpleEchoSocket();
        Session session = container.connectToServer(socket, uri);
        try
        {
            RemoteEndpoint.Basic remote = session.getBasicRemote();
            String msg = "Foo";
            remote.sendText(msg);
            assertTrue(socket.messageLatch.await(5, TimeUnit.SECONDS)); // give remote 1 second to respond
        }
        finally
        {
            session.close();
            assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS)); // give remote 1 second to acknowledge response
        }
    }
}
