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

package org.eclipse.jetty.server;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.eclipse.jetty.logging.JettyLevel;
import org.eclipse.jetty.logging.JettyLogger;
import org.eclipse.jetty.logging.JettyLoggerFactory;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Logger Debug Testing of Handlers
 */
public class LoggerDebugTest
{
    private CaptureDebugLogging captureDebugLogging = new CaptureDebugLogging(System.err);
    private PrintStream old = System.err;

    @BeforeEach
    public void init()
    {
        // Add some classes with Loggers that have triggered logging issues in the past
        captureDebugLogging.capture(ContainerLifeCycle.class);
        captureDebugLogging.capture(Handler.Wrapper.class);
        System.setErr(captureDebugLogging);
    }

    @AfterEach
    public void teardown()
    {
        System.setErr(old);
        captureDebugLogging.restore();
    }

    /**
     * Test to ensure that "SLF4J: Failed toString() invocation on an object of type" does not happen with Wrappers.
     * See https://github.com/jetty/jetty.project/issues/11220
     */
    @Test
    public void testWrapperNPE()
    {
        ContextHandler simpleContextHandler = new ContextHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        // verify that there was no exception in System.err from SLF4J (due to bad "this" handling in Wrapper class)
        assertThat(captureDebugLogging.capture.toString(), not(containsString("SLF4J: Failed toString()")));
    }

    record OriginalLogger(JettyLogger logger, JettyLevel level) {
    }

    public static class CaptureDebugLogging extends PrintStream
    {
        private final PrintStream out;
        StringBuilder capture = new StringBuilder();
        private final JettyLoggerFactory jettyLoggerFactory;
        private List<OriginalLogger> originalLoggers = new ArrayList<>();

        public CaptureDebugLogging(PrintStream out)
        {
            super(out);
            this.out = out;
            ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
            JettyLoggerFactory jettyFactory = null;
            if (loggerFactory instanceof JettyLoggerFactory factory)
            {
                jettyFactory = factory;
            }
            Assumptions.assumeTrue(jettyFactory != null, "Not using jetty-slf4j-impl");
            this.jettyLoggerFactory = jettyFactory;
        }

        public void restore()
        {
            for (OriginalLogger originalLogger : originalLoggers)
            {
                originalLogger.logger.setLevel(originalLogger.level);
            }
        }

        public void capture(Class klass)
        {
            capture(klass.getName());
        }

        public void capture(Object obj)
        {
            capture(obj.getClass());
        }

        public void capture(String loggerName)
        {
            JettyLogger jettyLogger = jettyLoggerFactory.getJettyLogger(loggerName);
            JettyLevel originalLevel = jettyLogger.getLevel();
            originalLoggers.add(new OriginalLogger(jettyLogger, originalLevel));
            jettyLogger.setLevel(JettyLevel.DEBUG);
        }

        private void out(String s)
        {
            this.out.print(s);
            capture.append(s);
        }

        public void print(String s)
        {
            out(s);
        }

        public void println(String s)
        {
            out(s + System.lineSeparator());
        }

        public void println(Object o)
        {
            out(Objects.toString(o) + System.lineSeparator());
        }
    }
}
