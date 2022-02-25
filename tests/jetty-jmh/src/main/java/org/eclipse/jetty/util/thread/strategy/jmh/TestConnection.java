//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.thread.strategy.jmh;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.jetty.util.thread.ExecutionStrategy.Producer;
import org.eclipse.jetty.util.thread.Invocable;
import org.openjdk.jmh.infra.Blackhole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestConnection implements Producer
{
    private static final Logger LOG = LoggerFactory.getLogger(TestConnection.class);

    private final TestServer _server;
    private final String _sessionid;
    private final boolean _sleeping;
    private final Queue<CompletableFuture<String>> _queue = new ConcurrentLinkedQueue<>();

    public TestConnection(TestServer server, boolean sleeping)
    {
        _server = server;
        _sessionid = "SESSION-" + server.getRandom(100000000);
        _sleeping = sleeping;
    }

    @Override
    public Runnable produce()
    {
        CompletableFuture<String> futureResult = _queue.poll();
        if (futureResult == null)
            return null;

        // The map will represent the request object
        Map<String, String> request = new HashMap<>();
        request.put("sessionid", _sessionid);

        int random = _server.getRandom(1000);
        int uri = random % 100;
        boolean blocking = (random / 10) > 2;
        int delay = (blocking && uri % 4 == 1) ? random / 2 : 0;
        request.put("uri", uri + ".txt"); // one of 100 resources on server
        request.put("blocking", blocking ? "True" : "False"); // one of 100 resources on server
        request.put("delay", Integer.toString(delay)); // random processing delay 0-100ms on 25% of requests
        Blackhole.consumeCPU(_server.getRandom(500)); // random CPU
        Handler handler = new Handler(request, futureResult);
        return handler;
    }

    private class Handler implements Invocable.Task
    {
        private final Map<String, String> _request;
        private final CompletableFuture<String> _futureResult;
        private final boolean _blocking;

        public Handler(Map<String, String> request, CompletableFuture<String> futureResult)
        {
            _request = request;
            _futureResult = futureResult;
            _blocking = Boolean.parseBoolean(request.get("blocking"));
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _blocking ? InvocationType.BLOCKING : InvocationType.NON_BLOCKING;
        }

        @Override
        public void run()
        {
            // Build a response
            StringBuilder response = new StringBuilder(4096);

            try
            {
                // Get the request
                String uri = _request.get("uri");

                // Obtain the session
                Map<String, String> session = _server.getSession(_request.get("sessionid"));

                // Check we are authenticated
                String userid;
                synchronized (session)
                {
                    userid = session.get("userid");
                    Blackhole.consumeCPU(100);
                    if (userid == null)
                    {
                        userid = "USER-" + Math.abs(session.hashCode());
                        session.put("userid", userid);
                    }
                }

                // simulate processing delay, blocking, etc.
                int delay = Integer.parseInt(_request.get("delay"));
                if (delay > 0)
                {
                    if (_sleeping)
                    {
                        try
                        {
                            Thread.sleep(delay / 8);
                        }
                        catch (InterruptedException e)
                        {
                            LOG.trace("IGNORED", e);
                        }
                    }
                    else
                        Blackhole.consumeCPU(delay * 150);
                }

                // get the uri 
                response.append("URI: ").append(uri).append(System.lineSeparator());

                // look for a file
                File file = _server.getFile(uri);
                if (file.exists())
                {
                    response.append("contentType: ").append("file").append(System.lineSeparator());
                    response.append("lastModified: ").append(Long.toString(file.lastModified())).append(System.lineSeparator());
                    response.append("contentLength: ").append(Long.toString(file.length())).append(System.lineSeparator());
                    response.append("content: ").append("This should be content from a file, but lets pretend it was cached").append(System.lineSeparator());
                }
                else
                {
                    response.append("contentType: ").append("dynamic").append(System.lineSeparator());
                    response.append("This is content for ").append(uri)
                        .append(" generated for ").append(userid)
                        .append(" with session ").append(_request.get("sessionid"))
                        .append(" for user ").append(userid)
                        .append(" on thread ").append(Thread.currentThread());
                }

                Blackhole.consumeCPU(1000);
            }
            finally
            {
                _futureResult.complete(response.toString());
            }
        }
    }

    public void submit(CompletableFuture<String> futureResult)
    {
        _queue.offer(futureResult);
    }
}
