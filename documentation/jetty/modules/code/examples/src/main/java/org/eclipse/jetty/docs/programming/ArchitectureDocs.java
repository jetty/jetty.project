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

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.TryExecutor;
import org.eclipse.jetty.util.thread.VirtualThreadPool;

@SuppressWarnings("unused")
public class ArchitectureDocs
{
    public void queuedVirtualThreads()
    {
        // tag::queuedVirtual[]
        QueuedThreadPool threadPool = new QueuedThreadPool();

        // Simple, unlimited, virtual thread Executor.
        threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // Configurable, bounded, virtual thread executor (preferred).
        VirtualThreadPool virtualExecutor = new VirtualThreadPool();
        virtualExecutor.setMaxThreads(128);
        threadPool.setVirtualThreadsExecutor(virtualExecutor);

        // For server-side usage.
        Server server = new Server(threadPool);

        // Simple client-side usage.
        HttpClient client = new HttpClient();
        client.setExecutor(threadPool);

        // Client-side usage with explicit HttpClientTransport.
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setExecutor(threadPool);
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
        // end::queuedVirtual[]
    }

    public void virtualVirtualThreads()
    {
        // tag::virtualVirtual[]
        VirtualThreadPool threadPool = new VirtualThreadPool();
        // Limit the max number of current virtual threads.
        threadPool.setMaxThreads(200);
        // Track, with details, virtual threads usage.
        threadPool.setTracking(true);
        threadPool.setDetailedDump(true);

        // For server-side usage.
        Server server = new Server(threadPool);

        // Simple client-side usage.
        HttpClient client = new HttpClient();
        client.setExecutor(threadPool);

        // Client-side usage with explicit HttpClientTransport.
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setExecutor(threadPool);
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
        // end::virtualVirtual[]
    }

    public static class EitherTask implements Invocable.Task
    {
        private final TryExecutor executor;
        private final Deque<Invocable.Task> subTasks = new ConcurrentLinkedDeque<>();

        public EitherTask(TryExecutor executor)
        {
            this.executor = executor;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.EITHER;
        }

        public void offer(Task task)
        {
            subTasks.add(task);
        }

        @Override
        public void run()
        {
            if (Invocable.isNonBlockingInvocation())
            {
                for (Task subTask = subTasks.pollFirst(); subTask != null; subTask = subTasks.pollFirst())
                    Invocable.getInvocationType(subTask).runWithoutBlocking(subTask, executor);
            }
            else
            {
                for (Task subTask = subTasks.pollFirst(); subTask != null; subTask = subTasks.pollFirst())
                {
                    switch (Invocable.getInvocationType(subTask))
                    {
                        case NON_BLOCKING ->
                            // Do not defer a NON-BLOCKING task
                            subTask.run();
                        case EITHER ->
                        {
                            // Do not defer an EITHER task
                            if (subTasks.isEmpty())
                                // No more subtasks, we can invoke directly with hot CPU cache and not block the head-of-line
                                subTask.run();
                            else
                                // more subtasks, so run non-blocking to avoid head-of-line blocking
                                Invocable.invokeNonBlocking(subTask);
                        }
                        case BLOCKING ->
                        {
                            // We can defer a blocking task, but still must avoid head-of-line blocking

                            if (subTasks.isEmpty())
                                // No more subtasks, we can invoke directly with hot CPU cache and not block the head-of-line
                                subTask.run();
                            else
                                // Defer the BLOCKING subtask to the executor so other subtasks can be handled
                                executor.execute(subTask);
                        }
                    }
                }
            }
        }
    }
}
