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

package org.eclipse.jetty.websocket.jsr356;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

public class DelayedStartClientTest
{
    WebSocketContainer container;

    @AfterEach
    public void stopContainer() throws Exception
    {
        ((LifeCycle)container).stop();
    }

    @Test
    public void testNoExtraHttpClientThreads()
    {
        container = ContainerProvider.getWebSocketContainer();
        assertThat("Container", container, notNullValue());

        List<String> threadNames = getThreadNames((ContainerLifeCycle)container);
        assertThat("Threads", threadNames, not(hasItem(containsString("WebSocketContainer@"))));
        assertThat("Threads", threadNames, not(hasItem(containsString("HttpClient@"))));
    }

    public static List<String> getThreadNames(ContainerLifeCycle... containers)
    {
        List<String> threadNames = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (ContainerLifeCycle container : containers)
        {
            if (container == null)
            {
                continue;
            }

            findConfiguredThreadNames(seen, threadNames, container);
        }
        seen.clear();
        // System.out.println("Threads: " + threadNames.stream().collect(Collectors.joining(", ", "[", "]")));
        return threadNames;
    }

    private static void findConfiguredThreadNames(Set<Object> seen, List<String> threadNames, ContainerLifeCycle container)
    {
        if (seen.contains(container))
        {
            // skip
            return;
        }

        seen.add(container);

        Collection<Executor> executors = container.getBeans(Executor.class);
        for (Executor executor : executors)
        {
            if (executor instanceof QueuedThreadPool)
            {
                QueuedThreadPool qtp = (QueuedThreadPool)executor;
                threadNames.add(qtp.getName());
            }
        }

        for (ContainerLifeCycle child : container.getBeans(ContainerLifeCycle.class))
        {
            findConfiguredThreadNames(seen, threadNames, child);
        }
    }
}
