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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.coders;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.websocket.Decoder;
import jakarta.websocket.Encoder;

/**
 * Singleton used for tracking events of {@link jakarta.websocket.Decoder} and {@link jakarta.websocket.Encoder}
 */
public class CoderEventTracking
{
    private static CoderEventTracking INSTANCE = new CoderEventTracking();

    public static CoderEventTracking getInstance()
    {
        return INSTANCE;
    }

    // Holds the tracking of events (string to count)
    private Map<String, AtomicInteger> eventTracking = new ConcurrentHashMap<>();

    public void clear()
    {
        eventTracking.clear();
    }

    private String toId(Class clazz, String method)
    {
        return String.format("%s#%s", clazz.getName(), method);
    }

    private void addEventCount(Object obj, String method)
    {
        String id = toId(obj.getClass(), method);
        synchronized (eventTracking)
        {
            AtomicInteger count = eventTracking.get(id);
            if (count == null)
            {
                count = new AtomicInteger(0);
                eventTracking.put(id, count);
            }
            count.incrementAndGet();
        }
    }

    public void addEvent(Decoder decoder, String method)
    {
        addEventCount(decoder, method);
    }

    public void addEvent(Encoder encoder, String method)
    {
        addEventCount(encoder, method);
    }

    public int getEventCount(Class clazz, String method)
    {
        String id = toId(clazz, method);
        AtomicInteger count = eventTracking.get(id);
        if (count == null)
        {
            return -1;
        }
        return count.get();
    }
}
