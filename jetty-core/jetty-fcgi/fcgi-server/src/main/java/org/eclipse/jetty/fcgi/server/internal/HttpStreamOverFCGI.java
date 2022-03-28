//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Locale;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.Flusher;
import org.eclipse.jetty.fcgi.generator.Generator;
import org.eclipse.jetty.fcgi.generator.ServerGenerator;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpStreamOverFCGI implements HttpStream
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpStreamOverFCGI.class);

    private final Callback _demandCallback = new DemandCallback();
    private final HttpFields.Mutable _headers = HttpFields.build();
    private final ServerFCGIConnection _connection;
    private final ServerGenerator _generator;
    private final HttpChannelState _channel;
    private final int _id;
    private final long _nanoTime;
    private String _method;
    private HostPortHttpField hostPort;
    private String _path;
    private String _query;
    private String _version;
    private Content _content;
    private boolean _committed;
    private boolean _shutdown;
    private boolean _aborted;

    public HttpStreamOverFCGI(ServerFCGIConnection connection, ServerGenerator generator, HttpChannelState channel, int id)
    {
        _connection = connection;
        _generator = generator;
        _channel = channel;
        _id = id;
        _nanoTime = System.nanoTime();
    }

    public HttpChannelState getHttpChannel()
    {
        return _channel;
    }

    @Override
    public String getId()
    {
        return String.valueOf(_id);
    }

    @Override
    public long getNanoTimeStamp()
    {
        return _nanoTime;
    }

    public void onHeader(HttpField field)
    {
        String name = field.getName();
        String value = field.getValue();
        if (FCGI.Headers.REQUEST_METHOD.equalsIgnoreCase(name))
            _method = value;
        else if (FCGI.Headers.DOCUMENT_URI.equalsIgnoreCase(name))
            _path = value;
        else if (FCGI.Headers.QUERY_STRING.equalsIgnoreCase(name))
            _query = value;
        else if (FCGI.Headers.SERVER_PROTOCOL.equalsIgnoreCase(name))
            _version = value;
        else
            processField(field);
    }

    public void onHeaders()
    {
        String pathQuery = URIUtil.addPathQuery(_path, _query);
        // TODO https?
        MetaData.Request request = new MetaData.Request(_method, HttpScheme.HTTP.asString(), hostPort, pathQuery, HttpVersion.fromString(_version), _headers, Long.MIN_VALUE);
        Runnable task = _channel.onRequest(request);
        _headers.forEach(field -> _channel.getRequest().setAttribute(field.getName(), field.getValue()));
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
    public Content readContent()
    {
        if (_content == null)
            _connection.parseAndFill();
        Content content = _content;
        _content = Content.next(content);
        return content;
    }

    @Override
    public void demandContent()
    {
        if (_content != null)
            return;

        _connection.parseAndFill();

        if (_content != null)
        {
            notifyContentAvailable();
            return;
        }

        _connection.tryFillInterested(_demandCallback);
    }

    private void notifyContentAvailable()
    {
        Runnable onContentAvailable = _channel.onContentAvailable();
        if (onContentAvailable != null)
            onContentAvailable.run();
    }

    public void onContent(Content content)
    {
        _content = content;
    }

    public void onComplete()
    {
        _content = Content.last(_content);
    }

    @Override
    public void prepareResponse(HttpFields.Mutable headers)
    {
        // Nothing to do for FastCGI.
    }

    @Override
    public void send(MetaData.Request request, MetaData.Response response, boolean last, Callback callback, ByteBuffer... buffers)
    {
        if (buffers.length > 1)
            throw new IllegalStateException();
        ByteBuffer content = buffers.length == 0 ? BufferUtil.EMPTY_BUFFER : buffers[0];

        if (LOG.isDebugEnabled())
            LOG.debug("send {} {} l={}", this, request, last);
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
                    Generator.Result result = generateResponseContent(true, BufferUtil.EMPTY_BUFFER, callback);
                    flusher.flush(result);
                }
                else
                {
                    // Skip content generation
                    callback.succeeded();
                }
            }
            else
            {
                Generator.Result result = generateResponseContent(last, content, callback);
                flusher.flush(result);
            }

            if (last && _shutdown)
                flusher.shutdown();
        }
    }

    private void commit(MetaData.Response info, boolean head, boolean last, ByteBuffer content, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("commit {} {} l={}", this, info, last);

        _committed = true;

        boolean shutdown = _shutdown = info.getFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());

        Flusher flusher = _connection.getFlusher();
        if (head)
        {
            if (last)
            {
                Generator.Result headersResult = generateResponseHeaders(info, Callback.NOOP);
                Generator.Result contentResult = generateResponseContent(true, BufferUtil.EMPTY_BUFFER, callback);
                flusher.flush(headersResult, contentResult);
            }
            else
            {
                Generator.Result headersResult = generateResponseHeaders(info, callback);
                flusher.flush(headersResult);
            }
        }
        else
        {
            Generator.Result headersResult = generateResponseHeaders(info, Callback.NOOP);
            Generator.Result contentResult = generateResponseContent(last, content, callback);
            flusher.flush(headersResult, contentResult);
        }

        if (last && shutdown)
            flusher.shutdown();
    }

    private Generator.Result generateResponseHeaders(MetaData.Response info, Callback callback)
    {
        return _generator.generateResponseHeaders(_id, info.getStatus(), info.getReason(), info.getFields(), callback);
    }

    private Generator.Result generateResponseContent(boolean last, ByteBuffer buffer, Callback callback)
    {
        return _generator.generateResponseContent(_id, buffer, last, _aborted, callback);
    }

    @Override
    public boolean isPushSupported()
    {
        return false;
    }

    @Override
    public void push(MetaData.Request request)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isCommitted()
    {
        return _committed;
    }

    @Override
    public void succeeded()
    {
        _connection.onCompleted(null);
    }

    @Override
    public void failed(Throwable x)
    {
        // TODO: should we do more?
        _aborted = true;
        _connection.onCompleted(x);
    }

    @Override
    public boolean isComplete()
    {
        // TODO
        return false;
    }

    @Override
    public void setUpgradeConnection(Connection connection)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Connection upgrade()
    {
        return null;
    }

    public boolean onIdleTimeout(Throwable timeout)
    {
        Runnable task = _channel.onError(timeout);
        if (task != null)
            execute(task);
        return false;
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
            Runnable task = _channel.onError(x);
            if (task != null)
                _connection.getConnector().getExecutor().execute(task);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _channel.getOnContentAvailableInvocationType();
        }
    }
}
