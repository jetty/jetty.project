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
package org.eclipse.jetty.webapp.logging;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.logging.impl.Appender;
import org.eclipse.jetty.logging.impl.Severity;

/**
 * Test Appender, records the logging events.
 */
public class TestAppender implements Appender
{
    public static class LogEvent
    {
        String date;
        Severity severity;
        String name;
        String message;
        Throwable t;

        public LogEvent(String date, Severity severity, String name, String message, Throwable t)
        {
            super();
            this.date = date;
            this.severity = severity;
            this.name = name;
            this.message = message;
            this.t = t;
        }

        public LogEvent(Severity severity, String name, String message)
        {
            this(null,severity,name,message,null);
        }

        public LogEvent expectedThrowable(Throwable t)
        {
            this.t = t;
            return this;
        }

        @Override
        public int hashCode()
        {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((message == null)?0:message.hashCode());
            result = prime * result + ((name == null)?0:name.hashCode());
            result = prime * result + ((severity == null)?0:severity.hashCode());
            if (t != null)
            {
                result = prime * result + t.getClass().hashCode();
                if (t.getMessage() != null)
                {
                    result = prime * result + t.getMessage().hashCode();
                }
                else
                {
                    result = prime * result + 0;
                }
            }
            else
            {
                result = prime * result + 0;
            }
            return result;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
            {
                return true;
            }
            if (obj == null)
            {
                return false;
            }
            if (getClass() != obj.getClass())
            {
                return false;
            }
            LogEvent other = (LogEvent)obj;
            if (message == null)
            {
                if (other.message != null)
                {
                    return false;
                }
            }
            else if (!message.equals(other.message))
            {
                return false;
            }
            if (name == null)
            {
                if (other.name != null)
                {
                    return false;
                }
            }
            else if (!name.equals(other.name))
            {
                return false;
            }
            if (severity == null)
            {
                if (other.severity != null)
                {
                    return false;
                }
            }
            else if (!severity.equals(other.severity))
            {
                return false;
            }

            // Throwable
            if (t == null)
            {
                if (other.t != null)
                {
                    return false;
                }
            }
            else
            {
                if (!t.getClass().equals(other.t.getClass()))
                {
                    return false;
                }
                if (t.getMessage() == null)
                {
                    if (other.t.getMessage() != null)
                    {
                        return false;
                    }
                }
                else if (!t.getMessage().equals(other.t.getMessage()))
                {
                    return false;
                }
            }

            return true;
        }

        @Override
        public String toString()
        {
            StringBuffer buf = new StringBuffer();
            buf.append(severity.name()).append("|");
            buf.append(name).append("|");
            buf.append(message);
            if (t != null)
            {
                buf.append("|").append(t.getClass().getName());
                buf.append("(\"").append(t.getMessage()).append("\")");
            }
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

    public void append(String date, Severity severity, String name, String message, Throwable t)
    {
        if (name.equals("org.eclipse.jetty.util.log")) // standard jetty logger
        {
            if (t != null)
            {
                // Still interested in seeing throwables (HACK)
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
        /*
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
            if (expectedEvent.t != null)
            {
                if (event.t == null)
                {
                    continue; // not a match. skip.
                }
                if (!event.t.getClass().equals(expectedEvent.t.getClass()))
                {
                    continue; // not a match. skip.
                }
                if (!event.t.getMessage().equals(expectedEvent.t.getMessage()))
                {
                    continue; // not a match. skip.
                }
            }
            if (event.message.equals(expectedEvent.message))
            {
                return true;
            }
        }*/
        return events.contains(expectedEvent);
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
}
