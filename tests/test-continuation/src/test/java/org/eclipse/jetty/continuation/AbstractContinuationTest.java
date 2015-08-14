//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.IO;
import org.junit.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public abstract class AbstractContinuationTest
{
    protected List<String> _history = new ArrayList<>();
    protected ContinuationListener _listener = new Listener(_history);
    protected SuspendServlet _servlet = new SuspendServlet(_history, _listener);
    protected int _port;

    protected void testNormal(String type) throws Exception
    {
        String response = process(null, null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("NORMAL"));
        assertThat(_history, hasItem(type));
        assertThat(_history, not(hasItem("onTimeout")));
        assertThat(_history, not(hasItem("onComplete")));
    }

    @Test
    public void testSleep() throws Exception
    {
        String response = process("sleep=200", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("SLEPT"));
        assertThat(_history, not(hasItem("onTimeout")));
        assertThat(_history, not(hasItem("onComplete")));
    }

    @Test
    public void testSuspend() throws Exception
    {
        String response = process("suspend=200", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("TIMEOUT"));
        assertThat(_history, hasItem("onTimeout"));
        assertThat(_history, hasItem("onComplete"));
    }

    @Test
    public void testSuspendWaitResume() throws Exception
    {
        String response = process("suspend=200&resume=10", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("RESUMED"));
        assertThat(_history, not(hasItem("onTimeout")));
        assertThat(_history, hasItem("onComplete"));
    }

    @Test
    public void testSuspendResume() throws Exception
    {
        String response = process("suspend=200&resume=0", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("RESUMED"));
        assertThat(_history, not(hasItem("onTimeout")));
        assertThat(_history, hasItem("onComplete"));
    }

    @Test
    public void testSuspendWaitComplete() throws Exception
    {
        String response = process("suspend=200&complete=50", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("COMPLETED"));
        assertThat(_history, hasItem("initial"));
        assertThat(_history, not(hasItem("!initial")));
        assertThat(_history, not(hasItem("onTimeout")));
        assertThat(_history, hasItem("onComplete"));
    }

    @Test
    public void testSuspendComplete() throws Exception
    {
        String response = process("suspend=200&complete=0", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("COMPLETED"));
        assertThat(_history, hasItem("initial"));
        assertThat(_history, not(hasItem("!initial")));
        assertThat(_history, not(hasItem("onTimeout")));
        assertThat(_history, hasItem("onComplete"));
    }

    @Test
    public void testSuspendWaitResumeSuspendWaitResume() throws Exception
    {
        String response = process("suspend=1000&resume=10&suspend2=1000&resume2=10", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("RESUMED"));
        assertEquals(2, count(_history, "suspend"));
        assertEquals(2, count(_history, "resume"));
        assertEquals(0, count(_history, "onTimeout"));
        assertEquals(1, count(_history, "onComplete"));
    }

    @Test
    public void testSuspendWaitResumeSuspendComplete() throws Exception
    {
        String response = process("suspend=1000&resume=10&suspend2=1000&complete2=10", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("COMPLETED"));
        assertEquals(2, count(_history, "suspend"));
        assertEquals(1, count(_history, "resume"));
        assertEquals(0, count(_history, "onTimeout"));
        assertEquals(1, count(_history, "onComplete"));
    }

    @Test
    public void testSuspendWaitResumeSuspend() throws Exception
    {
        String response = process("suspend=1000&resume=10&suspend2=10", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("TIMEOUT"));
        assertEquals(2, count(_history, "suspend"));
        assertEquals(1, count(_history, "resume"));
        assertEquals(1, count(_history, "onTimeout"));
        assertEquals(1, count(_history, "onComplete"));
    }

    @Test
    public void testSuspendTimeoutSuspendResume() throws Exception
    {
        String response = process("suspend=10&suspend2=1000&resume2=10", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("RESUMED"));
        assertEquals(2, count(_history, "suspend"));
        assertEquals(1, count(_history, "resume"));
        assertEquals(1, count(_history, "onTimeout"));
        assertEquals(1, count(_history, "onComplete"));
    }

    @Test
    public void testSuspendTimeoutSuspendComplete() throws Exception
    {
        String response = process("suspend=10&suspend2=1000&complete2=10", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("COMPLETED"));
        assertEquals(2, count(_history, "suspend"));
        assertEquals(0, count(_history, "resume"));
        assertEquals(1, count(_history, "onTimeout"));
        assertEquals(1, count(_history, "onComplete"));
    }

    @Test
    public void testSuspendTimeoutSuspend() throws Exception
    {
        String response = process("suspend=10&suspend2=10", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("TIMEOUT"));
        assertEquals(2, count(_history, "suspend"));
        assertEquals(0, count(_history, "resume"));
        assertEquals(2, count(_history, "onTimeout"));
        assertEquals(1, count(_history, "onComplete"));
    }

    @Test
    public void testSuspendThrowResume() throws Exception
    {
        String response = process("suspend=200&resume=10&undispatch=true", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("RESUMED"));
        assertThat(_history, not(hasItem("onTimeout")));
        assertThat(_history, hasItem("onComplete"));
    }

    @Test
    public void testSuspendResumeThrow() throws Exception
    {
        String response = process("suspend=200&resume=0&undispatch=true", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("RESUMED"));
        assertThat(_history, not(hasItem("onTimeout")));
        assertThat(_history, hasItem("onComplete"));
    }

    @Test
    public void testSuspendThrowComplete() throws Exception
    {
        String response = process("suspend=200&complete=10&undispatch=true", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("COMPLETED"));
        assertThat(_history, not(hasItem("onTimeout")));
        assertThat(_history, hasItem("onComplete"));
    }

    @Test
    public void testSuspendCompleteThrow() throws Exception
    {
        String response = process("suspend=200&complete=0&undispatch=true", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("COMPLETED"));
        assertThat(_history, not(hasItem("onTimeout")));
        assertThat(_history, hasItem("onComplete"));
    }

    private long count(List<String> history, String value)
    {
        return history.stream()
                .filter(value::equals)
                .count();
    }

    private String process(String query, String content) throws Exception
    {
        StringBuilder request = new StringBuilder("GET /");

        if (query != null)
            request.append("?").append(query);

        request.append(" HTTP/1.1\r\n")
                .append("Host: localhost\r\n")
                .append("Connection: close\r\n");

        if (content == null)
        {
            request.append("\r\n");
        }
        else
        {
            request.append("Content-Length: ").append(content.length()).append("\r\n");
            request.append("\r\n").append(content);
        }

        try (Socket socket = new Socket("localhost", _port))
        {
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return toString(socket.getInputStream());
        }
    }

    protected String toString(InputStream in) throws IOException
    {
        return IO.toString(in);
    }

    private static class SuspendServlet extends HttpServlet
    {
        private final Timer _timer = new Timer();
        private final List<String> _history;
        private final ContinuationListener _listener;

        public SuspendServlet(List<String> history, ContinuationListener listener)
        {
            _history = history;
            _listener = listener;
        }

        @Override
        protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
        {
            final Continuation continuation = ContinuationSupport.getContinuation(request);

            _history.add(continuation.getClass().getName());

            int read_before = 0;
            long sleep_for = -1;
            long suspend_for = -1;
            long suspend2_for = -1;
            long resume_after = -1;
            long resume2_after = -1;
            long complete_after = -1;
            long complete2_after = -1;
            boolean undispatch = false;

            if (request.getParameter("read") != null)
                read_before = Integer.parseInt(request.getParameter("read"));
            if (request.getParameter("sleep") != null)
                sleep_for = Integer.parseInt(request.getParameter("sleep"));
            if (request.getParameter("suspend") != null)
                suspend_for = Integer.parseInt(request.getParameter("suspend"));
            if (request.getParameter("suspend2") != null)
                suspend2_for = Integer.parseInt(request.getParameter("suspend2"));
            if (request.getParameter("resume") != null)
                resume_after = Integer.parseInt(request.getParameter("resume"));
            if (request.getParameter("resume2") != null)
                resume2_after = Integer.parseInt(request.getParameter("resume2"));
            if (request.getParameter("complete") != null)
                complete_after = Integer.parseInt(request.getParameter("complete"));
            if (request.getParameter("complete2") != null)
                complete2_after = Integer.parseInt(request.getParameter("complete2"));
            if (request.getParameter("undispatch") != null)
                undispatch = Boolean.parseBoolean(request.getParameter("undispatch"));

            if (continuation.isInitial())
            {
                _history.add("initial");
                if (read_before > 0)
                {
                    byte[] buf = new byte[read_before];
                    request.getInputStream().read(buf);
                }
                else if (read_before < 0)
                {
                    InputStream in = request.getInputStream();
                    int b = in.read();
                    while (b != -1)
                        b = in.read();
                }

                if (suspend_for >= 0)
                {
                    if (suspend_for > 0)
                        continuation.setTimeout(suspend_for);
                    continuation.addContinuationListener(_listener);
                    _history.add("suspend");
                    continuation.suspend(response);

                    if (complete_after > 0)
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
                        _timer.schedule(complete, complete_after);
                    }
                    else if (complete_after == 0)
                    {
                        response.setStatus(200);
                        response.getOutputStream().println("COMPLETED");
                        continuation.complete();
                    }
                    else if (resume_after > 0)
                    {
                        TimerTask resume = new TimerTask()
                        {
                            @Override
                            public void run()
                            {
                                _history.add("resume");
                                continuation.resume();
                            }
                        };
                        _timer.schedule(resume, resume_after);
                    }
                    else if (resume_after == 0)
                    {
                        _history.add("resume");
                        continuation.resume();
                    }

                    if (undispatch)
                    {
                        continuation.undispatch();
                    }
                }
                else if (sleep_for >= 0)
                {
                    try
                    {
                        Thread.sleep(sleep_for);
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
                _history.add("!initial");
                if (suspend2_for >= 0 && request.getAttribute("2nd") == null)
                {
                    request.setAttribute("2nd", "cycle");

                    if (suspend2_for > 0)
                        continuation.setTimeout(suspend2_for);

                    _history.add("suspend");
                    continuation.suspend(response);

                    if (complete2_after > 0)
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
                        _timer.schedule(complete, complete2_after);
                    }
                    else if (complete2_after == 0)
                    {
                        response.setStatus(200);
                        response.getOutputStream().println("COMPLETED");
                        continuation.complete();
                    }
                    else if (resume2_after > 0)
                    {
                        TimerTask resume = new TimerTask()
                        {
                            @Override
                            public void run()
                            {
                                _history.add("resume");
                                continuation.resume();
                            }
                        };
                        _timer.schedule(resume, resume2_after);
                    }
                    else if (resume2_after == 0)
                    {
                        _history.add("resume");
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
        private final List<String> _history;

        public Listener(List<String> history)
        {
            _history = history;
        }

        @Override
        public void onComplete(Continuation continuation)
        {
            _history.add("onComplete");
        }

        @Override
        public void onTimeout(Continuation continuation)
        {
            _history.add("onTimeout");
        }
    }
}
