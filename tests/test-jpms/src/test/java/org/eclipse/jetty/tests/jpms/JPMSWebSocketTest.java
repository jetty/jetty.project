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

package org.eclipse.jetty.tests.jpms;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.tests.testers.JPMSTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class JPMSWebSocketTest
{
    @Test
    public void testJPMSWebSocket(WorkDir workDir) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        String slf4jVersion = System.getProperty("slf4jVersion");

        int port = Tester.freePort();
        try (JPMSTester server = new JPMSTester.Builder(workDir.getPath())
//            .jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005")
            .classesDirectory(MavenPaths.targetDir().resolve("test-classes"))
            .moduleInfo("""
                module app.server
                {
                  requires org.eclipse.jetty.websocket.server;
                  exports org.eclipse.jetty.tests.jpms;
                }
                """)
            .addToModulePath("org.eclipse.jetty.websocket:jetty-websocket-jetty-server:" + jettyVersion)
            .addToModulePath("org.eclipse.jetty.websocket:jetty-websocket-jetty-api:" + jettyVersion)
            .addToModulePath("org.eclipse.jetty:jetty-server:" + jettyVersion)
            .addToModulePath("org.eclipse.jetty:jetty-http:" + jettyVersion)
            .addToModulePath("org.eclipse.jetty:jetty-io:" + jettyVersion)
            .addToModulePath("org.eclipse.jetty:jetty-util:" + jettyVersion)
            .addToModulePath("org.slf4j:slf4j-api:" + slf4jVersion)
            .addToModulePath("org.eclipse.jetty:jetty-slf4j-impl:" + jettyVersion)
            .addToModulePath("org.eclipse.jetty.websocket:jetty-websocket-jetty-common:" + jettyVersion)
            .addToModulePath("org.eclipse.jetty.websocket:jetty-websocket-core-server:" + jettyVersion)
            .addToModulePath("org.eclipse.jetty.websocket:jetty-websocket-core-common:" + jettyVersion)
            .mainClass(ServerMain.class)
            .args(String.valueOf(port))
            .build())
        {
            assertTrue(server.awaitConsoleLogsFor("Started oejs.Server@", Duration.ofSeconds(10)));

            try (JPMSTester client = new JPMSTester.Builder(workDir.getPath())
                .classesDirectory(MavenPaths.targetDir().resolve("test-classes"))
                .moduleInfo("""
                    module app.client
                    {
                      requires org.eclipse.jetty.websocket.client;
                      exports org.eclipse.jetty.tests.jpms;
                    }
                    """)
                .addToModulePath("org.eclipse.jetty.websocket:jetty-websocket-jetty-client:" + jettyVersion)
                .addToModulePath("org.eclipse.jetty.websocket:jetty-websocket-jetty-api:" + jettyVersion)
                .addToModulePath("org.eclipse.jetty:jetty-client:" + jettyVersion)
                .addToModulePath("org.eclipse.jetty:jetty-alpn-client:" + jettyVersion)
                .addToModulePath("org.eclipse.jetty:jetty-http:" + jettyVersion)
                .addToModulePath("org.eclipse.jetty:jetty-io:" + jettyVersion)
                .addToModulePath("org.eclipse.jetty:jetty-util:" + jettyVersion)
                .addToModulePath("org.slf4j:slf4j-api:" + slf4jVersion)
                .addToModulePath("org.eclipse.jetty:jetty-slf4j-impl:" + jettyVersion)
                .addToModulePath("org.eclipse.jetty.compression:jetty-compression-common:" + jettyVersion)
                .addToModulePath("org.eclipse.jetty.compression:jetty-compression-gzip:" + jettyVersion)
                .addToModulePath("org.eclipse.jetty.websocket:jetty-websocket-jetty-common:" + jettyVersion)
                .addToModulePath("org.eclipse.jetty.websocket:jetty-websocket-core-client:" + jettyVersion)
                .addToModulePath("org.eclipse.jetty.websocket:jetty-websocket-core-common:" + jettyVersion)
                .mainClass(ClientMain.class)
                .args(String.valueOf(port))
                .build())
            {
                assertTrue(client.awaitConsoleLogsFor("SUCCESS", Duration.ofSeconds(10)));
            }
        }
    }

    public static class ServerMain
    {
        public static void main(String[] args) throws Exception
        {
            Server server = new Server();
            ServerConnector connector = new ServerConnector(server);
            connector.setPort(Integer.parseInt(args[0]));
            server.addConnector(connector);
            WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, container ->
                container.addMapping("/echo", (request, response, callback) -> new AnnotatedServerEndPoint())
            );
            server.setHandler(wsHandler);
            server.start();
        }
    }

    @WebSocket
    public static class AnnotatedServerEndPoint
    {
        @OnWebSocketMessage
        public void onText(Session session, String text)
        {
            session.sendText(text, Callback.NOOP);
        }
    }

    public static class ClientMain
    {
        public static void main(String[] args) throws Exception
        {
            WebSocketClient client = new WebSocketClient();
            client.start();

            int port = Integer.parseInt(args[0]);
            ListenerClientEndPoint endPoint = new ListenerClientEndPoint();
            Session session = client.connect(endPoint, URI.create("ws://localhost:" + port + "/echo")).get(5, TimeUnit.SECONDS);
            session.sendText("hello", Callback.NOOP);
            endPoint.thenRun(() -> System.err.println("SUCCESS"));
        }
    }

    public static class ListenerClientEndPoint extends CompletableFuture<Void> implements Session.Listener.AutoDemanding
    {
        @Override
        public void onWebSocketText(String message)
        {
            complete(null);
        }
    }
}
