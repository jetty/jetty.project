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

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.websocket.jsr356.JettyClientContainerProvider;
import org.eclipse.jetty.websocket.tests.server.jsr356.DelayedStartClientOnServerTest;
import org.junit.Before;
import org.junit.Test;

public class DelayedStartClientTest
{
    WebSocketContainer container;
    
    @After
    public void stopContainer() throws Exception
    {
        ((LifeCycle)container).stop();
    }
    
    @Test
    public void testNoExtraHttpClientThreads()
    {
        container = ContainerProvider.getWebSocketContainer();
        assertThat("Container", container, notNullValue());
    
        List<String> threadNames = DelayedStartClientOnServerTest.getThreadNames(JettyClientContainerProvider.getInstance());
        assertThat("Threads", threadNames, not(hasItem(containsString("WebSocketContainer@"))));
        assertThat("Threads", threadNames, not(hasItem(containsString("HttpClient@"))));
        assertThat("Threads", threadNames, not(hasItem(containsString("Jsr356Client@"))));
    }
}
