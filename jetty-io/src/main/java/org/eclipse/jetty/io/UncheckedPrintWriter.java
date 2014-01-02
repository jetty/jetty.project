//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/**
 * A wrapper for the {@link java.io.PrintWriter} that re-throws the instances of
 * {@link java.io.IOException} thrown by the underlying implementation of
 * {@link java.io.Writer} as {@link RuntimeIOException} instances.
 */
public class UncheckedPrintWriter extends PrintWriter
{
    private static final Logger LOG = Log.getLogger(UncheckedPrintWriter.class);

    private boolean _autoFlush = false;
    private IOException _ioException;
    private boolean _isClosed = false;

    /* ------------------------------------------------------------ */
    /**
     * Line separator string. This is the value of the line.separator property
     * at the moment that the stream was created.
     */
    private String _lineSeparator;

    public UncheckedPrintWriter(Writer out)
    {
        this(out,false);
    }

    /* ------------------------------------------------------------ */
    /**
     * Create a new PrintWriter.
     * 
     * @param out
     *            A character-output stream
     * @param autoFlush
     *            A boolean; if true, the println() methods will flush the
     *            output buffer
     */
    public UncheckedPrintWriter(Writer out, boolean autoFlush)
    {
        super(out,autoFlush);
        this._autoFlush = autoFlush;
        this._lineSeparator = System.getProperty("line.separator");
    }

    /* ------------------------------------------------------------ */
    /**
     * Create a new PrintWriter, without automatic line flushing, from an
     * existing OutputStream. This convenience constructor creates the necessary
     * intermediate OutputStreamWriter, which will convert characters into bytes
     * using the default character encoding.
     * 
     * @param out
     *            An output stream
     * 
     * @see java.io.OutputStreamWriter#OutputStreamWriter(java.io.OutputStream)
     */
    public UncheckedPrintWriter(OutputStream out)
    {
        this(out,false);
    }

    /* ------------------------------------------------------------ */
    /**
     * Create a new PrintWriter from an existing OutputStream. This convenience
     * constructor creates the necessary intermediate OutputStreamWriter, which
     * will convert characters into bytes using the default character encoding.
     * 
     * @param out
     *            An output stream
     * @param autoFlush
     *            A boolean; if true, the println() methods will flush the
     *            output buffer
     * 
     * @see java.io.OutputStreamWriter#OutputStreamWriter(java.io.OutputStream)
     */
    public UncheckedPrintWriter(OutputStream out, boolean autoFlush)
    {
        this(new BufferedWriter(new OutputStreamWriter(out)),autoFlush);
    }
    
    
    /* ------------------------------------------------------------ */
    public boolean checkError()
    {
        return _ioException!=null || super.checkError();
    }
    
    /* ------------------------------------------------------------ */
    private void setError(Throwable th)
    {
      
        super.setError();

        if (th instanceof IOException)
            _ioException=(IOException)th;
        else
        {
            _ioException=new IOException(String.valueOf(th));
            _ioException.initCause(th);
        }

        LOG.debug(th);
    }


    @Override
    protected void setError()
    {
        setError(new IOException());
    }

    /* ------------------------------------------------------------ */
    /** Check to make sure that the stream has not been closed */
    private void isOpen() throws IOException
    {       
        if (_ioException!=null)
            throw new RuntimeIOException(_ioException); 
        
        if (_isClosed)
            throw new IOException("Stream closed");
    }

