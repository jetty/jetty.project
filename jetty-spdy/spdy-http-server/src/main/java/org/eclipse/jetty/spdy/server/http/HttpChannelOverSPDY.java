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

package org.eclipse.jetty.spdy.server.http;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpChannelOverSPDY extends HttpChannel<DataInfo>
{
    private static final Logger LOG = Log.getLogger(HttpChannelOverSPDY.class);

    private final Queue<Runnable> tasks = new LinkedList<>();
    private final Stream stream;
    private boolean dispatched; // Guarded by synchronization on tasks
    private boolean headersComplete;

    public HttpChannelOverSPDY(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport, HttpInputOverSPDY input, Stream stream)
    {
        super(connector, configuration, endPoint, transport, input);
        this.stream = stream;
    }

    @Override
    public boolean headerComplete()
    {
        headersComplete = true;
        return super.headerComplete();
    }

    private void post(Runnable task)
    {
        synchronized (tasks)
        {
            LOG.debug("Posting task {}", task);
            tasks.offer(task);
            dispatch();
        }
    }

    private void dispatch()
    {
        synchronized (tasks)
        {
            if (dispatched)
                return;

            final Runnable task = tasks.poll();
            if (task != null)
            {
                dispatched = true;
                LOG.debug("Dispatching task {}", task);
                execute(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        LOG.debug("Executing task {}", task);
                        task.run();
                        LOG.debug("Completing task {}", task);
                        dispatched = false;
                        dispatch();
                    }
                });
            }
        }
    }

    public void requestStart(final Fields headers, final boolean endRequest)
    {
        if (!headers.isEmpty())
            requestHeaders(headers, endRequest);
    }

    public void requestHeaders(Fields headers, boolean endRequest)
    {
        boolean proceed = performBeginRequest(headers);
        if (!proceed)
            return;

        performHeaders(headers);

        if (endRequest)
        {
            if (headerComplete())
                post(this);
            if (messageComplete())
                post(this);
        }
    }

    public void requestContent(final DataInfo dataInfo, boolean endRequest)
    {
        if (!headersComplete)
        {
            if (headerComplete())
                post(this);
        }

        LOG.debug("HTTP > {} bytes of content", dataInfo.length());

        // We need to copy the dataInfo since we do not know when its bytes
        // will be consumed. When the copy is consumed, we consume also the
        // original, so the implementation can send a window update.
        ByteBuffer copyByteBuffer = dataInfo.asByteBuffer(false);
        ByteBufferDataInfo copyDataInfo = new ByteBufferDataInfo(copyByteBuffer, dataInfo.isClose())
        {
            @Override
            public void consume(int delta)
            {
                super.consume(delta);
                dataInfo.consume(delta);
            }
        };
        LOG.debug("Queuing last={} content {}", endRequest, copyDataInfo);

        if (content(copyDataInfo))
            post(this);

        if (endRequest)
        {
            if (messageComplete())
                post(this);
        }
    }

    private boolean performBeginRequest(Fields headers)
    {
        short version = stream.getSession().getVersion();
        Fields.Field methodHeader = headers.get(HTTPSPDYHeader.METHOD.name(version));
        Fields.Field uriHeader = headers.get(HTTPSPDYHeader.URI.name(version));
        Fields.Field versionHeader = headers.get(HTTPSPDYHeader.VERSION.name(version));

        if (methodHeader == null || uriHeader == null || versionHeader == null)
        {
            badMessage(400, "Missing required request line elements");
            return false;
        }

        HttpMethod httpMethod = HttpMethod.fromString(methodHeader.value());
        HttpVersion httpVersion = HttpVersion.fromString(versionHeader.value());
        
        // TODO should handle URI as byte buffer as some bad clients send WRONG encodings in query string
        // that  we have to deal with
        ByteBuffer uri = BufferUtil.toBuffer(uriHeader.value());

        LOG.debug("HTTP > {} {} {}", httpMethod, uriHeader.value(), httpVersion);
        startRequest(httpMethod, httpMethod.asString(), uri, httpVersion);

        Fields.Field schemeHeader = headers.get(HTTPSPDYHeader.SCHEME.name(version));
        if (schemeHeader != null)
            getRequest().setScheme(schemeHeader.value());
        return true;
    }

    private void performHeaders(Fields headers)
    {
        for (Fields.Field header : headers)
        {
            String name = header.name();

            // Skip special SPDY headers, unless it's the "host" header
            HTTPSPDYHeader specialHeader = HTTPSPDYHeader.from(stream.getSession().getVersion(), name);
            if (specialHeader != null)
            {
                if (specialHeader == HTTPSPDYHeader.HOST)
                    name = "host";
                else
                    continue;
            }

            switch (name)
            {
                case "connection":
                case "keep-alive":
                case "proxy-connection":
                case "transfer-encoding":
                {
                    // Spec says to ignore these headers
                    continue;
                }
                default:
                {
                    // Spec says headers must be single valued
                    String value = header.value();
                    LOG.debug("HTTP > {}: {}", name, value);
                    parsedHeader(new HttpField(name,value));
                    break;
                }
            }
        }
    }
}
