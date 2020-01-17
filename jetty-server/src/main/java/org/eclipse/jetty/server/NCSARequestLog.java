//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.util.TimeZone;

import org.eclipse.jetty.util.RolloverFileOutputStream;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

/**
 * This {@link RequestLog} implementation outputs logs in the pseudo-standard
 * NCSA common log format. Configuration options allow a choice between the
 * standard Common Log Format (as used in the 3 log format) and the Combined Log
 * Format (single log format). This log format can be output by most web
 * servers, and almost all web log analysis software can understand these
 * formats.
 *
 * @deprecated use {@link CustomRequestLog} given format string {@link CustomRequestLog#EXTENDED_NCSA_FORMAT} with a {@link RequestLogWriter}
 */
@Deprecated
@ManagedObject("NCSA standard format request log")
public class NCSARequestLog extends AbstractNCSARequestLog
{
    private final RequestLogWriter _requestLogWriter;

    /**
     * Create request log object with default settings.
     */
    public NCSARequestLog()
    {
        this((String)null);
    }

    /**
     * Create request log object with specified output file name.
     *
     * @param filename the file name for the request log.
     * This may be in the format expected
     * by {@link RolloverFileOutputStream}
     */
    public NCSARequestLog(String filename)
    {
        this(new RequestLogWriter(filename));
    }

    /**
     * Create request log object given a RequestLogWriter file name.
     *
     * @param writer the writer which manages the output of the formatted string
     * produced by the {@link RequestLog}
     */
    public NCSARequestLog(RequestLogWriter writer)
    {
        super(writer);
        _requestLogWriter = writer;
        setExtended(true);
    }

    /**
     * Set the output file name of the request log.
     * The file name may be in the format expected by
     * {@link RolloverFileOutputStream}.
     *
     * @param filename file name of the request log
     */
    public void setFilename(String filename)
    {
        _requestLogWriter.setFilename(filename);
    }

    @Override
    public void setLogTimeZone(String tz)
    {
        super.setLogTimeZone(tz);
        _requestLogWriter.setTimeZone(tz);
    }

    /**
     * Retrieve the output file name of the request log.
     *
     * @return file name of the request log
     */
    @ManagedAttribute("file of log")
    public String getFilename()
    {
        return _requestLogWriter.getFileName();
    }

    /**
     * Retrieve the file name of the request log with the expanded
     * date wildcard if the output is written to the disk using
     * {@link RolloverFileOutputStream}.
     *
     * @return file name of the request log, or null if not applicable
     */
    public String getDatedFilename()
    {
        return _requestLogWriter.getDatedFilename();
    }

    @Override
    protected boolean isEnabled()
    {
        return _requestLogWriter.isEnabled();
    }

    /**
     * Set the number of days before rotated log files are deleted.
     *
     * @param retainDays number of days to keep a log file
     */
    public void setRetainDays(int retainDays)
    {
        _requestLogWriter.setRetainDays(retainDays);
    }

    /**
     * Retrieve the number of days before rotated log files are deleted.
     *
     * @return number of days to keep a log file
     */
    @ManagedAttribute("number of days that log files are kept")
    public int getRetainDays()
    {
        return _requestLogWriter.getRetainDays();
    }

    /**
     * Set append to log flag.
     *
     * @param append true - request log file will be appended after restart,
     * false - request log file will be overwritten after restart
     */
    public void setAppend(boolean append)
    {
        _requestLogWriter.setAppend(append);
    }

    /**
     * Retrieve append to log flag.
     *
     * @return value of the flag
     */
    @ManagedAttribute("existing log files are appends to the new one")
    public boolean isAppend()
    {
        return _requestLogWriter.isAppend();
    }

    /**
     * Set the log file name date format.
     *
     * @param logFileDateFormat format string that is passed to {@link RolloverFileOutputStream}
     * @see RolloverFileOutputStream#RolloverFileOutputStream(String, boolean, int, TimeZone, String, String)
     */
    public void setFilenameDateFormat(String logFileDateFormat)
    {
        _requestLogWriter.setFilenameDateFormat(logFileDateFormat);
    }

    /**
     * Retrieve the file name date format string.
     *
     * @return the log File Date Format
     */
    public String getFilenameDateFormat()
    {
        return _requestLogWriter.getFilenameDateFormat();
    }

    @Override
    public void write(String requestEntry) throws IOException
    {
        _requestLogWriter.write(requestEntry);
    }

    /**
     * Set up request logging and open log file.
     *
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected synchronized void doStart() throws Exception
    {
        super.doStart();
    }

    /**
     * Close the log file and perform cleanup.
     *
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        synchronized (this)
        {
            super.doStop();
        }
    }
}
