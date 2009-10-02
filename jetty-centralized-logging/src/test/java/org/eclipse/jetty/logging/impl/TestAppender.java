// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.eclipse.jetty.logging.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jetty.logging.impl.Appender;
import org.eclipse.jetty.logging.impl.Severity;
import org.slf4j.MDC;

/**
 * Test Appender, records the logging events.
 */
public class TestAppender implements Appender
{
    public static class LogEvent
    {
        Date date;
        Severity severity;
        String name;
        String message;
        Throwable t;
        String mdc;

        @SuppressWarnings("unchecked")
        public LogEvent(Date date, Severity severity, String name, String message, Throwable t)
        {
            super();
            this.date = date;
            this.severity = severity;
            this.name = name;
            this.message = message;
            this.t = t;
            this.mdc = "";

            Map<String, String> mdcMap = MDC.getCopyOfContextMap();
            if (mdcMap != null)
            {
                Set<String> keys = new TreeSet<String>();
                keys.addAll(mdcMap.keySet());
                boolean delim = false;
                for (String key : keys)
                {
                    if (delim)
                    {
                        mdc += ", ";
                    }
                    mdc += key + "=" + mdcMap.get(key);
                    delim = true;
                }
            }
        }

        public LogEvent(Severity severity, String name, String message)
        {
            this(null,severity,name,message,null);
        }

        @Override
        public String toString()
        {
            StringBuffer buf = new StringBuffer();
            buf.append(severity.name()).append("|");
            buf.append(name).append("|");
            buf.append(message);
            return buf.toString();
        }
    }

    private List<LogEvent> events = new ArrayList<LogEvent>();
    private String id;

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public void append(Date date, Severity severity, String name, String message, Throwable t)
    {
        if (name.equals("org.eclipse.jetty.util.log")) // standard jetty logger
        {
            if (t != null)
            {
                // Still interested in seeing throwables
                t.printStackTrace(System.err);
            }
            return; // skip storing it.
        }
        events.add(new LogEvent(date,severity,name,message,t));
    }

    public void close() throws IOException
    {
        /* nothing to do here */
    }

    public boolean contains(LogEvent expectedEvent)
    {
        // System.out.println("Looking for: " + expectedEvent);
        for (LogEvent event : events)
        {
            // System.out.println("Event: " + event);
            if (!event.name.equals(expectedEvent.name))
            {
                continue; // not a match. skip.
            }
            if (!event.severity.equals(expectedEvent.severity))
            {
                continue; // not a match. skip.
            }
            if (event.message.equals(expectedEvent.message))
            {
                return true;
            }
        }
        return false;
    }

    public List<LogEvent> getEvents()
    {
        return events;
    }

    public void open() throws IOException
    {
        /* nothing to do here */
    }

    public void reset()
    {
        events.clear();
    }

    public void setProperty(String key, String value) throws Exception
    {
        /* nothing to do here */
    }

    public void dump()
    {
        System.out.printf("Captured %s event(s)%n",events.size());
        for (LogEvent event : events)
        {
            System.out.println(event);
        }
    }

    public void setFormatter(Formatter formatter)
    {
        /* nothing to do here */
    }
}
