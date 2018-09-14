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

package org.eclipse.jetty.websocket.tests.server;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.tests.WSServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class WebSocketUpgradeFilterWebappTest
{
    private static AtomicInteger uniqTestDirId = new AtomicInteger(0);

    private static File getNewTestDir()
    {
        File testDir = MavenTestingUtils.getTargetTestingDir("tests/WSUF-webxml-" + uniqTestDirId.getAndIncrement());
        FS.ensureDirExists(testDir);
        return testDir;
    }

    interface ServerConfiguration
    {
        void configure(WSServer server) throws Exception;
    }

    public static Stream<Arguments> scenarios()
    {
        List<Arguments> cases = new ArrayList<>();

        // WSUF from web.xml, SCI active, apply app-ws configuration via Servlet.init

        cases.add(Arguments.of(
                "wsuf/WebAppContext/web.xml/Servlet.init",
                (ServerConfiguration) (server) ->
                {
                    server.copyWebInf("wsuf-config-via-servlet-init.xml");
                    server.copyClass(InfoSocket.class);
                    server.copyClass(InfoServlet.class);
                    server.start();

                    WebAppContext webapp = server.createWebAppContext();
                    server.deployWebapp(webapp);
                }
        ));

        // xml based, wsuf, on alternate url-pattern and config attribute location

        cases.add(Arguments.of(
                "wsuf/WebAppContext/web.xml/ServletContextListener/alt-config",
                (ServerConfiguration) (server) ->
                {
                    server.copyWebInf("wsuf-alt-config-via-listener.xml");
                    server.copyClass(InfoSocket.class);
                    server.copyClass(InfoContextAltAttributeListener.class);
                    server.start();

                    WebAppContext webapp = server.createWebAppContext();
                    server.deployWebapp(webapp);
                }
        ));
        
        // WSUF from web.xml, SCI active, apply app-ws configuration via ServletContextListener
        
        cases.add(Arguments.of(
                "From ServletContextListener",
                (ServerConfiguration) (server) ->
                {
                    server.copyWebInf("wsuf-config-via-listener.xml");
                    server.copyClass(InfoSocket.class);
                    server.copyClass(InfoContextAttributeListener.class);
                    server.start();

                    WebAppContext webapp = server.createWebAppContext();
                    server.deployWebapp(webapp);
                }
        ));
        
        // WSUF from web.xml, SCI active, apply app-ws configuration via ServletContextListener with WEB-INF/lib/jetty-http.jar
        
        cases.add(Arguments.of(
                "From ServletContextListener with jar scanning",
                (ServerConfiguration) (server) ->
                {
                    server.copyWebInf("wsuf-config-via-listener.xml");
                    server.copyClass(InfoSocket.class);
                    server.copyClass(InfoContextAttributeListener.class);
                    // Add a jetty-http.jar to ensure that the classloader constraints
                    // and the WebAppClassloader setup is sane and correct
                    // The odd version string is present to capture bad regex behavior in Jetty
                    server.copyLib(PathSpec.class, "jetty-http-9.99.999.jar");
                    server.start();

                    WebAppContext webapp = server.createWebAppContext();
                    server.deployWebapp(webapp);
                }
        ));

        return cases.stream();
    }
    
    private void startServer(ServerConfiguration serverConfiguration) throws Exception
    {
        server = new WSServer(getNewTestDir(), "app");
        serverConfiguration.configure(server);
    }

    private WSServer server;

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @SuppressWarnings("Duplicates")
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("scenarios")
    public void testNormalConfiguration(String testId, ServerConfiguration serverConfiguration) throws Exception
    {
        startServer(serverConfiguration);

        try (LocalFuzzer session = server.newLocalFuzzer("/app/info/"))
        {
            session.sendFrames(
                    new TextFrame().setPayload("hello"),
                    new CloseInfo(StatusCode.NORMAL).asFrame()
            );

            BlockingQueue<WebSocketFrame> framesQueue = session.getOutputFrames();
            WebSocketFrame frame = framesQueue.poll(1, TimeUnit.SECONDS);

            assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.TEXT));

            // If we can connect and send a text message, we know that the endpoint was
            // added properly, and the response will help us verify the policy configuration too
            assertThat("Frame.text-payload", frame.getPayloadAsUTF8(), containsString("session.maxTextMessageSize=" + (10 * 1024 * 1024)));
        }
    }

    @SuppressWarnings("Duplicates")
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("scenarios")
    public void testStopStartOfHandler(String testId, ServerConfiguration serverConfiguration) throws Exception
    {
        startServer(serverConfiguration);

        try (LocalFuzzer session = server.newLocalFuzzer("/app/info/"))
        {
            session.sendFrames(
                    new TextFrame().setPayload("hello 1"),
                    new CloseInfo(StatusCode.NORMAL).asFrame()
            );

            BlockingQueue<WebSocketFrame> framesQueue = session.getOutputFrames();
            WebSocketFrame frame = framesQueue.poll(1, TimeUnit.SECONDS);

            assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.TEXT));

            // If we can connect and send a text message, we know that the endpoint was
            // added properly, and the response will help us verify the policy configuration too
            assertThat("Frame.text-payload", frame.getPayloadAsUTF8(), containsString("session.maxTextMessageSize=" + (10 * 1024 * 1024)));
        }

        server.getContexts().stop();
        server.getContexts().start();

        // Make request again (server should have retained websocket configuration)

        try (LocalFuzzer session = server.newLocalFuzzer("/app/info/"))
        {
            session.sendFrames(
                    new TextFrame().setPayload("hello 2"),
                    new CloseInfo(StatusCode.NORMAL).asFrame()
            );

            BlockingQueue<WebSocketFrame> framesQueue = session.getOutputFrames();
            WebSocketFrame frame = framesQueue.poll(1, TimeUnit.SECONDS);

            assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.TEXT));

            // If we can connect and send a text message, we know that the endpoint was
            // added properly, and the response will help us verify the policy configuration too
            assertThat("Frame.text-payload", frame.getPayloadAsUTF8(), containsString("session.maxTextMessageSize=" + (10 * 1024 * 1024)));
        }
    }
}
