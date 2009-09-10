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
import java.util.TimeZone;

import org.eclipse.jetty.logging.PropertyExpansion;
import org.eclipse.jetty.util.RolloverFileOutputStream;

/**
 * Rolling File Appender
 */
public class RollingFileAppender implements Appender
{
    private static final byte[] LN = System.getProperty("line.separator","\n").getBytes();
    private RolloverFileOutputStream out;
    private String filename;
    private File file;
    private boolean append = true;
    private int retainDays = 31;
    private TimeZone zone = TimeZone.getDefault();
    private String dateFormat = "yyyy_MM_dd";
    private String backupFormat = "HHmmssSSS";
    private String id;

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public void append(String date, Severity severity, String name, String message, Throwable t) throws IOException
    {
        StringBuffer buf = new StringBuffer();
        buf.append(date);
        buf.append(':').append(severity.name()).append(':');
        buf.append(name);
        buf.append(':').append(message);

        out.write(buf.toString().getBytes());
        out.write(LN);
        if (t != null)
        {
            t.printStackTrace(new PrintStream(out));
            out.write(LN);
        }
        out.flush();
    }

    public void close() throws IOException
    {
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

    public String getFilename()
    {
        return filename;
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

    public File getFile()
    {
        return file;
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

        out = new RolloverFileOutputStream(file.getAbsolutePath(),append,retainDays,zone,dateFormat,backupFormat);
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
