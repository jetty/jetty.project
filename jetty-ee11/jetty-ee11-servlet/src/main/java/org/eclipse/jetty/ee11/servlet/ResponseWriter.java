//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee11.servlet;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.util.Formatter;
import java.util.Locale;
import java.util.Objects;

import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.WriteThroughWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Specialized PrintWriter for servlet Responses
 * <p>An instance of ResponseWriter is the {@link PrintWriter} subclass returned by {@link HttpServletResponse#getWriter()}.
 * It differs from the standard {@link PrintWriter} in that:<ul>
 * <li>It does not support autoflush</li>
 * <li>The default Locale for {@link #format(String, Object...)} is the locale obtained by {@link ServletResponse#getLocale()}</li>
 * <li>If a write or print method is called while {@link #checkError()}  returns true, then a {@link RuntimeIOException} is thrown to stop needless iterations.</li>
 * <li>The writer may be reopen to allow for recycling</li>
 * </ul>
 */
public class ResponseWriter extends PrintWriter
{
    private static final Logger LOG = LoggerFactory.getLogger(ResponseWriter.class);

    private final Object _lock;
    private final WriteThroughWriter _writer;
    private final Locale _locale;
    private final String _encoding;
    private IOException _ioException;
    private boolean _isClosed = false;
    private Formatter _formatter;

    public ResponseWriter(WriteThroughWriter writer, Locale locale, String encoding)
    {
        super(writer, false);
        _lock = lock;
        _writer = writer;
        _locale = locale;
        _encoding = encoding;
    }

    public boolean isFor(Locale locale, String encoding)
    {
        if (_locale == null && locale != null)
            return false;
        if (_encoding == null && encoding != null)
            return false;
        return Objects.equals(_encoding, encoding) && Objects.equals(_locale, locale); 
    }

    public void reopen()
    {
        synchronized (_lock)
        {
            _isClosed = false;
            clearError();
            out = _writer;
        }
    }

    @Override
    protected void clearError()
    {
        synchronized (_lock)
        {
            _ioException = null;
            super.clearError();
        }
    }

    @Override
    public boolean checkError()
    {
        synchronized (_lock)
        {
            return _ioException != null || super.checkError();
        }
    }

    private void setError(Throwable th)
    {
        super.setError();

        if (th instanceof IOException)
        {
            _ioException = (IOException)th;
        }
        else
        {
            _ioException = new IOException(String.valueOf(th));
            _ioException.initCause(th);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("PrintWriter Error is set", th);
    }

    @Override
    protected void setError()
    {
        setError(new IOException());
    }

    /**
     * Check to make sure that the stream has not been closed
     */
    private void isOpen() throws IOException
    {
        if (_ioException != null)
            throw _ioException;

        if (_isClosed)
        {
            _ioException = new EofException("Stream closed");
            throw _ioException;
        }
    }

    @Override
    public void flush()
    {
        try
        {
            synchronized (_lock)
            {
                isOpen();
                out.flush();
            }
        }
        catch (Throwable ex)
        {
            setError(ex);
        }
    }

    @Override
    public void close()
    {
        try
        {
            synchronized (_lock)
            {
                out.close();
                _isClosed = true;
            }
        }
        catch (IOException ex)
        {
            setError(ex);
        }
    }

    /**
     * Used to mark this writer as closed during any asynchronous completion operation.
     */
    void markAsClosed()
    {
        synchronized (_lock)
        {
            _isClosed = true;
        }
    }

    @Override
    public void write(int c)
    {
        try
        {
            synchronized (_lock)
            {
                isOpen();
                out.write(c);
            }
        }
        catch (InterruptedIOException ex)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Write interrupted", ex);
            Thread.currentThread().interrupt();
        }
        catch (IOException ex)
        {
            setError(ex);
        }
    }

    @Override
    public void write(char[] buf, int off, int len)
    {
        try
        {
            synchronized (_lock)
            {
                isOpen();
                out.write(buf, off, len);
            }
        }
        catch (InterruptedIOException ex)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Write interrupted", ex);
            Thread.currentThread().interrupt();
        }
        catch (IOException ex)
        {
            setError(ex);
        }
    }

    @Override
    public void write(char[] buf)
    {
        this.write(buf, 0, buf.length);
    }

    @Override
    public void write(String s, int off, int len)
    {
        try
        {
            synchronized (_lock)
            {
                isOpen();
                out.write(s, off, len);
            }
        }
        catch (InterruptedIOException ex)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Write interrupted", ex);
            Thread.currentThread().interrupt();
        }
        catch (IOException ex)
        {
            setError(ex);
        }
    }

    @Override
    public void write(String s)
    {
        this.write(s, 0, s.length());
    }

    @Override
    public void print(boolean b)
    {
        this.write(b ? "true" : "false");
    }

    @Override
    public void print(char c)
    {
        this.write(c);
    }

    @Override
    public void print(int i)
    {
        this.write(String.valueOf(i));
    }

    @Override
    public void print(long l)
    {
        this.write(String.valueOf(l));
    }

    @Override
    public void print(float f)
    {
        this.write(String.valueOf(f));
    }

    @Override
    public void print(double d)
    {
        this.write(String.valueOf(d));
    }

    @Override
    public void print(char[] s)
    {
        this.write(s);
    }

    @Override
    public void print(String s)
    {
        if (s == null)
            s = "null";
        this.write(s);
    }

    @Override
    public void print(Object obj)
    {
        this.write(String.valueOf(obj));
    }

    @Override
    public void println()
    {
        try
        {
            synchronized (_lock)
            {
                isOpen();
                out.write(System.lineSeparator());
            }
        }
        catch (InterruptedIOException ex)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("write interrupted", ex);
            Thread.currentThread().interrupt();
        }
        catch (IOException ex)
        {
            setError(ex);
        }
    }

    @Override
    public void println(boolean b)
    {
        println(Boolean.toString(b));
    }

    @Override
    public void println(char c)
    {
        try
        {
            synchronized (_lock)
            {
                isOpen();
                out.write(c);
                out.write(System.lineSeparator());
            }
        }
        catch (InterruptedIOException ex)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Write interrupted", ex);
            Thread.currentThread().interrupt();
        }
        catch (IOException ex)
        {
            setError(ex);
        }
    }

    @Override
    public void println(int x)
    {
        this.println(String.valueOf(x));
    }

    @Override
    public void println(long x)
    {
        this.println(String.valueOf(x));
    }

    @Override
    public void println(float x)
    {
        this.println(String.valueOf(x));
    }

    @Override
    public void println(double x)
    {
        this.println(String.valueOf(x));
    }

    @Override
    public void println(char[] s)
    {
        try
        {
            synchronized (_lock)
            {
                isOpen();
                out.write(s, 0, s.length);
                out.write(System.lineSeparator());
            }
        }
        catch (InterruptedIOException ex)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Write interrupted", ex);
            Thread.currentThread().interrupt();
        }
        catch (IOException ex)
        {
            setError(ex);
        }
    }

    @Override
    public void println(String s)
    {
        if (s == null)
            s = "null";

        try
        {
            synchronized (_lock)
            {
                isOpen();
                out.write(s, 0, s.length());
                out.write(System.lineSeparator());
            }
        }
        catch (InterruptedIOException ex)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Write interrupted", ex);
            Thread.currentThread().interrupt();
        }
        catch (IOException ex)
        {
            setError(ex);
        }
    }

    @Override
    public void println(Object x)
    {
        this.println(String.valueOf(x));
    }

    @Override
    public PrintWriter printf(String format, Object... args)
    {
        return format(_locale, format, args);
    }

    @Override
    public PrintWriter printf(Locale l, String format, Object... args)
    {
        return format(l, format, args);
    }

    @Override
    public PrintWriter format(String format, Object... args)
    {
        return format(_locale, format, args);
    }

    @Override
    public PrintWriter format(Locale locale, String format, Object... args)
    {
        try
        {
            
            /* If the passed locale is null then 
            use any locale set on the response as the default. */
            if (locale == null)
                locale = _locale;

            synchronized (_lock)
            {
                isOpen();

                if (_formatter == null)
                {
                    _formatter = new Formatter(this, locale);
                }
                else if (!_formatter.locale().equals(locale))
                {
                    _formatter = new Formatter(this, locale);
                }

                _formatter.format(locale, format, args);
            }
        }
        catch (InterruptedIOException ex)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("format interrupted", ex);
            Thread.currentThread().interrupt();
        }
        catch (IOException ex)
        {
            setError(ex);
        }
        return this;
    }
}
