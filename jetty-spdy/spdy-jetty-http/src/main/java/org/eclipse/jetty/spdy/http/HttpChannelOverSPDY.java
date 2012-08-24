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

package org.eclipse.jetty.spdy.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpChannelOverSPDY extends HttpChannel
{
    private static final Logger LOG = Log.getLogger(HttpChannelOverSPDY.class);

    private final Queue<Runnable> tasks = new LinkedList<>();
    private final Stream stream;
    private Headers headers; // No need for volatile, guarded by state
    private DataInfo dataInfo; // No need for volatile, guarded by state
    private ByteBuffer buffer; // No need for volatile, guarded by state
    private volatile State state = State.INITIAL;
    private boolean dispatched; // Guarded by synchronization on tasks

    public HttpChannelOverSPDY(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport, HttpInputOverSPDY input, Stream stream)
    {
        super(connector, configuration, endPoint, transport, input);
        this.stream = stream;
    }

    private void handle()
    {
        switch (state)
        {
            case INITIAL:
            {
                break;
            }
            case REQUEST:
            {
                short version = stream.getSession().getVersion();
                Headers.Header methodHeader = headers.get(HTTPSPDYHeader.METHOD.name(version));
                Headers.Header uriHeader = headers.get(HTTPSPDYHeader.URI.name(version));
                Headers.Header versionHeader = headers.get(HTTPSPDYHeader.VERSION.name(version));

                if (methodHeader == null || uriHeader == null || versionHeader == null)
                {
                    badMessage(400, "Missing required request line elements");
                    break;
                }

                HttpMethod httpMethod = HttpMethod.fromString(methodHeader.value());
                HttpVersion httpVersion = HttpVersion.fromString(versionHeader.value());
                String uriString = uriHeader.value();

                LOG.debug("HTTP > {} {} {}", httpMethod, uriString, httpVersion);
                startRequest(httpMethod, httpMethod.asString(), uriString, httpVersion);

                Headers.Header schemeHeader = headers.get(HTTPSPDYHeader.SCHEME.name(version));
                if (schemeHeader != null)
                    getRequest().setScheme(schemeHeader.value());

                updateState(State.HEADERS);
                handle();
                break;
            }
            case HEADERS:
            {
                for (Headers.Header header : headers)
                {
                    String name = header.name();
                    HttpHeader httpHeader = HttpHeader.CACHE.get(name);

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
                            parsedHeader(httpHeader, name, value);
                            break;
                        }
                    }
                }
                break;
            }
            case HEADERS_COMPLETE:
            {
                if (headerComplete())
                    run();
                break;
            }
            case CONTENT:
            {
                run();
                break;
            }
            case FINAL:
            {
                if (messageComplete(0))
                    run();
                break;
            }
            case ASYNC:
            {
                // TODO:
//                handleRequest();
                break;
            }
            default:
            {
                throw new IllegalStateException();
            }
        }
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

    private void updateState(State newState)
    {
        LOG.debug("State update {} -> {}", state, newState);
        state = newState;
    }

    private void close(Stream stream)
    {
        stream.getSession().goAway();
    }

    public void beginRequest(final Headers headers, final boolean endRequest)
    {
        this.headers = headers.isEmpty() ? null : headers;
        post(new Runnable()
        {
            @Override
            public void run()
            {
                if (!headers.isEmpty())
                    updateState(State.REQUEST);
                handle();
                if (endRequest)
                    performEndRequest();
            }
        });
    }

    public void headers(Headers headers)
    {
        this.headers = headers;
        post(new Runnable()
        {
            @Override
            public void run()
            {
                updateState(state == State.INITIAL ? State.REQUEST : State.HEADERS);
                handle();
            }
        });
    }

    public void content(final DataInfo dataInfo, boolean endRequest)
    {
        // We need to copy the dataInfo since we do not know when its bytes
        // will be consumed. When the copy is consumed, we consume also the
        // original, so the implementation can send a window update.
        ByteBuffer copyByteBuffer = dataInfo.asByteBuffer(false);
        ByteBufferDataInfo copyDataInfo = new ByteBufferDataInfo(copyByteBuffer, dataInfo.isClose(), dataInfo.isCompress())
        {
            @Override
            public void consume(int delta)
            {
                super.consume(delta);
                dataInfo.consume(delta);
            }
        };
        LOG.debug("Queuing last={} content {}", endRequest, copyDataInfo);

        HttpInputOverSPDY input = (HttpInputOverSPDY)getRequest().getHttpInput();
        input.offer(copyDataInfo, endRequest);
        input.content(copyDataInfo);
        if (endRequest)
            input.shutdown();

        post(new Runnable()
        {
            @Override
            public void run()
            {
                LOG.debug("HTTP > {} bytes of content", dataInfo.length());
                if (state == State.HEADERS)
                {
                    updateState(State.HEADERS_COMPLETE);
                    handle();
                }
                updateState(State.CONTENT);
                handle();
            }
        });
    }

    public void endRequest()
    {
        post(new Runnable()
        {
            public void run()
            {
                performEndRequest();
            }
        });
    }

    private void performEndRequest()
    {
        if (state == State.HEADERS)
        {
            updateState(State.HEADERS_COMPLETE);
            handle();
        }
        updateState(State.FINAL);
        handle();
    }

    //        @Override
    protected int write(ByteBuffer content) throws IOException
    {
        LOG.debug("write");
        return 0;
    }

//        @Override
    protected void commitResponse(HttpGenerator.ResponseInfo info, ByteBuffer content) throws IOException
    {
        LOG.debug("commitResponse");
    }

//        @Override
    protected int getContentBufferSize()
    {
        LOG.debug("getContentBufferSize");
        return 0;
    }

//        @Override
    protected void increaseContentBufferSize(int size)
    {
        LOG.debug("increaseContentBufferSize");
    }

//        @Override
    protected void resetBuffer()
    {
        LOG.debug("resetBuffer");
    }

//        @Override
    protected void flushResponse() throws IOException
    {
        LOG.debug("flushResponse");
    }

//        @Override
    protected void completeResponse() throws IOException
    {
        LOG.debug("completeResponse");
//            commit();
        Response response = getResponse();
        Headers headers = new Headers();
        short version = stream.getSession().getVersion();
        headers.put(HTTPSPDYHeader.VERSION.name(version), HttpVersion.HTTP_1_1.asString());
        StringBuilder status = new StringBuilder().append(response.getStatus());
        String reason = response.getReason();
        if (reason != null)
            status.append(" ").append(reason.toString());
        headers.put(HTTPSPDYHeader.STATUS.name(version), status.toString());
        LOG.debug("HTTP < {} {}", HttpVersion.HTTP_1_1, status);

        HttpFields httpFields = response.getHttpFields();
        if (httpFields != null)
        {
            for (HttpFields.Field httpField : httpFields)
            {
                String name = httpField.getName().toLowerCase();
                String value = httpField.getValue();
                headers.put(name, value);
                LOG.debug("HTTP < {}: {}", name, value);
            }
        }

        // We have to query the HttpGenerator and its buffers to know
        // whether there is content buffered and update the generator state
//        reply(stream, new ReplyInfo(headers, response.getContentCount() < 1));

        //TODO: sent content
    }

//    @Override
    protected void completed()
    {
        LOG.debug("completed");
    }

    private enum State
    {
        INITIAL, REQUEST, HEADERS, HEADERS_COMPLETE, CONTENT, FINAL, ASYNC
    }
}
