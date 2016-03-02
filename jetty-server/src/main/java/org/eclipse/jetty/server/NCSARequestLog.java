//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

/**
 * This {@link RequestLog} implementation outputs logs in the pseudo-standard
 * NCSA common log format. Configuration options allow a choice between the
 * standard Common Log Format (as used in the 3 log format) and the Combined Log
 * Format (single log format). This log format can be output by most web
 * servers, and almost all web log analysis software can understand these
 * formats.
 */
@ManagedObject("NCSA standard format request log")
public class NCSARequestLog extends AbstractNCSARequestLog
{
    private String _filename;
    private boolean _append;
    private int _retainDays;
    private boolean _closeOut;
    private String _filenameDateFormat = null;
    private transient OutputStream _out;
    private transient OutputStream _fileOut;
    private transient Writer _writer;

    /* ------------------------------------------------------------ */
    /**
     * Create request log object with default settings.
     */
    public NCSARequestLog()
    {
        setExtended(true);
        _append = true;
        _retainDays = 31;
    }

    /* ------------------------------------------------------------ */
    /**
     * Create request log object with specified output file name.
     *
     * @param filename the file name for the request log.
     *                 This may be in the format expected
     *                 by {@link RolloverFileOutputStream}
     */
    public NCSARequestLog(String filename)
    {
        setExtended(true);
        _append = true;
        _retainDays = 31;
        setFilename(filename);
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the output file name of the request log.
     * The file name may be in the format expected by
     * {@link RolloverFileOutputStream}.
     *
     * @param filename file name of the request log
     *
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

    /* ------------------------------------------------------------ */
    /**
     * Retrieve the output file name of the request log.
     *
     * @return file name of the request log
     */
    @ManagedAttribute("file of log")
    public String getFilename()
    {
        return _filename;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Retrieve the file name of the request log with the expanded
     * date wildcard if the output is written to the disk using
     * {@link RolloverFileOutputStream}.
     *
     * @return file name of the request log, or null if not applicable
     */
    public String getDatedFilename()
    {
        if (_fileOut instanceof RolloverFileOutputStream)
            return ((RolloverFileOutputStream)_fileOut).getDatedFilename();
        return null;
    }

    /* ------------------------------------------------------------ */
    @Override
    protected boolean isEnabled()
    {
        return (_fileOut != null);
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the number of days before rotated log files are deleted.
     *
     * @param retainDays number of days to keep a log file
     */
    public void setRetainDays(int retainDays)
    {
        _retainDays = retainDays;
    }

    /* ------------------------------------------------------------ */
    /**
     * Retrieve the number of days before rotated log files are deleted.
     *
     * @return number of days to keep a log file
     */
    @ManagedAttribute("number of days that log files are kept")
    public int getRetainDays()
    {
        return _retainDays;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set append to log flag.
     *
     * @param append true - request log file will be appended after restart,
     *               false - request log file will be overwritten after restart
     */
    public void setAppend(boolean append)
    {
        _append = append;
    }

    /* ------------------------------------------------------------ */
    /**
     * Retrieve append to log flag.
     *
     * @return value of the flag
     */
    @ManagedAttribute("existing log files are appends to the new one")
    public boolean isAppend()
    {
        return _append;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the log file name date format.
     * @see RolloverFileOutputStream#RolloverFileOutputStream(String, boolean, int, TimeZone, String, String)
     *
     * @param logFileDateFormat format string that is passed to {@link RolloverFileOutputStream}
     */
    public void setFilenameDateFormat(String logFileDateFormat)
    {
        _filenameDateFormat = logFileDateFormat;
    }

    /* ------------------------------------------------------------ */
    /**
     * Retrieve the file name date format string.
     *
     * @return the log File Date Format
     */
    public String getFilenameDateFormat()
    {
        return _filenameDateFormat;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void write(String requestEntry) throws IOException
    {
        synchronized(this)
        {
            if (_writer==null)
                return;
            _writer.write(requestEntry);
            _writer.write(StringUtil.__LINE_SEPARATOR);
            _writer.flush();
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Set up request logging and open log file.
     *
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected synchronized void doStart() throws Exception
    {
        if (_filename != null)
        {
            _fileOut = new RolloverFileOutputStream(_filename,_append,_retainDays,TimeZone.getTimeZone(getLogTimeZone()),_filenameDateFormat,null);
            _closeOut = true;
            LOG.info("Opened " + getDatedFilename());
        }
        else
            _fileOut = System.err;

        _out = _fileOut;

        synchronized(this)
        {
            _writer = new OutputStreamWriter(_out);
        }
        super.doStart();
    }

    /* ------------------------------------------------------------ */
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
