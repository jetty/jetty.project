//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.coders;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.websocket.Decoder;
import javax.websocket.Encoder;

/**
 * Singleton used for tracking events of {@link javax.websocket.Decoder} and {@link javax.websocket.Encoder}
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
