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

package org.eclipse.jetty.fcgi.server;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpChannelOverFCGI extends HttpChannel<ByteBuffer>
{
    private static final Logger LOG = Log.getLogger(HttpChannelOverFCGI.class);

    private final List<HttpField> fields = new ArrayList<>();
    private final Dispatcher dispatcher;
    private String method;
    private String path;
    private String query;
    private String version;

    public HttpChannelOverFCGI(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport, HttpInput<ByteBuffer> input)
    {
        super(connector, configuration, endPoint, transport, input);
        this.dispatcher = new Dispatcher(connector.getExecutor(), this);
    }

    protected void header(HttpField field)
    {
        if (FCGI.Headers.REQUEST_METHOD.equalsIgnoreCase(field.getName()))
            method = field.getValue();
        else if (FCGI.Headers.DOCUMENT_URI.equalsIgnoreCase(field.getName()))
            path = field.getValue();
        else if (FCGI.Headers.QUERY_STRING.equalsIgnoreCase(field.getName()))
            query = field.getValue();
        else if (FCGI.Headers.SERVER_PROTOCOL.equalsIgnoreCase(field.getName()))
            version = field.getValue();
        else
            fields.add(field);
    }

    @Override
    public boolean headerComplete()
    {
        String uri = path;
        if (query != null && query.length() > 0)
            uri += "?" + query;
        startRequest(HttpMethod.fromString(method), method, ByteBuffer.wrap(uri.getBytes(StandardCharsets.UTF_8)),
                HttpVersion.fromString(version));

        for (HttpField fcgiField : fields)
        {
            HttpField httpField = convertHeader(fcgiField);
            if (httpField != null)
                parsedHeader(httpField);
        }

        return super.headerComplete();
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
            return new HttpField(httpName.toString(), field.getValue());
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
