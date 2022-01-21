//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.fcgi.server;

import java.util.Locale;
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
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpChannelOverFCGI extends HttpChannel
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpChannelOverFCGI.class);
    private static final HttpInput.Content EOF_CONTENT = new HttpInput.EofContent();

    private final Callback asyncFillCallback = new AsyncFillCallback();
    private final ServerFCGIConnection connection;
    private final HttpFields.Mutable fields = HttpFields.build();
    private final Dispatcher dispatcher;
    private HttpInput.Content normalContent;
    private HttpInput.Content specialContent;
    private String method;
    private String path;
    private String query;
    private String version;
    private HostPortHttpField hostPort;

    public HttpChannelOverFCGI(ServerFCGIConnection connection, Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport)
    {
        super(connector, configuration, endPoint, transport);
        this.connection = connection;
        this.dispatcher = new Dispatcher(connector.getServer().getThreadPool(), this);
    }

    @Override
    public boolean onContent(HttpInput.Content content)
    {
        boolean result = super.onContent(content);

        HttpInput.Content special = this.specialContent;
        Throwable failure = special == null ? null : special.getError();
        if (failure == null)
        {
            if (normalContent != null)
                throw new IllegalStateException("onContent has unconsumed content");
            normalContent = content;
        }
        else
        {
            content.failed(failure);
        }

        return result;
    }

    @Override
    public boolean needContent()
    {
        if (hasContent())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("needContent has immediate content {}", this);
            return true;
        }

        parseAndFill();

        if (hasContent())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("needContent has parsed content {}", this);
            return true;
        }

        connection.getEndPoint().tryFillInterested(asyncFillCallback);
        return false;
    }

    private boolean hasContent()
    {
        return specialContent != null || normalContent != null;
    }

    @Override
    public HttpInput.Content produceContent()
    {
        if (!hasContent())
            parseAndFill();

        if (!hasContent())
            return null;

        HttpInput.Content content = normalContent;
        if (content != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("produceContent produced {} {}", content, this);
            normalContent = null;
            return content;
        }
        content = specialContent;
        if (LOG.isDebugEnabled())
            LOG.debug("produceContent produced special {} {}", content, this);
        return content;
    }

    private void parseAndFill()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("parseAndFill {}", this);
        connection.parseAndFill();
    }

    @Override
    public boolean failAllContent(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failing all content {}", this);
        HttpInput.Content normal = normalContent;
        if (normal != null)
            normal.failed(failure);
        HttpInput.Content special = specialContent;
        if (special != null)
            return special.isEof();
        while (true)
        {
            HttpInput.Content content = produceContent();
            if (content == null)
                return false;
            special = specialContent;
            if (special != null)
                return special.isEof();
            content.failed(failure);
        }
    }

    @Override
    public boolean failed(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failed {}", this, x);

        HttpInput.Content special = specialContent;
        Throwable error = special == null ? null : special.getError();
        if (error != null && error != x)
            error.addSuppressed(x);
        else
            specialContent = new HttpInput.ErrorContent(x);

        return getRequest().getHttpInput().onContentProducible();
    }

    @Override
    protected boolean eof()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("received EOF");
        specialContent = EOF_CONTENT;
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
        HttpInput.Content normal = this.normalContent;
        boolean waitingForContent = normal == null || normal.remaining() == 0;
        if ((waitingForContent || neverDispatched) && specialContent == null)
        {
            x.addSuppressed(new Throwable("HttpInput idle timeout"));
            specialContent = new HttpInput.ErrorContent(x);
            return getRequest().getHttpInput().onContentProducible();
        }
        return false;
    }

    @Override
    public void recycle()
    {
        super.recycle();
        HttpInput.Content normal = normalContent;
        if (normal != null)
            throw new AssertionError("unconsumed content: " + normal);
        specialContent = null;
    }

    @Override
    public void onCompleted()
    {
        super.onCompleted();
        HttpInput input = getRequest().getHttpInput();
        boolean consumed = input.consumeAll();
        // Assume we don't arrive here from the connection's onFillable() (which already
        // calls fillInterested()), because we dispatch() when all the headers are received.
        // When the request/response is completed, we must arrange to call fillInterested().
        connection.onCompleted(consumed);
    }

    private class AsyncFillCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            if (getRequest().getHttpInput().onContentProducible())
                handle();
        }

        @Override
        public void failed(Throwable x)
        {
            if (HttpChannelOverFCGI.this.failed(x))
                handle();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }
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
