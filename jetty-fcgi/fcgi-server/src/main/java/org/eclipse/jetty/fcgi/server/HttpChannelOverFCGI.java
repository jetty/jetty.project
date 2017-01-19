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
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpChannelOverFCGI extends HttpChannel
{
    private static final Logger LOG = Log.getLogger(HttpChannelOverFCGI.class);

    private final HttpFields fields = new HttpFields();
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
        if (query != null && query.length() > 0)
            uri += "?" + query;
        // TODO https?
        onRequest(new MetaData.Request(method, HttpScheme.HTTP.asString(), hostPort, uri, HttpVersion.fromString(version), fields,Long.MIN_VALUE));
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
