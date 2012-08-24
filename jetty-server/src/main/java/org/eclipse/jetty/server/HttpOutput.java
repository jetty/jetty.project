//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.resource.Resource;

/**
 * <p>{@link HttpOutput} implements {@link ServletOutputStream}
 * as required by the Servlet specification.</p>
 * <p>{@link HttpOutput} buffers content written by the application until a
 * further write will overflow the buffer, at which point it triggers a commit
 * of the response.</p>
 * <p>{@link HttpOutput} can be closed and reopened, to allow requests included
 * via {@link RequestDispatcher#include(ServletRequest, ServletResponse)} to
 * close the stream, to be reopened after the inclusion ends.</p>
 */
public class HttpOutput extends ServletOutputStream
{
    private final HttpChannel _channel;
    private boolean _closed;
    private long _written;
    private ByteBuffer _aggregate;
    private int _bufferSize;

    public HttpOutput(HttpChannel channel)
    {
        _channel = channel;
        _bufferSize = _channel.getHttpConfiguration().getResponseBufferSize();
    }

    public boolean isWritten()
    {
        return _written > 0;
    }

    public long getWritten()
    {
        return _written;
    }

    public void reset()
    {
        _written = 0;
        _closed = false;
    }

    public void reopen()
    {
        _closed = false;
    }

    @Override
    public void close() throws IOException
    {
        if (!_closed)
        {
            if (BufferUtil.hasContent(_aggregate))
                _channel.write(_aggregate, !_channel.getResponse().isIncluding());
            else
                _channel.write(BufferUtil.EMPTY_BUFFER, !_channel.getResponse().isIncluding());
        }
        _closed = true;
        if (_aggregate != null)
        {
            _channel.getConnector().getByteBufferPool().release(_aggregate);
            _aggregate = null;
        }
    }

    public boolean isClosed()
    {
        return _closed;
    }

    @Override
    public void flush() throws IOException
    {
        if (_closed)
            throw new EofException();

        if (BufferUtil.hasContent(_aggregate))
            _channel.write(_aggregate, false);
        else
            _channel.write(BufferUtil.EMPTY_BUFFER, false);
    }

    public boolean checkAllWritten()
    {
        return _channel.getResponse().checkAllContentWritten(_written);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        if (_closed)
            throw new EOFException();

        // Do we have an aggregate buffer already ?
        if (_aggregate == null)
        {
            // What size should the aggregate be ?
            int size = getBufferSize();

            // If this write would fill more than half the aggregate, just write it directly
            if (len > size / 2)
            {
                _channel.write(ByteBuffer.wrap(b, off, len), false);
                _written += len;
                return;
            }

            // Allocate an aggregate buffer
            _aggregate = _channel.getByteBufferPool().acquire(size, false);
        }

        // Do we have space to aggregate ?
        int space = BufferUtil.space(_aggregate);
        if (len > space)
        {
            // No space so write the aggregate out if it is not empty
            if (BufferUtil.hasContent(_aggregate))
            {
                _channel.write(_aggregate, false);
                space = BufferUtil.space(_aggregate);
            }
        }

        // Do we have space to aggregate now ?
        if (len > space)
        {
            // No space so write the content directly
            _channel.write(ByteBuffer.wrap(b, off, len), false);
            _written += len;
            return;
        }

        // Aggregate the content
        BufferUtil.append(_aggregate, b, off, len);
        _written += len;

        // Check if all written or full
        if (!checkAllWritten() && BufferUtil.isFull(_aggregate))
        {
            _channel.write(_aggregate, false);
        }
    }

    @Override
    public void write(int b) throws IOException
    {
        if (_closed)
            throw new EOFException();

        if (_aggregate == null)
            _aggregate = _channel.getByteBufferPool().acquire(getBufferSize(), false);

        BufferUtil.append(_aggregate, (byte)b);
        _written++;

        // Check if all written or full
        if (!checkAllWritten() && BufferUtil.isFull(_aggregate))
            _channel.write(_aggregate, false);
    }

    @Override
    public void print(String s) throws IOException
    {
        if (isClosed())
            throw new IOException("Closed");
        write(s.getBytes(_channel.getResponse().getCharacterEncoding()));
    }

    public void sendContent(Object content) throws IOException
    {
        if (isClosed())
            throw new IOException("Closed");

        if (content instanceof HttpContent)
        {
            HttpContent httpContent = (HttpContent)content;
            Response response = _channel.getResponse();
            String contentType = httpContent.getContentType();
            if (contentType != null)
                response.getHttpFields().put(HttpHeader.CONTENT_TYPE, contentType);

            if (httpContent.getContentLength() > 0)
                response.getHttpFields().putLongField(HttpHeader.CONTENT_LENGTH, httpContent.getContentLength());

            String lm = httpContent.getLastModified();
            if (lm != null)
                response.getHttpFields().put(HttpHeader.LAST_MODIFIED, lm);
            else if (httpContent.getResource() != null)
            {
                long lml = httpContent.getResource().lastModified();
                if (lml != -1)
                    response.getHttpFields().putDateField(HttpHeader.LAST_MODIFIED, lml);
            }

            content = httpContent.getDirectBuffer();
            if (content == null)
                content = httpContent.getIndirectBuffer();
            if (content == null)
                content = httpContent.getInputStream();
        }
        else if (content instanceof Resource)
        {
            Resource resource = (Resource)content;
            _channel.getResponse().getHttpFields().putDateField(HttpHeader.LAST_MODIFIED, resource.lastModified());
            content = resource.getInputStream();
        }

        // Process content.
        if (content instanceof ByteBuffer)
        {
            _channel.write((ByteBuffer)content, true); // TODO: we have written all content ?
        }
        else if (content instanceof InputStream)
        {
            throw new IllegalArgumentException("not implemented!");
        }
        else
            throw new IllegalArgumentException("unknown content type?");
    }

    public int getBufferSize()
    {
        return _bufferSize;
    }

    public void setBufferSize(int size)
    {
        this._bufferSize = size;
    }

    public void resetBuffer()
    {
        if (BufferUtil.hasContent(_aggregate))
            BufferUtil.clear(_aggregate);
    }
}
