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
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.util.Formatter;
import java.util.Locale;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Specialized PrintWriter for servlet Responses
 * <p>An instance of ResponseWriter is the {@link PrintWriter} subclass returned by {@link Response#getWriter()}.
 * It differs from the standard {@link PrintWriter} in that:<ul>
 * <li>It does not support autoflush</li>
 * <li>The default Locale for {@link #format(String, Object...)} is the locale obtained by {@link ServletResponse#getLocale()}</li>
 * <li>If a write or print method is called while {@link #checkError()}  returns true, then a {@link RuntimeIOException} is thrown to stop needless iterations.</li>
 * <li>The writer may be reopen to allow for recycling</li>
 * </ul>
 */
public class ResponseWriter extends PrintWriter
{
    private static final Logger LOG = Log.getLogger(ResponseWriter.class);
    private static final String __lineSeparator = System.getProperty("line.separator");
    private static final String __trueln = "true" + __lineSeparator;
    private static final String __falseln = "false" + __lineSeparator;

    private final HttpWriter _httpWriter;
    private final Locale _locale;
    private final String _encoding;
    private IOException _ioException;
    private boolean _isClosed = false;
    private Formatter _formatter;

    public ResponseWriter(HttpWriter httpWriter, Locale locale, String encoding)
    {
        super(httpWriter, false);
        _httpWriter = httpWriter;
        _locale = locale;
        _encoding = encoding;
    }

    public boolean isFor(Locale locale, String encoding)
    {
        if (_locale == null && locale != null)
            return false;
        if (_encoding == null && encoding != null)
            return false;
        return _encoding.equalsIgnoreCase(encoding) && _locale.equals(locale);
    }

    protected void reopen()
    {
        synchronized (lock)
        {
            _isClosed = false;
            clearError();
            out = _httpWriter;
        }
    }

    @Override
    protected void clearError()
    {
        synchronized (lock)
        {
            _ioException = null;
            super.clearError();
        }
    }

    @Override
    public boolean checkError()
    {
        synchronized (lock)
        {
            return _ioException != null || super.checkError();
        }
    }

    private void setError(Throwable th)
    {
        super.setError();

        if (th instanceof IOException)
            _ioException = (IOException)th;
        else
        {
            _ioException = new IOException(String.valueOf(th));
            _ioException.initCause(th);
        }

        if (LOG.isDebugEnabled())
            LOG.debug(th);
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
            synchronized (lock)
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
            synchronized (lock)
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

    public void complete(Callback callback)
    {
        synchronized (lock)
        {
            _isClosed = true;
        }
        _httpWriter.complete(callback);
    }

    @Override
    public void write(int c)
    {
        try
        {
            synchronized (lock)
            {
                isOpen();
                out.write(c);
            }
        }
        catch (InterruptedIOException ex)
        {
            LOG.debug(ex);
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
            synchronized (lock)
            {
                isOpen();
                out.write(buf, off, len);
            }
        }
        catch (InterruptedIOException ex)
        {
            LOG.debug(ex);
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
            synchronized (lock)
            {
                isOpen();
                out.write(s, off, len);
            }
        }
        catch (InterruptedIOException ex)
        {
            LOG.debug(ex);
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
            synchronized (lock)
            {
                isOpen();
                out.write(__lineSeparator);
            }
        }
        catch (InterruptedIOException ex)
        {
            LOG.debug(ex);
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
        println(b ? __trueln : __falseln);
    }

    @Override
    public void println(char c)
    {
        try
        {
            synchronized (lock)
            {
                isOpen();
                out.write(c);
            }
        }
        catch (InterruptedIOException ex)
        {
            LOG.debug(ex);
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
            synchronized (lock)
            {
                isOpen();
                out.write(s, 0, s.length);
                out.write(__lineSeparator);
            }
        }
        catch (InterruptedIOException ex)
        {
            LOG.debug(ex);
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
            synchronized (lock)
            {
                isOpen();
                out.write(s, 0, s.length());
                out.write(__lineSeparator);
            }
        }
        catch (InterruptedIOException ex)
        {
            LOG.debug(ex);
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

            synchronized (lock)
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
            LOG.debug(ex);
            Thread.currentThread().interrupt();
        }
        catch (IOException ex)
        {
            setError(ex);
        }
        return this;
    }
}
