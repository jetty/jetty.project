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

package org.eclipse.jetty.client;

import java.util.ArrayList;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RequestListenersTest
{
    @Test
    public void testAddRemove()
    {
        RequestListeners listeners = new RequestListeners();

        List<String> events = new ArrayList<>();
        Request.Listener listener1 = new Request.Listener()
        {
            @Override
            public void onQueued(Request request)
            {
                events.add("queued1");
            }
        };
        listeners.addListener(listener1);
        Request.Listener listener2 = new Request.Listener()
        {
            @Override
            public void onQueued(Request request)
            {
                events.add("queued2");
            }
        };
        listeners.addListener(listener2);
        Request.Listener listener3 = new Request.Listener()
        {
            @Override
            public void onQueued(Request request)
            {
                events.add("queued3");
            }
        };
        listeners.addListener(listener3);
        Request.Listener listener4 = new Request.Listener()
        {
            @Override
            public void onQueued(Request request)
            {
                events.add("queued4");
            }
        };
        listeners.addListener(listener4);

        listeners.getQueuedListener().onQueued(null);
        assertEquals(4, events.size());
        assertThat(events, Matchers.contains("queued1", "queued2", "queued3", "queued4"));
        events.clear();

        boolean removed = listeners.removeListener(listener2);
        assertTrue(removed);

        listeners.getQueuedListener().onQueued(null);
        assertEquals(3, events.size());
        assertThat(events, Matchers.contains("queued1", "queued3", "queued4"));
        events.clear();

        removed = listeners.removeListener(null);
        assertFalse(removed);

        removed = listeners.removeListener(new Request.Listener() {});
        assertFalse(removed);

        removed = listeners.removeListener(listener3);
        assertTrue(removed);

        listeners.getQueuedListener().onQueued(null);
        assertEquals(2, events.size());
        assertThat(events, Matchers.contains("queued1", "queued4"));
        events.clear();

        removed = listeners.removeListener(listener4);
        assertTrue(removed);

        listeners.getQueuedListener().onQueued(null);
        assertEquals(1, events.size());
        assertThat(events, Matchers.contains("queued1"));
        events.clear();

        removed = listeners.removeListener(listener1);
        assertTrue(removed);
        assertNull(listeners.getQueuedListener());
    }
}
