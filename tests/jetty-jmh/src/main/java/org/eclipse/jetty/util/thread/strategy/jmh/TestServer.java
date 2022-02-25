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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.TryExecutor;

public class TestServer implements Executor, TryExecutor
{
    private final ConcurrentMap<String, Map<String, String>> _sessions = new ConcurrentHashMap<>();
    private final QueuedThreadPool _threadpool = new QueuedThreadPool(200);
    private final File _docroot;

    TestServer(File docroot)
    {
        _threadpool.setReservedThreads(20);
        _docroot = docroot;
    }

    TestServer()
    {
        this(new File(System.getProperty("java.io.tmpdir")));
    }

    public Map<String, String> getSession(String sessionid)
    {
        Map<String, String> session = _sessions.get(sessionid);
        if (session == null)
        {
            session = new HashMap<>();
            session.put("id", sessionid);
            Map<String, String> s = _sessions.putIfAbsent(sessionid, session);
            if (s != null)
                session = s;
        }
        return session;
    }

    public int getRandom(int max)
    {
        return ThreadLocalRandom.current().nextInt(max);
    }

    @Override
    public void execute(Runnable task)
    {
        _threadpool.execute(task);
    }

    @Override
    public boolean tryExecute(Runnable task)
    {
        return _threadpool.tryExecute(task);
    }

    public void start() throws Exception
    {
        _threadpool.start();
    }

    public void stop() throws Exception
    {
        _threadpool.stop();
    }

    public File getFile(String path)
    {
        return new File(_docroot, path);
    }
}
