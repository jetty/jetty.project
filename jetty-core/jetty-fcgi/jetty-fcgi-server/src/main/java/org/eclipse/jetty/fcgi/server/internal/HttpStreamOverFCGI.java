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

package org.eclipse.jetty.fcgi.server.internal;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.Flusher;
import org.eclipse.jetty.fcgi.generator.ServerGenerator;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpStreamOverFCGI implements HttpStream
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpStreamOverFCGI.class);

    private final Callback _demandCallback = new DemandCallback();
    private final HttpFields.Mutable _allHeaders = HttpFields.build();
    private final HttpFields.Mutable _headers = HttpFields.build();
    private final ServerFCGIConnection _connection;
    private final ServerGenerator _generator;
    private final HttpChannel _httpChannel;
    private final int _id;
    private String _method;
    private HostPortHttpField hostPort;
    private String _path;
    private String _query;
    private String _version;
    private String _secure;
    private Content.Chunk _chunk;
    private boolean _committed;
    private boolean _shutdown;
    private boolean _aborted;

    public HttpStreamOverFCGI(ServerFCGIConnection connection, ServerGenerator generator, HttpChannel httpChannel, int id)
    {
        _connection = connection;
        _generator = generator;
        _httpChannel = httpChannel;
        _id = id;
    }

    public HttpChannel getHttpChannel()
    {
        return _httpChannel;
    }

    @Override
    public String getId()
    {
        return String.valueOf(_id);
    }

    public void onHeader(HttpField field)
    {
        String name = field.getName();
        String value = field.getValue();
        _allHeaders.put(field);
        if (FCGI.Headers.REQUEST_METHOD.equalsIgnoreCase(name))
            _method = value;
        else if (FCGI.Headers.DOCUMENT_URI.equalsIgnoreCase(name))
            _path = value;
        else if (FCGI.Headers.QUERY_STRING.equalsIgnoreCase(name))
            _query = value;
        else if (FCGI.Headers.SERVER_PROTOCOL.equalsIgnoreCase(name))
            _version = value;
        else if (FCGI.Headers.HTTPS.equalsIgnoreCase(name))
            _secure = value;
        else
            processField(field);
    }

    public void onHeaders()
    {
        String pathQuery = URIUtil.addPathQuery(_path, _query);
        HttpScheme scheme = StringUtil.isEmpty(_secure) ? HttpScheme.HTTP : HttpScheme.HTTPS;
        MetaData.Request request = new MetaData.Request(_connection.getBeginNanoTime(), _method, scheme.asString(), hostPort, pathQuery, HttpVersion.fromString(_version), _headers, -1);
        Runnable task = _httpChannel.onRequest(request);
        _allHeaders.forEach(field -> _httpChannel.getRequest().setAttribute(field.getName(), field.getValue()));
        // TODO: here we just execute the task.
        //  However, we should really return all the way back to onFillable()
        //  and feed the Runnable to an ExecutionStrategy.
        execute(task);
    }

    private void processField(HttpField field)
    {
        HttpField httpField = convertHeader(field);
        if (httpField != null)
        {
            _headers.add(httpField);
            if (HttpHeader.HOST.is(httpField.getName()))
                hostPort = (HostPortHttpField)httpField;
        }
    }

    private HttpField convertHeader(HttpField field)
    {
        String name = field.getName();
        if (name.startsWith("HTTP_"))
        {
            // Converts e.g. "HTTP_ACCEPT_ENCODING" to "Accept-Encoding"
            String[] parts = name.split("_");
            StringBuilder httpName = new StringBuilder();
            for (int i = 1; i < parts.length; ++i)
            {
                if (i > 1)
                    httpName.append("-");
                String part = parts[i];
                httpName.append(Character.toUpperCase(part.charAt(0)));
                httpName.append(part.substring(1).toLowerCase(Locale.ENGLISH));
            }
            String headerName = httpName.toString();
            String value = field.getValue();
            if (HttpHeader.HOST.is(headerName))
                return new HostPortHttpField(value);
            else
                return new HttpField(headerName, value);
        }
        return null;
    }

    @Override
    public Content.Chunk read()
    {
        if (_chunk == null)
            _connection.process(false);

        Content.Chunk chunk = _chunk;
        _chunk = Content.Chunk.next(chunk);
        return chunk;
    }

    @Override
    public void demand()
    {
        if (_chunk != null)
            return;

        _connection.process(false);

        if (_chunk != null)
        {
            notifyContentAvailable();
            return;
        }

        _connection.tryFillInterested(_demandCallback);
    }

    private void notifyContentAvailable()
    {
        Runnable onContentAvailable = _httpChannel.onContentAvailable();
        if (onContentAvailable != null)
            onContentAvailable.run();
    }

    public void onContent(Content.Chunk chunk)
    {
        // Retain the chunk because it is stored for later reads.
        chunk.retain();
        _chunk = chunk;
    }

    public void onComplete()
    {
        if (_chunk == null)
            _chunk = Content.Chunk.EOF;
        else if (Content.Chunk.isFailure(_chunk, false))
            _chunk = Content.Chunk.from(_chunk.getFailure(), true);
        else if (!_chunk.isLast())
            throw new IllegalStateException();
    }

    public void onFailure(Throwable failure)
    {
        Runnable task = getHttpChannel().onFailure(failure);
        if (task != null)
            task.run();
    }

    @Override
    public void prepareResponse(HttpFields.Mutable headers)
    {
        // Nothing to do for FastCGI.
    }

    @Override
    public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer byteBuffer, Callback callback)
    {
        ReadableBuffer content = ReadableBuffer.wrap(byteBuffer);

        if (LOG.isDebugEnabled())
            LOG.debug("send {} l={} {} {}", request, last, content, this);
        boolean head = HttpMethod.HEAD.is(request.getMethod());
        if (response != null)
        {
            commit(response, head, last, content, callback);
        }
        else
        {
            Flusher flusher = _connection.getFlusher();
            if (head)
            {
                if (last)
                {
                    List<ReadableBuffer> accumulator = new ArrayList<>();
                    generateResponseContent(accumulator, true, ReadableBuffer.EMPTY);
                    ReadableBuffer buffer = ReadableBuffer.accumulate(accumulator);
                    accumulator.forEach(ReadableBuffer::release);
                    flusher.flush(buffer, callback);
                    buffer.release();
                }
                else
                {
                    // Skip content generation
                    callback.succeeded();
                }
            }
            else
            {
                List<ReadableBuffer> accumulator = new ArrayList<>();
                generateResponseContent(accumulator, last, content);
                ReadableBuffer buffer = ReadableBuffer.accumulate(accumulator);
                accumulator.forEach(ReadableBuffer::release);
                flusher.flush(buffer, callback);
                buffer.release();
            }

            if (last && _shutdown)
                flusher.shutdown();
        }
    }

    @Override
    public Runnable cancelSend(Throwable cause, Callback appCallback)
    {
        return () -> Callback.combine(_connection.getFlusher().cancel(cause), appCallback).failed(cause);
    }

    private void commit(MetaData.Response info, boolean head, boolean last, ReadableBuffer content, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("commit {} {} l={}", this, info, last);

        _committed = true;

        boolean shutdown = _shutdown = info.getHttpFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());

        Flusher flusher = _connection.getFlusher();
        if (head)
        {
            if (last)
            {
                List<ReadableBuffer> accumulator = new ArrayList<>();
                generateResponseHeaders(accumulator, info);
                generateResponseContent(accumulator, true, ReadableBuffer.EMPTY);
                ReadableBuffer buffer = ReadableBuffer.accumulate(accumulator);
                accumulator.forEach(ReadableBuffer::release);
                flusher.flush(buffer, callback);
                buffer.release();
            }
            else
            {
                List<ReadableBuffer> accumulator = new ArrayList<>();
                generateResponseHeaders(accumulator, info);
                ReadableBuffer buffer = ReadableBuffer.accumulate(accumulator);
                accumulator.forEach(ReadableBuffer::release);
                flusher.flush(buffer, callback);
                buffer.release();
            }
        }
        else
        {
            List<ReadableBuffer> accumulator = new ArrayList<>();
            generateResponseHeaders(accumulator, info);
            generateResponseContent(accumulator, last, content);
            ReadableBuffer buffer = ReadableBuffer.accumulate(accumulator);
            accumulator.forEach(ReadableBuffer::release);
            flusher.flush(buffer, callback);
            buffer.release();
        }

        if (last && shutdown)
            flusher.shutdown();
    }

    private void generateResponseHeaders(List<ReadableBuffer> accumulator, MetaData.Response info)
    {
        _generator.generateResponseHeaders(accumulator, _id, info.getStatus(), info.getReason(), info.getHttpFields());
    }

    private void generateResponseContent(List<ReadableBuffer> accumulator, boolean last, ReadableBuffer buffer)
    {
        _generator.generateResponseContent(accumulator, _id, buffer, last, _aborted);
    }

    @Override
    public long getIdleTimeout()
    {
        return _connection.getEndPoint().getIdleTimeout();
    }

    @Override
    public void setIdleTimeout(long idleTimeoutMs)
    {
        _connection.getEndPoint().setIdleTimeout(idleTimeoutMs);
    }

    @Override
    public boolean isCommitted()
    {
        return _committed;
    }

    @Override
    public Throwable consumeAvailable()
    {
        Throwable result = HttpStream.consumeAvailable(this, _httpChannel.getConnectionMetaData().getHttpConfiguration());
        if (result != null)
        {
            if (_chunk != null)
                _chunk.release();
            _chunk = Content.Chunk.from(result, true);
        }
        return result;
    }

    @Override
    public void succeeded()
    {
        _httpChannel.recycle();
        _connection.onCompleted(null);
    }

    @Override
    public void failed(Throwable x)
    {
        // TODO: should we do more?
        _aborted = true;
        _connection.onCompleted(x);
    }

    public boolean onIdleTimeout(TimeoutException timeout)
    {
        HttpChannel.IdleTimeoutTask task = _httpChannel.onIdleTimeout(timeout);
        boolean handlingRequest = task.handlingRequest();
        if (handlingRequest)
            ThreadPool.executeImmediately(_connection.getConnector().getExecutor(), task.action());
        return !handlingRequest;
    }

    private void execute(Runnable task)
    {
        _connection.getConnector().getExecutor().execute(task);
    }

    private class DemandCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            notifyContentAvailable();
        }

        @Override
        public void failed(Throwable x)
        {
            ThreadPool.executeImmediately(_connection.getConnector().getExecutor(), _httpChannel.onFailure(x));
        }

        @Override
        public InvocationType getInvocationType()
        {
            return Invocable.getInvocationType(_httpChannel);
        }
    }

    @Override
    public String toString()
    {
        return "%s@%x".formatted(TypeUtil.toShortName(getClass()), hashCode());
    }
}
