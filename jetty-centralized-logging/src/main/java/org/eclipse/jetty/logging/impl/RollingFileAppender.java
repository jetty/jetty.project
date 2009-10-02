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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Date;
import java.util.TimeZone;

import org.eclipse.jetty.logging.impl.io.RolloverFileOutputStream;

/**
 * Rolling File Appender
 */
public class RollingFileAppender implements Appender
{
    private RolloverFileOutputStream fileout;
    private PrintStream out;
    private String filename;
    private File file;
    private boolean append = true;
    private int retainDays = 31;
    private TimeZone zone = TimeZone.getDefault();
    private String dateFormat = "yyyy_MM_dd";
    private String backupFormat = "HHmmssSSS";
    private String id;
    private Formatter formatter;

    public void append(Date date, Severity severity, String name, String message, Throwable t) throws IOException
    {
        out.println(getFormatter().format(date,severity,name,message));
        if (t != null)
        {
            t.printStackTrace(out);
        }
        out.flush();
    }

    public void close() throws IOException
    {
        fileout.close();
        out.close();
    }

    public String getBackupFormat()
    {
        return backupFormat;
    }

    public String getDateFormat()
    {
        return dateFormat;
    }

    public File getFile()
    {
        return file;
    }

    public String getFilename()
    {
        return filename;
    }

    public Formatter getFormatter()
    {
        if (formatter == null)
        {
            formatter = new DefaultFormatter();
        }
        return formatter;
    }

    public String getId()
    {
        return id;
    }

    public int getRetainDays()
    {
        return retainDays;
    }

    public TimeZone getZone()
    {
        return zone;
    }

    public boolean isAppend()
    {
        return append;
    }

    public void open() throws IOException
    {
        file = new File(PropertyExpansion.expand(filename));

        File logDir = file.getParentFile();
        if (!logDir.exists())
        {
            throw new FileNotFoundException("Logging directory does not exist: " + logDir);
        }

        if (!logDir.isDirectory())
        {
            throw new FileNotFoundException("Logging path exist, but is not a directory: " + logDir);
        }

        fileout = new RolloverFileOutputStream(file.getAbsolutePath(),append,retainDays,zone,dateFormat,backupFormat);
        out = new PrintStream(fileout);
    }

    public void setAppend(boolean append)
    {
        this.append = append;
    }

    public void setBackupFormat(String backupFormat)
    {
        this.backupFormat = backupFormat;
    }

    public void setDateFormat(String dateFormat)
    {
        this.dateFormat = dateFormat;
    }

    public void setFilename(String filename)
    {
        this.filename = filename;
    }

    public void setFormatter(Formatter formatter)
    {
        this.formatter = formatter;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public void setProperty(String key, String value) throws Exception
    {
        if ("filename".equals(key))
        {
            setFilename(value);
            return;
        }

        if ("append".equals(key))
        {
            setAppend(Boolean.parseBoolean(value));
            return;
        }

        if ("retainDays".equals(key))
        {
            setRetainDays(Integer.parseInt(value));
            return;
        }

        if ("zone".equals(key))
        {
            setZone(TimeZone.getTimeZone(value));
            return;
        }

        if ("dateFormat".equals(key))
        {
            setDateFormat(value);
            return;
        }

        if ("backupFormat".equals(key))
        {
            setBackupFormat(value);
            return;
        }

        throw new IllegalArgumentException("No such key \"" + key + "\"");
    }

    public void setRetainDays(int retainDays)
    {
        this.retainDays = retainDays;
    }

    public void setZone(TimeZone zone)
    {
        this.zone = zone;
    }

    @Override
    public String toString()
    {
        return "RollingFileAppender[" + id + "|" + filename + "]";
    }
}
