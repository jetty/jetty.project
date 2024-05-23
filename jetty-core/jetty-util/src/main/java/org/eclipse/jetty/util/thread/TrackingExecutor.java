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

package org.eclipse.jetty.util.thread;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;

@ManagedObject("Tracking Executor wrapper")
public class TrackingExecutor implements Executor, Dumpable
{
    private final Executor _threadFactoryExecutor;
    private final Set<Thread> _threads = ConcurrentHashMap.newKeySet();
    private boolean _detailed;

    public TrackingExecutor(Executor executor, boolean detailed)
    {
        _threadFactoryExecutor = executor;
        _detailed = detailed;
    }

    @Override
    public void execute(Runnable task)
    {
        _threadFactoryExecutor.execute(() ->
        {
            Thread thread = Thread.currentThread();
            try
            {
                _threads.add(thread);
                task.run();
            }
            finally
            {
                _threads.remove(thread);
            }
        });
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Object[] threads = _threads.stream().map(DumpableThread::new).toArray();
        Dumpable.dumpObjects(out, indent, _threadFactoryExecutor.toString() + " size=" + threads.length, threads);
    }

    public void setDetailedDump(boolean detailedDump)
    {
        _detailed = detailedDump;
    }

    @ManagedAttribute("reports additional details in the dump")
    public boolean isDetailedDump()
    {
        return _detailed;
    }

    public int size()
    {
        return _threads.size();
    }

    private class DumpableThread implements Dumpable
    {
        private final Thread _thread;

        private DumpableThread(Thread thread)
        {
            _thread = thread;
        }

        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            if (_detailed)
            {
                Object[] stack = _thread.getStackTrace();
                Dumpable.dumpObjects(out, indent, _thread.toString(), stack);
            }
            else
            {
                Dumpable.dumpObject(out, _thread);
            }
        }
    }
}
