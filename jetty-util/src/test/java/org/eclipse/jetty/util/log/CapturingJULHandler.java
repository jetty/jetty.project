package org.eclipse.jetty.util.log;

import static org.hamcrest.Matchers.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.eclipse.jetty.util.IO;
import org.junit.Assert;

public class CapturingJULHandler extends Handler
{
    private List<String> lines = new ArrayList<String>();

    @Override
    public void publish(LogRecord record)
    {
        StringBuilder buf = new StringBuilder();
        buf.append(record.getLevel().getName()).append("|");
        String logname = record.getLoggerName();
        int idx = logname.lastIndexOf('.');
        if (idx > 0)
        {
            logname = logname.substring(idx + 1);
        }
        buf.append(logname);
        buf.append("|");
        buf.append(record.getMessage());

        lines.add(buf.toString());

        if (record.getThrown() != null)
        {
            StringWriter sw = new StringWriter(128);
            PrintWriter capture = new PrintWriter(sw);
            record.getThrown().printStackTrace(capture);
            capture.flush();
            lines.add(sw.toString());
            IO.close(capture);
        }
    }

    public void clear()
    {
        lines.clear();
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
        for (String line : lines)
        {
            System.out.println(line);
        }
    }

    public void assertContainsLine(String line)
    {
        Assert.assertThat(lines, contains(line));
    }
}
