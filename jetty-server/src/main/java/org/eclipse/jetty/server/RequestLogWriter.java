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
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.TimeZone;

import org.eclipse.jetty.util.RolloverFileOutputStream;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Writer which outputs pre-formatted request log strings to a file using {@link RolloverFileOutputStream}.
 */
@ManagedObject("Request Log writer which writes to file")
public class RequestLogWriter extends AbstractLifeCycle implements RequestLog.Writer
{
    private static final Logger LOG = Log.getLogger(RequestLogWriter.class);

    private String _filename;
    private boolean _append;
    private int _retainDays;
    private boolean _closeOut;
    private String _timeZone = "GMT";
    private String _filenameDateFormat = null;
    private transient OutputStream _out;
    private transient OutputStream _fileOut;
    private transient Writer _writer;

    public RequestLogWriter()
    {
        this(null);
    }

    public RequestLogWriter(String filename)
    {
        setAppend(true);
        setRetainDays(31);

        if (filename != null)
            setFilename(filename);
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
        if (filename != null)
        {
            filename = filename.trim();
            if (filename.length() == 0)
                filename = null;
        }
        _filename = filename;
    }

    /**
     * Retrieve the output file name of the request log.
     *
     * @return file name of the request log
     */
    @ManagedAttribute("filename")
    public String getFileName()
    {
        return _filename;
    }

    /**
     * Retrieve the file name of the request log with the expanded
     * date wildcard if the output is written to the disk using
     * {@link RolloverFileOutputStream}.
     *
     * @return file name of the request log, or null if not applicable
     */
    @ManagedAttribute("dated filename")
    public String getDatedFilename()
    {
        if (_fileOut instanceof RolloverFileOutputStream)
            return ((RolloverFileOutputStream)_fileOut).getDatedFilename();
        return null;
    }

    @Deprecated
    protected boolean isEnabled()
    {
        return (_fileOut != null);
    }

    /**
     * Set the number of days before rotated log files are deleted.
     *
     * @param retainDays number of days to keep a log file
     */
    public void setRetainDays(int retainDays)
    {
        _retainDays = retainDays;
    }

    /**
     * Retrieve the number of days before rotated log files are deleted.
     *
     * @return number of days to keep a log file
     */
    @ManagedAttribute("number of days to keep a log file")
    public int getRetainDays()
    {
        return _retainDays;
    }

    /**
     * Set append to log flag.
     *
     * @param append true - request log file will be appended after restart,
     * false - request log file will be overwritten after restart
     */
    public void setAppend(boolean append)
    {
        _append = append;
    }

    /**
     * Retrieve append to log flag.
     *
     * @return value of the flag
     */
    @ManagedAttribute("if request log file will be appended after restart")
    public boolean isAppend()
    {
        return _append;
    }

    /**
     * Set the log file name date format.
     *
     * @param logFileDateFormat format string that is passed to {@link RolloverFileOutputStream}
     * @see RolloverFileOutputStream#RolloverFileOutputStream(String, boolean, int, TimeZone, String, String)
     */
    public void setFilenameDateFormat(String logFileDateFormat)
    {
        _filenameDateFormat = logFileDateFormat;
    }

    /**
     * Retrieve the file name date format string.
     *
     * @return the log File Date Format
     */
    @ManagedAttribute("log file name date format")
    public String getFilenameDateFormat()
    {
        return _filenameDateFormat;
    }

    @Override
    public void write(String requestEntry) throws IOException
    {
        synchronized (this)
        {
            if (_writer == null)
                return;
            _writer.write(requestEntry);
            _writer.write(System.lineSeparator());
            _writer.flush();
        }
    }

    @Override
    protected synchronized void doStart() throws Exception
    {
        if (_filename != null)
        {
            _fileOut = new RolloverFileOutputStream(_filename, _append, _retainDays, TimeZone.getTimeZone(getTimeZone()), _filenameDateFormat, null);
            _closeOut = true;
            LOG.info("Opened " + getDatedFilename());
        }
        else
            _fileOut = System.err;

        _out = _fileOut;

        synchronized (this)
        {
            _writer = new OutputStreamWriter(_out);
        }
        super.doStart();
    }

    public void setTimeZone(String timeZone)
    {
        _timeZone = timeZone;
    }

    @ManagedAttribute("timezone of the log")
    public String getTimeZone()
    {
        return _timeZone;
    }

    @Override
    protected void doStop() throws Exception
    {
        synchronized (this)
        {
            super.doStop();
            try
            {
                if (_writer != null)
                    _writer.flush();
            }
            catch (IOException e)
            {
                LOG.ignore(e);
            }
            if (_out != null && _closeOut)
                try
                {
                    _out.close();
                }
                catch (IOException e)
                {
                    LOG.ignore(e);
                }

            _out = null;
            _fileOut = null;
            _closeOut = false;
            _writer = null;
        }
    }
}