    /* ------------------------------------------------------------ */
    /**
     * Flush the stream.
     */
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
        catch (IOException ex)
        {
            setError(ex);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Close the stream.
     */
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

    /* ------------------------------------------------------------ */
    /**
     * Write a single character.
     * 
     * @param c
     *            int specifying a character to be written.
     */
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
        catch (InterruptedIOException x)
        {
            Thread.currentThread().interrupt();
        }
        catch (IOException ex)
        {
            setError(ex);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Write a portion of an array of characters.
     * 
     * @param buf
     *            Array of characters
     * @param off
     *            Offset from which to start writing characters
     * @param len
     *            Number of characters to write
     */
    @Override
    public void write(char buf[], int off, int len)
    {
        try
        {
            synchronized (lock)
            {
                isOpen();
                out.write(buf,off,len);
            }
        }
        catch (InterruptedIOException x)
        {
            Thread.currentThread().interrupt();
        }
        catch (IOException ex)
        {
            setError(ex);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Write an array of characters. This method cannot be inherited from the
     * Writer class because it must suppress I/O exceptions.
     * 
     * @param buf
     *            Array of characters to be written
     */
    @Override
    public void write(char buf[])
    { 
        this.write(buf,0,buf.length);
    }

    /* ------------------------------------------------------------ */
    /**
     * Write a portion of a string.
     * 
     * @param s
     *            A String
     * @param off
     *            Offset from which to start writing characters
     * @param len
     *            Number of characters to write
     */
    @Override
    public void write(String s, int off, int len)
    {
        try
        {
            synchronized (lock)
            {
                isOpen();
                out.write(s,off,len);
            }
        }
        catch (InterruptedIOException x)
        {
            Thread.currentThread().interrupt();
        }
        catch (IOException ex)
        {
            setError(ex);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Write a string. This method cannot be inherited from the Writer class
     * because it must suppress I/O exceptions.
     * 
     * @param s
     *            String to be written
     */
    @Override
    public void write(String s)
    {
        this.write(s,0,s.length());
    }

    private void newLine()
    {
        try
        {
            synchronized (lock)
            {
                isOpen();
                out.write(_lineSeparator);
                if (_autoFlush)
                    out.flush();
            }
        }
        catch (InterruptedIOException x)
        {
            Thread.currentThread().interrupt();
        }
        catch (IOException ex)
        {
            setError(ex);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Print a boolean value. The string produced by <code>{@link
     * java.lang.String#valueOf(boolean)}</code> is translated into bytes
     * according to the platform's default character encoding, and these bytes
     * are written in exactly the manner of the <code>{@link
     * #write(int)}</code> method.
     * 
     * @param b
     *            The <code>boolean</code> to be printed
     */
    @Override
    public void print(boolean b)
    {
        this.write(b?"true":"false");
    }

    /* ------------------------------------------------------------ */
    /**
     * Print a character. The character is translated into one or more bytes
     * according to the platform's default character encoding, and these bytes
     * are written in exactly the manner of the <code>{@link
     * #write(int)}</code> method.
     * 
     * @param c
     *            The <code>char</code> to be printed
     */
    @Override
    public void print(char c)
    {
        this.write(c);
    }

    /* ------------------------------------------------------------ */
    /**
     * Print an integer. The string produced by <code>{@link
     * java.lang.String#valueOf(int)}</code> is translated into bytes according
     * to the platform's default character encoding, and these bytes are written
     * in exactly the manner of the <code>{@link #write(int)}</code> method.
     * 
     * @param i
     *            The <code>int</code> to be printed
     * @see java.lang.Integer#toString(int)
     */
    @Override
    public void print(int i)
    {
        this.write(String.valueOf(i));
    }

    /* ------------------------------------------------------------ */
    /**
     * Print a long integer. The string produced by <code>{@link
     * java.lang.String#valueOf(long)}</code> is translated into bytes according
     * to the platform's default character encoding, and these bytes are written
     * in exactly the manner of the <code>{@link #write(int)}</code> method.
     * 
     * @param l
     *            The <code>long</code> to be printed
     * @see java.lang.Long#toString(long)
     */
    @Override
    public void print(long l)
    {
        this.write(String.valueOf(l));
    }

    /* ------------------------------------------------------------ */
    /**
     * Print a floating-point number. The string produced by <code>{@link
     * java.lang.String#valueOf(float)}</code> is translated into bytes
     * according to the platform's default character encoding, and these bytes
     * are written in exactly the manner of the <code>{@link #write(int)}</code>
     * method.
     * 
     * @param f
     *            The <code>float</code> to be printed
     * @see java.lang.Float#toString(float)
     */
    @Override
    public void print(float f)
    {
        this.write(String.valueOf(f));
    }

    /* ------------------------------------------------------------ */
    /**
     * Print a double-precision floating-point number. The string produced by
     * <code>{@link java.lang.String#valueOf(double)}</code> is translated into
     * bytes according to the platform's default character encoding, and these
     * bytes are written in exactly the manner of the <code>{@link
     * #write(int)}</code> method.
     * 
     * @param d
     *            The <code>double</code> to be printed
     * @see java.lang.Double#toString(double)
     */
    @Override
    public void print(double d)
    {
        this.write(String.valueOf(d));
    }

    /* ------------------------------------------------------------ */
    /**
     * Print an array of characters. The characters are converted into bytes
     * according to the platform's default character encoding, and these bytes
     * are written in exactly the manner of the <code>{@link #write(int)}</code>
     * method.
     * 
     * @param s
     *            The array of chars to be printed
     * 
     * @throws NullPointerException
     *             If <code>s</code> is <code>null</code>
     */
    @Override
    public void print(char s[])
    {
        this.write(s);
    }

    /* ------------------------------------------------------------ */
    /**
     * Print a string. If the argument is <code>null</code> then the string
     * <code>"null"</code> is printed. Otherwise, the string's characters are
     * converted into bytes according to the platform's default character
     * encoding, and these bytes are written in exactly the manner of the
     * <code>{@link #write(int)}</code> method.
     * 
     * @param s
     *            The <code>String</code> to be printed
     */
    @Override
    public void print(String s)
    {
        if (s == null)
        {
            s = "null";
        }
        this.write(s);
    }

    /* ------------------------------------------------------------ */
    /**
     * Print an object. The string produced by the <code>{@link
     * java.lang.String#valueOf(Object)}</code> method is translated into bytes
     * according to the platform's default character encoding, and these bytes
     * are written in exactly the manner of the <code>{@link #write(int)}</code>
     * method.
     * 
     * @param obj
     *            The <code>Object</code> to be printed
     * @see java.lang.Object#toString()
     */
    @Override
    public void print(Object obj)
    {
        this.write(String.valueOf(obj));
    }

    /* ------------------------------------------------------------ */
    /**
     * Terminate the current line by writing the line separator string. The line
     * separator string is defined by the system property
     * <code>line.separator</code>, and is not necessarily a single newline
     * character (<code>'\n'</code>).
     */
    @Override
    public void println()
    {
        this.newLine();
    }

    /* ------------------------------------------------------------ */
    /**
     * Print a boolean value and then terminate the line. This method behaves as
     * though it invokes <code>{@link #print(boolean)}</code> and then
     * <code>{@link #println()}</code>.
     * 
     * @param x
     *            the <code>boolean</code> value to be printed
     */
    @Override
    public void println(boolean x)
    {
        synchronized (lock)
        {
            this.print(x);
            this.println();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Print a character and then terminate the line. This method behaves as
     * though it invokes <code>{@link #print(char)}</code> and then <code>{@link
     * #println()}</code>.
     * 
     * @param x
     *            the <code>char</code> value to be printed
     */
    @Override
    public void println(char x)
    {
        synchronized (lock)
        {
            this.print(x);
            this.println();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Print an integer and then terminate the line. This method behaves as
     * though it invokes <code>{@link #print(int)}</code> and then <code>{@link
     * #println()}</code>.
     * 
     * @param x
     *            the <code>int</code> value to be printed
     */
    @Override
    public void println(int x)
    {
        synchronized (lock)
        {
            this.print(x);
            this.println();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Print a long integer and then terminate the line. This method behaves as
     * though it invokes <code>{@link #print(long)}</code> and then
     * <code>{@link #println()}</code>.
     * 
     * @param x
     *            the <code>long</code> value to be printed
     */
    @Override
    public void println(long x)
    {
        synchronized (lock)
        {
            this.print(x);
            this.println();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Print a floating-point number and then terminate the line. This method
     * behaves as though it invokes <code>{@link #print(float)}</code> and then
     * <code>{@link #println()}</code>.
     * 
     * @param x
     *            the <code>float</code> value to be printed
     */
    @Override
    public void println(float x)
    {
        synchronized (lock)
        {
            this.print(x);
            this.println();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Print a double-precision floating-point number and then terminate the
     * line. This method behaves as though it invokes <code>{@link
     * #print(double)}</code> and then <code>{@link #println()}</code>.
     * 
     * @param x
     *            the <code>double</code> value to be printed
     */
    /* ------------------------------------------------------------ */
    @Override
    public void println(double x)
    {
        synchronized (lock)
        {
            this.print(x);
            this.println();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Print an array of characters and then terminate the line. This method
     * behaves as though it invokes <code>{@link #print(char[])}</code> and then
     * <code>{@link #println()}</code>.
     * 
     * @param x
     *            the array of <code>char</code> values to be printed
     */
    @Override
    public void println(char x[])
    {
        synchronized (lock)
        {
            this.print(x);
            this.println();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Print a String and then terminate the line. This method behaves as though
     * it invokes <code>{@link #print(String)}</code> and then
     * <code>{@link #println()}</code>.
     * 
     * @param x
     *            the <code>String</code> value to be printed
     */
    @Override
    public void println(String x)
    {
        synchronized (lock)
        {
            this.print(x);
            this.println();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Print an Object and then terminate the line. This method behaves as
     * though it invokes <code>{@link #print(Object)}</code> and then
     * <code>{@link #println()}</code>.
     * 
     * @param x
     *            the <code>Object</code> value to be printed
     */
    @Override
    public void println(Object x)
    {
        synchronized (lock)
        {
            this.print(x);
            this.println();
        }
    }
}
