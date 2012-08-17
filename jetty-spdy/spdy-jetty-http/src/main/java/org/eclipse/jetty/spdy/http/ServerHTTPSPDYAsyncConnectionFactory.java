//========================================================================
//Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================


package org.eclipse.jetty.spdy.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.spdy.ServerSPDYAsyncConnectionFactory;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ServerHTTPSPDYAsyncConnectionFactory extends ServerSPDYAsyncConnectionFactory
{
    private static final String CHANNEL_ATTRIBUTE = "org.eclipse.jetty.spdy.http.HTTPChannelOverSPDY";
    private static final Logger logger = Log.getLogger(ServerHTTPSPDYAsyncConnectionFactory.class);

    private final Connector connector;
    private final PushStrategy pushStrategy;

    public ServerHTTPSPDYAsyncConnectionFactory(short version, ByteBufferPool bufferPool, Executor threadPool, ScheduledExecutorService scheduler, Connector connector, PushStrategy pushStrategy)
    {
        super(version, bufferPool, threadPool, scheduler);
        this.connector = connector;
        this.pushStrategy = pushStrategy;
    }

    @Override
    protected ServerSessionFrameListener provideServerSessionFrameListener(EndPoint endPoint, Object attachment)
    {
        return new HTTPServerFrameListener(endPoint);
    }

    private class HTTPServerFrameListener extends ServerSessionFrameListener.Adapter implements StreamFrameListener
    {
        private final EndPoint endPoint;

        public HTTPServerFrameListener(EndPoint endPoint)
        {
            this.endPoint = endPoint;
        }

        @Override
        public StreamFrameListener onSyn(final Stream stream, SynInfo synInfo)
        {
            // Every time we have a SYN, it maps to a HTTP request.
            // We can have multiple concurrent SYNs on the same connection,
            // and this is very different from HTTP, where only one request/response
            // cycle is processed at a time, so we need to fake an http connection
            // for each SYN in order to run concurrently.

            logger.debug("Received {} on {}", synInfo, stream);

            HTTPChannelOverSPDY channel = new HTTPChannelOverSPDY(connector.getServer(), endPoint.getConnection(),
                    stream);
            stream.setAttribute(CHANNEL_ATTRIBUTE, channel);
            Headers headers = synInfo.getHeaders();

            if (headers.isEmpty())
            {
                // If the SYN has no headers, they may come later in a HEADERS frame
                return this;
            }
            //TODO: beginRequest does two things, startRequest and close...should be doing one only?!
            channel.beginRequest(headers, synInfo.isClose());

            if (synInfo.isClose())
                return null;
            else
                return this;
        }

        @Override
        public void onReply(Stream stream, ReplyInfo replyInfo)
        {
            // Do nothing, servers cannot get replies
        }

        @Override
        public void onHeaders(Stream stream, HeadersInfo headersInfo)
        {
            logger.debug("Received {} on {}", headersInfo, stream);
            HTTPChannelOverSPDY channel = (HTTPChannelOverSPDY)stream.getAttribute(CHANNEL_ATTRIBUTE);
            channel.parseHeaders(headersInfo.getHeaders());

            if (headersInfo.isClose())
                channel.endRequest();
        }

        @Override
        public void onData(Stream stream, final DataInfo dataInfo)
        {
            logger.debug("Received {} on {}", dataInfo, stream);
            HTTPChannelOverSPDY channel = (HTTPChannelOverSPDY)stream.getAttribute(CHANNEL_ATTRIBUTE);

            // We need to copy the dataInfo since we do not know when its bytes
            // will be consumed. When the copy is consumed, we consume also the
            // original, so the implementation can send a window update.
            ByteBufferDataInfo copyDataInfo = new ByteBufferDataInfo(dataInfo.asByteBuffer(false), dataInfo.isClose(), dataInfo.isCompress())
            {
                @Override
                public void consume(int delta)
                {
                    super.consume(delta);
                    dataInfo.consume(delta);
                }
            };
            logger.debug("Queuing last={} content {}", dataInfo.isClose(), copyDataInfo);
            channel.getEventHandler().content(copyDataInfo.asByteBuffer(true));
            //            dataInfos.offer(copyDataInfo); //TODO:
            // .content()
            //            if (endRequest)
            //                dataInfos.offer(END_OF_CONTENT);
            //                    updateState(State.CONTENT);
            //                    handle();
            //                }
            //            });
            //
            //            connection.content(dataInfo, dataInfo.isClose());
            if (dataInfo.isClose())
                channel.endRequest();
        }
    }

    private enum State
    {
        INITIAL, REQUEST, HEADERS, HEADERS_COMPLETE, CONTENT, FINAL, ASYNC
    }

    private class HTTPChannelOverSPDY extends HttpChannel
    {
        private final Stream stream;

        private final Queue<Runnable> tasks = new LinkedList<>();
        private final BlockingQueue<DataInfo> dataInfos = new LinkedBlockingQueue<>();

        // TODO: volatile?
        private Headers headers; // No need for volatile, guarded by state
        private DataInfo dataInfo; // No need for volatile, guarded by state
        //        private NIOBuffer buffer; // No need for volatile, guarded by state
        private volatile State state = State.INITIAL;
        private boolean dispatched; // Guarded by synchronization on tasks


        public HTTPChannelOverSPDY(Server server, Connection connection, Stream stream)
        {
            super(server, connection, new HttpInput());
            this.stream = stream;
        }

        @Override
        public void handle()
        {
            switch (state)
            {
                case INITIAL:
                {
                    break;
                }
                case REQUEST:
                {
                    logger.debug("handle: REQUEST");
                    startRequest(headers);

                    updateState(State.HEADERS);
                    handle();
                    break;
                }
                case HEADERS:
                {
                    parseHeaders(headers);
                    break;
                }
                case HEADERS_COMPLETE:
                {
                    getEventHandler().headerComplete(false, false);
                }
                case CONTENT:
                {
                    //TODO:
                    //                        final Buffer buffer = this.buffer;
                    //                        if (buffer != null && buffer.length() > 0)
                    //                            content(buffer);
                    break;
                }
                case FINAL:
                {
                    getEventHandler().messageComplete(0);
                    super.handle();
                    break;
                }
                case ASYNC:
                {
                    //TODO:
                    //                        handleRequest();
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
                logger.debug("Posting task {}", task);
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
                    logger.debug("Dispatching task {}", task);
                    execute(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            logger.debug("Executing task {}", task);
                            task.run();
                            logger.debug("Completing task {}", task);
                            dispatched = false;
                            dispatch();
                        }
                    });
                }
            }
        }

        private void updateState(State newState)
        {
            logger.debug("State update {} -> {}", state, newState);
            state = newState;
        }

        private void close(Stream stream)
        {
            stream.getSession().goAway();
        }

        public void beginRequest(final Headers headers, final boolean endRequest)
        {
            //TODO: probably not necessary to dispatch beginRequest
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

        private void startRequest(Headers headers)
        {
            this.headers = headers;
            Headers.Header method = headers.get(HTTPSPDYHeader.METHOD.name(getVersion()));
            Headers.Header uri = headers.get(HTTPSPDYHeader.URI.name(getVersion()));
            Headers.Header version = headers.get(HTTPSPDYHeader.VERSION.name(getVersion()));

            if (method == null || uri == null || version == null)
                throw new IllegalStateException("400"); // TODO: replace with HttpException equivalent
            //                throw new HttpException(HttpStatus.BAD_REQUEST_400);

            HttpMethod httpMethod = HttpMethod.fromString(method.value());
            HttpVersion httpVersion = HttpVersion.fromString(version.value());
            String uriString = uri.value();

            logger.debug("HTTP > {} {} {}", httpMethod, uriString, httpVersion);
            //TODO: why pass httpMethod and httpMethod.asString() ?
            getEventHandler().startRequest(httpMethod, httpMethod.asString(), uriString, httpVersion);

            Headers.Header schemeHeader = headers.get(HTTPSPDYHeader.SCHEME.name(getVersion()));
            //            if (schemeHeader != null)  //TODO: thomas
            //                _request.setScheme(schemeHeader.value());
        }

        private void parseHeaders(Headers headers)
        {
            for (Headers.Header header : headers)
            {
                String name = header.name();
                HttpHeader httpHeader = null;

                // Skip special SPDY headers, unless it's the "host" header
                HTTPSPDYHeader specialHeader = HTTPSPDYHeader.from(getVersion(), name);
                if (specialHeader != null)
                {
                    if (specialHeader == HTTPSPDYHeader.HOST)
                    {
                        httpHeader = HttpHeader.HOST;
                        name = "host";
                    }
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
                        logger.debug("HTTP > {}: {}", name, value);
                        //TODO: Is it safe to pass a null HttpHeader here?
                        getEventHandler().parsedHeader(httpHeader, name, value);
                        break;
                    }
                }
            }
        }

        @Override
        public Connector getConnector()
        {
            logger.debug("getConnector");
            return null;
        }

        @Override
        public HttpConfiguration getHttpConfiguration()
        {
            logger.debug("getHttpConfiguration");
            return new HttpConfiguration(null, false);
        }

        @Override
        protected int write(ByteBuffer content) throws IOException
        {
            logger.debug("write");
            return 0;
        }

        @Override
        protected void commitResponse(HttpGenerator.ResponseInfo info, ByteBuffer content) throws IOException
        {
            logger.debug("commitResponse");
        }

        @Override
        protected int getContentBufferSize()
        {
            logger.debug("getContentBufferSize");
            return 0;
        }

        @Override
        protected void increaseContentBufferSize(int size)
        {
            logger.debug("increaseContentBufferSize");
        }

        @Override
        protected void resetBuffer()
        {
            logger.debug("resetBuffer");
        }

        @Override
        protected void flushResponse() throws IOException
        {
            logger.debug("flushResponse");
        }

        @Override
        protected void completeResponse() throws IOException
        {
            logger.debug("completeResponse");
            getEventHandler().commit();
            Response response = getResponse();
            Headers headers = new Headers();
            headers.put(HTTPSPDYHeader.VERSION.name(getVersion()), HttpVersion.HTTP_1_1.asString());
            StringBuilder status = new StringBuilder().append(response.getStatus());
            String reason = response.getReason();
            if (reason != null)
                status.append(" ").append(reason.toString());
            headers.put(HTTPSPDYHeader.STATUS.name(getVersion()), status.toString());
            logger.debug("HTTP < {} {}", HttpVersion.HTTP_1_1, status);

            HttpFields httpFields = response.getHttpFields();
            if (httpFields != null)
            {
                for (HttpFields.Field httpField : httpFields)
                {
                    String name = httpField.getName().toLowerCase();
                    String value = httpField.getValue();
                    headers.put(name, value);
                    logger.debug("HTTP < {}: {}", name, value);
                }
            }

            // We have to query the HttpGenerator and its buffers to know
            // whether there is content buffered and update the generator state
            reply(stream, new ReplyInfo(headers, response.getContentCount() < 1));

            //TODO: sent content
        }

        protected void reply(Stream stream, ReplyInfo replyInfo)
        {
            if (!stream.isUnidirectional())
                stream.reply(replyInfo);
            if (replyInfo.getHeaders().get(HTTPSPDYHeader.STATUS.name(getVersion())).value().startsWith("200") &&
                    !stream.isClosed())
            {
                // We have a 200 OK with some content to send

                Headers.Header scheme = headers.get(HTTPSPDYHeader.SCHEME.name(getVersion()));
                Headers.Header host = headers.get(HTTPSPDYHeader.HOST.name(getVersion()));
                Headers.Header uri = headers.get(HTTPSPDYHeader.URI.name(getVersion()));
                Set<String> pushResources = pushStrategy.apply(stream, headers, replyInfo.getHeaders());

                for (String pushResourcePath : pushResources)
                {
                    final Headers requestHeaders = createRequestHeaders(scheme, host, uri, pushResourcePath);
                    final Headers pushHeaders = createPushHeaders(scheme, host, pushResourcePath);

                    //TODO:
                    //                    stream.syn(new SynInfo(pushHeaders, false), getMaxIdleTime(), TimeUnit.MILLISECONDS, new Handler.Adapter<Stream>()
                    //                    {
                    //                        @Override
                    //                        public void completed(Stream pushStream)
                    //                        {
                    //                            ServerHTTPSPDYAsyncConnection pushConnection =
                    //                                    new ServerHTTPSPDYAsyncConnection(getConnector(), getEndPoint(), getServer(), getVersion(), connection, pushStrategy, pushStream);
                    //                            pushConnection.startRequest(requestHeaders, true);
                    //                        }
                    //                    });
                }
            }
        }

        private Headers createRequestHeaders(Headers.Header scheme, Headers.Header host, Headers.Header uri, String pushResourcePath)
        {
            final Headers requestHeaders = new Headers();
            requestHeaders.put(HTTPSPDYHeader.METHOD.name(getVersion()), "GET");
            requestHeaders.put(HTTPSPDYHeader.VERSION.name(getVersion()), "HTTP/1.1");
            requestHeaders.put(scheme);
            requestHeaders.put(host);
            requestHeaders.put(HTTPSPDYHeader.URI.name(getVersion()), pushResourcePath);
            String referrer = scheme.value() + "://" + host.value() + uri.value();
            requestHeaders.put("referer", referrer);
            // Remember support for gzip encoding
            requestHeaders.put(headers.get("accept-encoding"));
            requestHeaders.put("x-spdy-push", "true");
            return requestHeaders;
        }

        private Headers createPushHeaders(Headers.Header scheme, Headers.Header host, String pushResourcePath)
        {
            final Headers pushHeaders = new Headers();
            if (getVersion() == SPDY.V2)
                pushHeaders.put(HTTPSPDYHeader.URI.name(getVersion()), scheme.value() + "://" + host.value() + pushResourcePath);
            else
            {
                pushHeaders.put(HTTPSPDYHeader.URI.name(getVersion()), pushResourcePath);
                pushHeaders.put(scheme);
                pushHeaders.put(host);
            }
            pushHeaders.put(HTTPSPDYHeader.STATUS.name(getVersion()), "200");
            pushHeaders.put(HTTPSPDYHeader.VERSION.name(getVersion()), "HTTP/1.1");
            return pushHeaders;
        }

        @Override
        protected void completed()
        {
            logger.debug("completed");
        }

        @Override
        protected void execute(Runnable task)
        {
            connector.getExecutor().execute(task);
        }

        @Override
        public ScheduledExecutorService getScheduler()
        {
            return null;
        }
    }
}
