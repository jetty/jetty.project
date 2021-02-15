//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.continuation;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Stream;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ContinuationsTest
{
    public static Stream<Arguments> setups()
    {
        List<Arguments> setup = new ArrayList<>();

        // Servlet3 / AsyncContext Setup
        {
            String description = "Servlet 3 Setup";
            Class<? extends Continuation> expectedImplClass = Servlet3Continuation.class;
            List<String> log = new ArrayList<>();
            RequestLogHandler servlet3Setup = new RequestLogHandler();
            servlet3Setup.setRequestLog(new Log(log));

            ServletContextHandler servletContext = new ServletContextHandler();
            servlet3Setup.setHandler(servletContext);

            ServletHandler servletHandler = servletContext.getServletHandler();
            List<String> history = new ArrayList<>();
            Listener listener = new Listener(history);
            ServletHolder holder = new ServletHolder(new SuspendServlet(history, listener));
            holder.setAsyncSupported(true);
            servletHandler.addServletWithMapping(holder, "/");
            setup.add(Arguments.of(new Scenario(description, servlet3Setup, history, listener, expectedImplClass, log)));
        }

        // Faux Continuations Setup
        {
            String description = "Faux Setup";
            Class<? extends Continuation> expectedImplClass = FauxContinuation.class;

            // no log for this setup
            List<String> log = null;

            ServletContextHandler fauxSetup = new ServletContextHandler();
            ServletHandler servletHandler = fauxSetup.getServletHandler();
            List<String> history = new ArrayList<>();
            Listener listener = new Listener(history);
            ServletHolder holder = new ServletHolder(new SuspendServlet(history, listener));
            servletHandler.addServletWithMapping(holder, "/");

            FilterHolder filter = servletHandler.addFilterWithMapping(ContinuationFilter.class, "/*", null);
            filter.setInitParameter("debug", "true");
            filter.setInitParameter("faux", "true");
            setup.add(Arguments.of(new Scenario(description, fauxSetup, history, listener, expectedImplClass, log)));
        }

        return setup.stream();
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testNormal(Scenario scenario) throws Exception
    {
        String response = process(scenario, null, null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("NORMAL"));
        assertThat(scenario.history, hasItem(scenario.expectedImplClass.getName()));
        assertThat(scenario.history, not(hasItem("onTimeout")));
        assertThat(scenario.history, not(hasItem("onComplete")));
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testSleep(Scenario scenario) throws Exception
    {
        String response = process(scenario, "sleep=200", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("SLEPT"));
        assertThat(scenario.history, not(hasItem("onTimeout")));
        assertThat(scenario.history, not(hasItem("onComplete")));
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testSuspend(Scenario scenario) throws Exception
    {
        String response = process(scenario, "suspend=200", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("TIMEOUT"));
        assertThat(scenario.history, hasItem("onTimeout"));
        assertThat(scenario.history, hasItem("onComplete"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testSuspendWaitResume(Scenario scenario) throws Exception
    {
        String response = process(scenario, "suspend=200&resume=10", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("RESUMED"));
        assertThat(scenario.history, not(hasItem("onTimeout")));
        assertThat(scenario.history, hasItem("onComplete"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testSuspendResume(Scenario scenario) throws Exception
    {
        String response = process(scenario, "suspend=200&resume=0", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("RESUMED"));
        assertThat(scenario.history, not(hasItem("onTimeout")));
        assertThat(scenario.history, hasItem("onComplete"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testSuspendWaitComplete(Scenario scenario) throws Exception
    {
        String response = process(scenario, "suspend=200&complete=50", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("COMPLETED"));
        assertThat(scenario.history, hasItem("initial"));
        assertThat(scenario.history, not(hasItem("!initial")));
        assertThat(scenario.history, not(hasItem("onTimeout")));
        assertThat(scenario.history, hasItem("onComplete"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testSuspendComplete(Scenario scenario) throws Exception
    {
        String response = process(scenario, "suspend=200&complete=0", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("COMPLETED"));
        assertThat(scenario.history, hasItem("initial"));
        assertThat(scenario.history, not(hasItem("!initial")));
        assertThat(scenario.history, not(hasItem("onTimeout")));
        assertThat(scenario.history, hasItem("onComplete"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testSuspendWaitResumeSuspendWaitResume(Scenario scenario) throws Exception
    {
        String response = process(scenario, "suspend=1000&resume=10&suspend2=1000&resume2=10", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("RESUMED"));
        assertEquals(2, count(scenario.history, "suspend"));
        assertEquals(2, count(scenario.history, "resume"));
        assertEquals(0, count(scenario.history, "onTimeout"));
        assertEquals(1, count(scenario.history, "onComplete"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testSuspendWaitResumeSuspendComplete(Scenario scenario) throws Exception
    {
        String response = process(scenario, "suspend=1000&resume=10&suspend2=1000&complete2=10", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("COMPLETED"));
        assertEquals(2, count(scenario.history, "suspend"));
        assertEquals(1, count(scenario.history, "resume"));
        assertEquals(0, count(scenario.history, "onTimeout"));
        assertEquals(1, count(scenario.history, "onComplete"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testSuspendWaitResumeSuspend(Scenario scenario) throws Exception
    {
        String response = process(scenario, "suspend=1000&resume=10&suspend2=10", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("TIMEOUT"));
        assertEquals(2, count(scenario.history, "suspend"));
        assertEquals(1, count(scenario.history, "resume"));
        assertEquals(1, count(scenario.history, "onTimeout"));
        assertEquals(1, count(scenario.history, "onComplete"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testSuspendTimeoutSuspendResume(Scenario scenario) throws Exception
    {
        String response = process(scenario, "suspend=10&suspend2=1000&resume2=10", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("RESUMED"));
        assertEquals(2, count(scenario.history, "suspend"));
        assertEquals(1, count(scenario.history, "resume"));
        assertEquals(1, count(scenario.history, "onTimeout"));
        assertEquals(1, count(scenario.history, "onComplete"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testSuspendTimeoutSuspendComplete(Scenario scenario) throws Exception
    {
        String response = process(scenario, "suspend=10&suspend2=1000&complete2=10", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("COMPLETED"));
        assertEquals(2, count(scenario.history, "suspend"));
        assertEquals(0, count(scenario.history, "resume"));
        assertEquals(1, count(scenario.history, "onTimeout"));
        assertEquals(1, count(scenario.history, "onComplete"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testSuspendTimeoutSuspend(Scenario scenario) throws Exception
    {
        String response = process(scenario, "suspend=10&suspend2=10", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("TIMEOUT"));
        assertEquals(2, count(scenario.history, "suspend"));
        assertEquals(0, count(scenario.history, "resume"));
        assertEquals(2, count(scenario.history, "onTimeout"));
        assertEquals(1, count(scenario.history, "onComplete"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testSuspendThrowResume(Scenario scenario) throws Exception
    {
        String response = process(scenario, "suspend=200&resume=10&undispatch=true", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("RESUMED"));
        assertThat(scenario.history, not(hasItem("onTimeout")));
        assertThat(scenario.history, hasItem("onComplete"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testSuspendResumeThrow(Scenario scenario) throws Exception
    {
        String response = process(scenario, "suspend=200&resume=0&undispatch=true", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("RESUMED"));
        assertThat(scenario.history, not(hasItem("onTimeout")));
        assertThat(scenario.history, hasItem("onComplete"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testSuspendThrowComplete(Scenario scenario) throws Exception
    {
        String response = process(scenario, "suspend=200&complete=10&undispatch=true", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("COMPLETED"));
        assertThat(scenario.history, not(hasItem("onTimeout")));
        assertThat(scenario.history, hasItem("onComplete"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    public void testSuspendCompleteThrow(Scenario scenario) throws Exception
    {
        String response = process(scenario, "suspend=200&complete=0&undispatch=true", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("COMPLETED"));
        assertThat(scenario.history, not(hasItem("onTimeout")));
        assertThat(scenario.history, hasItem("onComplete"));
    }

    private long count(List<String> history, String value)
    {
        return history.stream()
            .filter(value::equals)
            .count();
    }

    private String process(Scenario scenario, String query, String content) throws Exception
    {
        Server server = new Server();
        server.setStopTimeout(20000);
        try
        {
            ServerConnector connector = new ServerConnector(server);
            server.addConnector(connector);
            if (scenario.log != null)
            {
                scenario.log.clear();
            }
            scenario.history.clear();
            StatisticsHandler stats = new StatisticsHandler();
            server.setHandler(stats);
            stats.setHandler(scenario.setupHandler);

            server.start();
            int port = connector.getLocalPort();

            StringBuilder request = new StringBuilder("GET /");

            if (query != null)
                request.append("?").append(query);

            request.append(" HTTP/1.1\r\n")
                .append("Host: localhost\r\n")
                .append("Connection: close\r\n");

            if (content != null)
                request.append("Content-Length: ").append(content.length()).append("\r\n");

            request.append("\r\n"); // end of header

            if (content != null)
                request.append(content);

            try (Socket socket = new Socket("localhost", port))
            {
                socket.setSoTimeout(10000);
                System.err.println("request: " + request);
                socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                return toString(socket.getInputStream());
            }
        }
        finally
        {
            if (scenario.log != null)
            {
                for (int i = 0; scenario.log.isEmpty() && i < 60; i++)
                {
                    Thread.sleep(100);
                }
                assertThat("Log.size", scenario.log.size(), is(1));
                String entry = scenario.log.get(0);
                assertThat("Log entry", entry, startsWith("200 "));
                assertThat("Log entry", entry, endsWith(" /"));
            }
            server.stop();
        }
    }

    protected String toString(InputStream in) throws IOException
    {
        return IO.toString(in);
    }

    @SuppressWarnings("serial")
    private static class SuspendServlet extends HttpServlet
    {
        private final Timer _timer = new Timer();
        private final List<String> history;
        private final ContinuationListener listener;

        public SuspendServlet(List<String> history, ContinuationListener listener)
        {
            this.history = history;
            this.listener = listener;
        }

        @Override
        protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
        {
            final Continuation continuation = ContinuationSupport.getContinuation(request);

            history.add(continuation.getClass().getName());

            int readBefore = 0;
            long sleepFor = -1;
            long suspendFor = -1;
            long suspend2For = -1;
            long resumeAfter = -1;
            long resume2After = -1;
            long completeAfter = -1;
            long complete2After = -1;
            boolean undispatch = false;

            if (request.getParameter("read") != null)
                readBefore = Integer.parseInt(request.getParameter("read"));
            if (request.getParameter("sleep") != null)
                sleepFor = Integer.parseInt(request.getParameter("sleep"));
            if (request.getParameter("suspend") != null)
                suspendFor = Integer.parseInt(request.getParameter("suspend"));
            if (request.getParameter("suspend2") != null)
                suspend2For = Integer.parseInt(request.getParameter("suspend2"));
            if (request.getParameter("resume") != null)
                resumeAfter = Integer.parseInt(request.getParameter("resume"));
            if (request.getParameter("resume2") != null)
                resume2After = Integer.parseInt(request.getParameter("resume2"));
            if (request.getParameter("complete") != null)
                completeAfter = Integer.parseInt(request.getParameter("complete"));
            if (request.getParameter("complete2") != null)
                complete2After = Integer.parseInt(request.getParameter("complete2"));
            if (request.getParameter("undispatch") != null)
                undispatch = Boolean.parseBoolean(request.getParameter("undispatch"));

            if (continuation.isInitial())
            {
                history.add("initial");
                if (readBefore > 0)
                {
                    byte[] buf = new byte[readBefore];
                    request.getInputStream().read(buf);
                }
                else if (readBefore < 0)
                {
                    InputStream in = request.getInputStream();
                    int b = in.read();
                    while (b != -1)
                    {
                        b = in.read();
                    }
                }

                if (suspendFor >= 0)
                {
                    if (suspendFor > 0)
                        continuation.setTimeout(suspendFor);
                    continuation.addContinuationListener(listener);
                    history.add("suspend");
                    continuation.suspend(response);

                    if (completeAfter > 0)
                    {
                        TimerTask complete = new TimerTask()
                        {
                            @Override
                            public void run()
                            {
                                try
                                {
                                    response.setStatus(200);
                                    response.getOutputStream().println("COMPLETED");
                                    continuation.complete();
                                }
                                catch (Exception e)
                                {
                                    e.printStackTrace();
                                }
                            }
                        };
                        _timer.schedule(complete, completeAfter);
                    }
                    else if (completeAfter == 0)
                    {
                        response.setStatus(200);
                        response.getOutputStream().println("COMPLETED");
                        continuation.complete();
                    }
                    else if (resumeAfter > 0)
                    {
                        TimerTask resume = new TimerTask()
                        {
                            @Override
                            public void run()
                            {
                                history.add("resume");
                                continuation.resume();
                            }
                        };
                        _timer.schedule(resume, resumeAfter);
                    }
                    else if (resumeAfter == 0)
                    {
                        history.add("resume");
                        continuation.resume();
                    }

                    if (undispatch)
                    {
                        continuation.undispatch();
                    }
                }
                else if (sleepFor >= 0)
                {
                    try
                    {
                        Thread.sleep(sleepFor);
                    }
                    catch (InterruptedException e)
                    {
                        e.printStackTrace();
                    }
                    response.setStatus(200);
                    response.getOutputStream().println("SLEPT");
                }
                else
                {
                    response.setStatus(200);
                    response.getOutputStream().println("NORMAL");
                }
            }
            else
            {
                history.add("!initial");
                if (suspend2For >= 0 && request.getAttribute("2nd") == null)
                {
                    request.setAttribute("2nd", "cycle");

                    if (suspend2For > 0)
                        continuation.setTimeout(suspend2For);

                    history.add("suspend");
                    continuation.suspend(response);

                    if (complete2After > 0)
                    {
                        TimerTask complete = new TimerTask()
                        {
                            @Override
                            public void run()
                            {
                                try
                                {
                                    response.setStatus(200);
                                    response.getOutputStream().println("COMPLETED");
                                    continuation.complete();
                                }
                                catch (Exception e)
                                {
                                    e.printStackTrace();
                                }
                            }
                        };
                        _timer.schedule(complete, complete2After);
                    }
                    else if (complete2After == 0)
                    {
                        response.setStatus(200);
                        response.getOutputStream().println("COMPLETED");
                        continuation.complete();
                    }
                    else if (resume2After > 0)
                    {
                        TimerTask resume = new TimerTask()
                        {
                            @Override
                            public void run()
                            {
                                history.add("resume");
                                continuation.resume();
                            }
                        };
                        _timer.schedule(resume, resume2After);
                    }
                    else if (resume2After == 0)
                    {
                        history.add("resume");
                        continuation.resume();
                    }

                    if (undispatch)
                    {
                        continuation.undispatch();
                    }
                }
                else if (continuation.isExpired())
                {
                    response.setStatus(200);
                    response.getOutputStream().println("TIMEOUT");
                }
                else if (continuation.isResumed())
                {
                    response.setStatus(200);
                    response.getOutputStream().println("RESUMED");
                }
                else
                {
                    response.setStatus(200);
                    response.getOutputStream().println("UNKNOWN");
                }
            }
        }
    }

    private static class Listener implements ContinuationListener
    {
        private final List<String> history;

        public Listener(List<String> history)
        {
            this.history = history;
        }

        @Override
        public void onComplete(Continuation continuation)
        {
            history.add("onComplete");
        }

        @Override
        public void onTimeout(Continuation continuation)
        {
            history.add("onTimeout");
        }
    }

    public static class Log extends AbstractLifeCycle implements RequestLog
    {
        private final List<String> log;

        public Log(List<String> log)
        {
            this.log = log;
        }

        @Override
        public void log(Request request, Response response)
        {
            int status = response.getCommittedMetaData().getStatus();
            long written = response.getHttpChannel().getBytesWritten();
            log.add(status + " " + written + " " + request.getRequestURI());
        }
    }

    public static class Scenario
    {
        public String setupDescription;
        public Handler setupHandler;
        public List<String> history;
        public Listener listener;
        public Class<? extends Continuation> expectedImplClass;
        public List<String> log;

        public Scenario(String description, Handler handler, List<String> history, Listener listener, Class<? extends Continuation> expectedImplClass, List<String> log)
        {
            this.setupDescription = description;
            this.setupHandler = handler;
            this.history = history;
            this.listener = listener;
            this.expectedImplClass = expectedImplClass;
            this.log = log;
        }

        @Override
        public String toString()
        {
            return this.setupDescription;
        }
    }
}
