// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletOutputStream;

import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpGenerator.Action;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ByteArrayOutputStream2;

/** Output.
 * 
 * <p>
 * Implements  {@link javax.servlet.ServletOutputStream} from the <code>javax.servlet</code> package.   
 * </p>
 * A {@link ServletOutputStream} implementation that writes content
 * to a {@link AbstractGenerator}.   The class is designed to be reused
 * and can be reopened after a close.
 */
public class HttpOutput extends ServletOutputStream 
{
    protected final AbstractHttpConnection _connection;
    protected final HttpGenerator _generator;
    private boolean _closed;
    ByteBuffer header=null;
    ByteBuffer chunk=null;
    ByteBuffer buffer=null;
    
    // These are held here for reuse by Writer
    String _characterEncoding;
    Writer _converter;
    char[] _chars;
    ByteArrayOutputStream2 _bytes;

    /* ------------------------------------------------------------ */
    public HttpOutput(AbstractHttpConnection connection)
    {
        _connection=connection;
        _generator=(HttpGenerator)connection.getGenerator();
    }

    /* ------------------------------------------------------------ */
    public int getMaxIdleTime()
    {
        return _connection.getMaxIdleTime();
    }
    
    /* ------------------------------------------------------------ */
    public boolean isWritten()
    {
        return _generator.getContentWritten()>0;
    }
    
    /* ------------------------------------------------------------ */
    /*
     * @see java.io.OutputStream#close()
     */
    @Override
    public void close() throws IOException
    {
        _closed=true;
    }
    
    /* ------------------------------------------------------------ */
    public boolean isClosed()
    {
        return _closed;
    }
    
    /* ------------------------------------------------------------ */
    public void reopen()
    {
        _closed=false;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void flush() throws IOException
    {
        // TODO
    }

    /* ------------------------------------------------------------ */
    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        write(ByteBuffer.wrap(b,off,len));
    }

    /* ------------------------------------------------------------ */
    /*
     * @see java.io.OutputStream#write(byte[])
     */
    @Override
    public void write(byte[] b) throws IOException
    {
        write(ByteBuffer.wrap(b));
    }

    /* ------------------------------------------------------------ */
    /*
     * @see java.io.OutputStream#write(int)
     */
    @Override
    public void write(int b) throws IOException
    {
        write(ByteBuffer.wrap(new byte[]{(byte)b}));
    }

    /* ------------------------------------------------------------ */
    private void write(ByteBuffer content) throws IOException
    {
        if (_closed)
            throw new IOException("Closed");
        if (!_generator.isComplete())
            throw new EofException();

        try
        {
            while(BufferUtil.hasContent(content))
            {

                // Generate
                Action action=BufferUtil.hasContent(content)?null:Action.COMPLETE;

                /* System.err.printf("generate(%s,%s,%s,%s,%s)@%s%n",
                    BufferUtil.toSummaryString(header),
                    BufferUtil.toSummaryString(chunk),
                    BufferUtil.toSummaryString(buffer),
                    BufferUtil.toSummaryString(content),
                    action,gen.getState());*/
                HttpGenerator.Result result=_generator.generate(header,chunk,buffer,content,action);
                /*System.err.printf("%s (%s,%s,%s,%s,%s)@%s%n",
                    result,
                    BufferUtil.toSummaryString(header),
                    BufferUtil.toSummaryString(chunk),
                    BufferUtil.toSummaryString(buffer),
                    BufferUtil.toSummaryString(content),
                    action,gen.getState());*/

                switch(result)
                {
                    case NEED_HEADER:
                        header=BufferUtil.allocate(2048);
                        break;

                    case NEED_BUFFER:
                        buffer=BufferUtil.allocate(8192);
                        break;

                    case NEED_CHUNK:
                        header=null;
                        chunk=BufferUtil.allocate(HttpGenerator.CHUNK_SIZE);
                        break;

                    case FLUSH:
                    {
                        Future<Integer> future = _connection.getEndPoint().flush(header,chunk,buffer);
                        future.get(getMaxIdleTime(),TimeUnit.MILLISECONDS);
                        break;
                    }
                    case FLUSH_CONTENT:
                    {
                        Future<Integer> future = _connection.getEndPoint().flush(header,chunk,content);
                        future.get(getMaxIdleTime(),TimeUnit.MILLISECONDS);
                        break;
                    }
                    case OK:
                        break;
                    case SHUTDOWN_OUT:
                        _connection.getEndPoint().shutdownOutput();
                        break;
                }
            }
        }

        catch(final TimeoutException e)
        {
            throw new InterruptedIOException(e.toString())
            {
                {
                    this.initCause(e);
                }
            };
        }
        catch (final InterruptedException e)
        {
            throw new InterruptedIOException(e.toString())
            {
                {
                    this.initCause(e);
                }
            };
        }
        catch (final ExecutionException e)
        {
            throw new IOException(e.toString())
            {
                {
                    this.initCause(e);
                }
            };
        }
    }
    

    /* ------------------------------------------------------------ */
    /* 
     * @see javax.servlet.ServletOutputStream#print(java.lang.String)
     */
    @Override
    public void print(String s) throws IOException
    {
        write(s.getBytes());
    }
}
