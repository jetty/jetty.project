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

package org.eclipse.jetty.util.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.eclipse.jetty.util.IO;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class CapturingJULHandler extends Handler
{
    private static final String LN = System.getProperty("line.separator");
    private StringBuilder output = new StringBuilder();

    @Override
    public void publish(LogRecord record)
    {
        StringBuilder buf = new StringBuilder();
        buf.append(record.getLevel().getName()).append("|");
        buf.append(record.getLoggerName()).append("|");
        buf.append(record.getMessage());

        output.append(buf);
        if (record.getMessage().length() > 0)
        {
            output.append(LN);
        }

        if (record.getThrown() != null)
        {
            StringWriter sw = new StringWriter(128);
            PrintWriter capture = new PrintWriter(sw);
            record.getThrown().printStackTrace(capture);
            capture.flush();
            output.append(sw.toString());
            IO.close(capture);
        }
    }

    public void clear()
    {
        output.setLength(0);
    }

    @Override
    public void flush()
    {
        /* do nothing */
    }

    @Override
    public void close() throws SecurityException
    {
        /* do nothing */
    }

    public void dump()
    {
        System.out.println(output);
    }

    public void assertContainsLine(String line)
    {
        assertThat(output.toString(), containsString(line));
    }
}
