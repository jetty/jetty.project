//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.client.jsr356;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.websocket.jsr356.JettyClientContainerProvider;
import org.junit.Before;
import org.junit.Test;

public class DelayedStartClientTest
{
    @Before
    public void stopClientContainer() throws Exception
    {
        JettyClientContainerProvider.stop();
    }
    
    @Test
    public void testNoExtraHttpClientThreads()
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        assertThat("Container", container, notNullValue());
    
        List<String> threadNames = getThreadNames();
        assertThat("Threads", threadNames, not(hasItem(containsString("WebSocketContainer@"))));
        assertThat("Threads", threadNames, not(hasItem(containsString("HttpClient@"))));
        assertThat("Threads", threadNames, not(hasItem(containsString("Jsr356Client@"))));
    }
    
    private List<String> getThreadNames()
    {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threads = threadMXBean.dumpAllThreads(false, false);
        List<String> ret = new ArrayList<>();
        for (ThreadInfo info : threads)
        {
            ret.add(info.getThreadName());
        }
        return ret;
    }
}
