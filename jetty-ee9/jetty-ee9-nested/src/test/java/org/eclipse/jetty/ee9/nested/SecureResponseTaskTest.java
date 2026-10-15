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

package org.eclipse.jetty.ee9.nested;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.logging.JettyLevel;
import org.eclipse.jetty.logging.JettyLogger;
import org.eclipse.jetty.logging.StdErrAppender;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Tests the reporting of a {@link HttpChannel#setSecureResponseTask(Runnable) secure response task}
 * that fails, which is how an {@code Authenticator} says the response must not reach the client.
 */
public class SecureResponseTaskTest
{
    private Server _server;
    private LocalConnector _connector;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        ContextHandler context = new ContextHandler(_server);
        context.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                baseRequest.getHttpChannel().setSecureResponseTask(() ->
                {
                    throw new RuntimeException("cannot secure");
                });
                response.getWriter().print("RESOURCE");
                if (request.getHeader("X-Flush") != null)
                    response.flushBuffer();
            }
        });
        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testFailureOnACommittedResponseIsLogged() throws Exception
    {
        // The handler has flushed, so the response can only be aborted, and this is the last point at which the failure can be reported.
        String log = captureLogging(() -> _connector.getResponse("GET /flushed HTTP/1.0\r\nX-Flush: yes\r\n\r\n"));

        assertThat(log, containsString("Could not secure the committed response of /flushed"));
        assertThat(log, containsString("java.lang.RuntimeException: cannot secure"));
    }

    @Test
    public void testFailureOnAnUncommittedResponseIsReportedOnlyByTheErrorResponse() throws Exception
    {
        // An uncommitted response is replaced by a 500, which reports the failure itself, so the channel must not report it again.
        String log = captureLogging(() ->
        {
            String response = _connector.getResponse("GET /buffered HTTP/1.0\r\n\r\n");
            assertThat(response, containsString("HTTP/1.1 500"));
        });

        assertThat(log, containsString("writeError: status=500"));
        assertThat(log, containsString("java.lang.RuntimeException: cannot secure"));
        assertThat(log, not(containsString("Could not secure the committed response of /buffered")));
    }

    /**
     * <p>Runs the action with the {@link HttpChannel} and {@link Response} loggers capturing to a
     * string at WARN, so that what an operator would see can be asserted.</p>
     * <p>The appender is shared, so only what this test provokes can be asserted on: the messages
     * below are unique to it and name the request they are about.</p>
     */
    private String captureLogging(ThrowingRunnable action) throws Exception
    {
        JettyLogger channelLogger = (JettyLogger)LoggerFactory.getLogger(HttpChannel.class);
        JettyLogger responseLogger = (JettyLogger)LoggerFactory.getLogger(Response.class);
        StdErrAppender appender = (StdErrAppender)channelLogger.getAppender();
        PrintStream oldStream = appender.getStream();
        JettyLevel oldChannelLevel = channelLogger.getLevel();
        JettyLevel oldResponseLevel = responseLogger.getLevel();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try
        {
            appender.setStream(new PrintStream(bytes, true));
            channelLogger.setLevel(JettyLevel.WARN);
            responseLogger.setLevel(JettyLevel.WARN);
            action.run();
        }
        finally
        {
            appender.setStream(oldStream);
            channelLogger.setLevel(oldChannelLevel);
            responseLogger.setLevel(oldResponseLevel);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private interface ThrowingRunnable
    {
        void run() throws Exception;
    }
}
