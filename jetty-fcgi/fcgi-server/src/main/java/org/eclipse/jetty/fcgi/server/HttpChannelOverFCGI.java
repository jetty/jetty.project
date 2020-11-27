//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.fcgi.server;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpChannelOverFCGI extends HttpChannel
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpChannelOverFCGI.class);

    private final Queue<HttpInput.Content> _contentQueue = new LinkedList<>();
    private final AutoLock _lock = new AutoLock();
    private HttpInput.Content _specialContent;
    private final HttpFields.Mutable fields = HttpFields.build();
    private final Dispatcher dispatcher;
    private String method;
    private String path;
    private String query;
    private String version;
    private HostPortHttpField hostPort;

    public HttpChannelOverFCGI(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport)
    {
        super(connector, configuration, endPoint, transport);
        this.dispatcher = new Dispatcher(connector.getServer().getThreadPool(), this);
    }

    @Override
    public boolean onContent(HttpInput.Content content)
    {
        boolean b = super.onContent(content);

        Throwable failure;
        try (AutoLock l = _lock.lock())
        {
            failure = _specialContent == null ? null : _specialContent.getError();
            if (failure == null)
                _contentQueue.offer(content);
        }
        if (failure != null)
            content.failed(failure);

        return b;
    }

    @Override
    public boolean needContent()
    {
        try (AutoLock l = _lock.lock())
        {
            boolean hasContent = _specialContent != null || !_contentQueue.isEmpty();
            if (LOG.isDebugEnabled())
                LOG.debug("needContent has content? {}", hasContent);
            return hasContent;
        }
    }

    @Override
    public HttpInput.Content produceContent()
    {
        HttpInput.Content content;
        try (AutoLock l = _lock.lock())
        {
            content = _contentQueue.poll();
            if (content == null)
                content = _specialContent;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("produceContent has produced {}", content);
        return content;
    }

    @Override
    public boolean failAllContent(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failing all content with {} {}", failure, this);
        List<HttpInput.Content> copy;
        try (AutoLock l = _lock.lock())
        {
            copy = new ArrayList<>(_contentQueue);
            _contentQueue.clear();
        }
        copy.forEach(c -> c.failed(failure));
        boolean atEof;
        try (AutoLock l = _lock.lock())
        {
            atEof = _specialContent != null && _specialContent.isEof();
        }
        if (LOG.isDebugEnabled())
            LOG.debug("failed all content, EOF = {}", atEof);
        return atEof;
    }

    @Override
    public boolean failed(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failed " + x);

        try (AutoLock l = _lock.lock())
        {
            Throwable error = _specialContent == null ? null : _specialContent.getError();

            if (error != null && error != x)
                error.addSuppressed(x);
            else
                _specialContent = new HttpInput.ErrorContent(x);
        }

        return getRequest().getHttpInput().onContentProducible();
    }

    @Override
    protected boolean eof()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("received EOF");
        try (AutoLock l = _lock.lock())
        {
            _specialContent = new HttpInput.EofContent();
        }
        return getRequest().getHttpInput().onContentProducible();
    }

    protected void header(HttpField field)
    {
        String name = field.getName();
        String value = field.getValue();
        getRequest().setAttribute(name, value);
        if (FCGI.Headers.REQUEST_METHOD.equalsIgnoreCase(name))
            method = value;
        else if (FCGI.Headers.DOCUMENT_URI.equalsIgnoreCase(name))
            path = value;
        else if (FCGI.Headers.QUERY_STRING.equalsIgnoreCase(name))
            query = value;
        else if (FCGI.Headers.SERVER_PROTOCOL.equalsIgnoreCase(name))
            version = value;
        else
            processField(field);
    }

    private void processField(HttpField field)
    {
        HttpField httpField = convertHeader(field);
        if (httpField != null)
        {
            fields.add(httpField);
            if (HttpHeader.HOST.is(httpField.getName()))
                hostPort = (HostPortHttpField)httpField;
        }
    }

    public void onRequest()
    {
        String uri = path;
        if (!StringUtil.isEmpty(query))
            uri += "?" + query;
        // TODO https?
        onRequest(new MetaData.Request(method, HttpScheme.HTTP.asString(), hostPort, uri, HttpVersion.fromString(version), fields, Long.MIN_VALUE));
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

    protected void dispatch()
    {
        dispatcher.dispatch();
    }

    public boolean onIdleTimeout(Throwable timeout)
    {
        boolean handle = doOnIdleTimeout(timeout);
        if (handle)
            execute(this);
        return !handle;
    }

    private boolean doOnIdleTimeout(Throwable x)
    {
        boolean neverDispatched = getState().isIdle();
        boolean waitingForContent;
        HttpInput.Content specialContent;
        try (AutoLock l = _lock.lock())
        {
            waitingForContent = _contentQueue.isEmpty() || _contentQueue.peek().remaining() == 0;
            specialContent = _specialContent;
        }
        if ((waitingForContent || neverDispatched) && specialContent == null)
        {
            x.addSuppressed(new Throwable("HttpInput idle timeout"));
            try (AutoLock l = _lock.lock())
            {
                _specialContent = new HttpInput.ErrorContent(x);
            }
            return getRequest().getHttpInput().onContentProducible();
        }
        return false;
    }

    @Override
    public void recycle()
    {
        try (AutoLock l = _lock.lock())
        {
            if (!_contentQueue.isEmpty())
                throw new AssertionError("unconsumed content: " + _contentQueue);
            _specialContent = null;
        }
        super.recycle();
    }

    private static class Dispatcher implements Runnable
    {
        private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
        private final Executor executor;
        private final Runnable runnable;

        private Dispatcher(Executor executor, Runnable runnable)
        {
            this.executor = executor;
            this.runnable = runnable;
        }

        public void dispatch()
        {
            while (true)
            {
                State current = state.get();
                if (LOG.isDebugEnabled())
                    LOG.debug("Dispatching, state={}", current);
                switch (current)
                {
                    case IDLE:
                    {
                        if (!state.compareAndSet(current, State.DISPATCH))
                            continue;
                        executor.execute(this);
                        return;
                    }
                    case DISPATCH:
                    case EXECUTE:
                    {
                        if (state.compareAndSet(current, State.SCHEDULE))
                            return;
                        continue;
                    }
                    case SCHEDULE:
                    {
                        return;
                    }
                    default:
                    {
                        throw new IllegalStateException();
                    }
                }
            }
        }

        @Override
        public void run()
        {
            while (true)
            {
                State current = state.get();
                if (LOG.isDebugEnabled())
                    LOG.debug("Running, state={}", current);
                switch (current)
                {
                    case DISPATCH:
                    {
                        if (state.compareAndSet(current, State.EXECUTE))
                            runnable.run();
                        continue;
                    }
                    case EXECUTE:
                    {
                        if (state.compareAndSet(current, State.IDLE))
                            return;
                        continue;
                    }
                    case SCHEDULE:
                    {
                        if (state.compareAndSet(current, State.DISPATCH))
                            continue;
                        throw new IllegalStateException();
                    }
                    default:
                    {
                        throw new IllegalStateException();
                    }
                }
            }
        }

        private enum State
        {
            IDLE, DISPATCH, EXECUTE, SCHEDULE
        }
    }
}
