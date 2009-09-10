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

/**
 * Standard Appender to the STDOUT Console
 */
public class ConsoleAppender implements Appender
{
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
        StringBuffer buf = new StringBuffer();
        buf.append(date);
        buf.append(':').append(severity.name()).append(':');
        buf.append(name);
        buf.append(':').append(message);

        System.out.println(buf.toString());
        if (t != null)
        {
            t.printStackTrace(System.out);
        }
        System.out.flush();
    }

    public void setProperty(String key, String value) throws Exception
    {
        /* nothing to do here */
    }

    public void open() throws IOException
    {
        /* nothing to do here */
    }

    public void close() throws IOException
    {
        /* nothing to do here */
    }

    @Override
    public String toString()
    {
        return "ConsoleAppender[" + id + "]";
    }
}
