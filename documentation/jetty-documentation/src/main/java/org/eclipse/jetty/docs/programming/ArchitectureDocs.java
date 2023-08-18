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

package org.eclipse.jetty.docs.programming;

import java.util.concurrent.Executors;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

@SuppressWarnings("unused")
public class ArchitectureDocs
{
    public void configureVirtualThreads()
    {
        // tag::virtual[]
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());

        Server server = new Server(threadPool);
        // end::virtual[]
    }
}
