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
import static org.hamcrest.Matchers.notNullValue;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.servlet.DispatcherType;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.server.NativeWebSocketConfiguration;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.tests.LocalServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class WebSocketUpgradeFilterEmbeddedTest
{
    private static AtomicInteger uniqTestDirId = new AtomicInteger(0);
    private LocalServer server;

    protected static File getNewTestDir()
    {
        File testDir = MavenTestingUtils.getTargetTestingDir("WSUF-webxml-" + uniqTestDirId.getAndIncrement());
        FS.ensureDirExists(testDir);
        return testDir;
    }

    public static Stream<Arguments> scenarios()
    {
        final WebSocketCreator infoCreator = (req, resp) -> new InfoSocket();

        List<Arguments> cases = new ArrayList<>();

        // Embedded WSUF.configureContext(), directly app-ws configuration

        cases.add(Arguments.of("wsuf.configureContext/Direct configure", (ContextProvider) (server1) ->
        {
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server1.setHandler(context);

            WebSocketUpgradeFilter wsuf = WebSocketUpgradeFilter.configureContext(context);

            // direct configuration via WSUF
            wsuf.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
            wsuf.addMapping("/info/*", infoCreator);

            return context;
        }));

        // Embedded WSUF.configureContext(), apply app-ws configuration via attribute

        cases.add(Arguments.of("wsuf.configureContext/Attribute based configure", (ContextProvider) (server12) ->
        {
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server12.setHandler(context);

            WebSocketUpgradeFilter.configureContext(context);

            // configuration via attribute
            NativeWebSocketConfiguration configuration = (NativeWebSocketConfiguration) context.getServletContext().getAttribute(NativeWebSocketConfiguration.class.getName());
            assertThat("NativeWebSocketConfiguration", configuration, notNullValue());
            configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
            configuration.addMapping("/info/*", infoCreator);

            return context;
        }));

        // Embedded WSUF, added as filter, apply app-ws configuration via attribute

        cases.add(Arguments.of("wsuf/addFilter/Attribute based configure", (ContextProvider) (server13) ->
        {
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server13.setHandler(context);
            context.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

            NativeWebSocketConfiguration configuration = new NativeWebSocketConfiguration(context.getServletContext());
            configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
            configuration.addMapping("/info/*", infoCreator);
            context.setAttribute(NativeWebSocketConfiguration.class.getName(), configuration);

            return context;
        }));

        // Embedded WSUF, added as filter, apply app-ws configuration via wsuf constructor

        cases.add(Arguments.of("wsuf/addFilter/WSUF Constructor configure", (ContextProvider) (server) -> {
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server.setHandler(context);

            NativeWebSocketConfiguration configuration = new NativeWebSocketConfiguration(context.getServletContext());
            configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
            configuration.addMapping("/info/*", infoCreator);
            context.addBean(configuration, true);

            FilterHolder wsufHolder = new FilterHolder(new WebSocketUpgradeFilter(configuration));
            context.addFilter(wsufHolder, "/*", EnumSet.of(DispatcherType.REQUEST));

            return context;
        }));

        // Embedded WSUF, added as filter, apply app-ws configuration via ServletContextListener

        cases.add(Arguments.of("wsuf.configureContext/ServletContextListener configure", (ContextProvider) (server14) ->
        {
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server14.setHandler(context);
            context.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
            context.addEventListener(new InfoContextListener());

            return context;
        }));

        return cases.stream();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("scenarios")
    public void testNormalConfiguration(String testId, ContextProvider serverProvider) throws Exception
    {
        startServer(serverProvider);

        try (LocalFuzzer session = server.newLocalFuzzer("/info/"))
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

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("scenarios")
    public void testStopStartOfHandler(String testId, ContextProvider serverProvider) throws Exception
    {
        startServer(serverProvider);

        try (LocalFuzzer session = server.newLocalFuzzer("/info/"))
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

        server.getServletContextHandler().stop();
        server.getServletContextHandler().start();

        // Make request again (server should have retained websocket configuration)

        try (LocalFuzzer session = server.newLocalFuzzer("/info/"))
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

    private void startServer(ContextProvider contextProvider) throws Exception
    {
        this.server = new LocalServer()
        {
            @Override
            protected Handler createRootHandler(Server server) throws Exception
            {
                return contextProvider.configureRootHandler(server);
            }
        };
    }

    interface ContextProvider
    {
        Handler configureRootHandler(Server server) throws Exception;
    }
}
