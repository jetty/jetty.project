//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.spdy.proxy;

import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.SPDYClient;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;

/**
 * <p>{@link SPDYProxyEngine} implements a SPDY to SPDY proxy, that is, converts SPDY events received by
 * clients into SPDY events for the servers.</p>
 */
public class SPDYProxyEngine extends ProxyEngine implements StreamFrameListener
{
    private static final String STREAM_HANDLER_ATTRIBUTE = "org.eclipse.jetty.spdy.http.proxy.streamHandler";
    private static final String CLIENT_STREAM_ATTRIBUTE = "org.eclipse.jetty.spdy.http.proxy.clientStream";

    private final ConcurrentMap<String, Session> serverSessions = new ConcurrentHashMap<>();
    private final SessionFrameListener sessionListener = new ProxySessionFrameListener();
    private final SPDYClient.Factory factory;
    private volatile long connectTimeout = 15000;
    private volatile long timeout = 60000;

    public SPDYProxyEngine(SPDYClient.Factory factory)
    {
        this.factory = factory;
    }

    public long getConnectTimeout()
    {
        return connectTimeout;
    }

    public void setConnectTimeout(long connectTimeout)
    {
        this.connectTimeout = connectTimeout;
    }

    public long getTimeout()
    {
        return timeout;
    }

    public void setTimeout(long timeout)
    {
        this.timeout = timeout;
    }

    public StreamFrameListener proxy(final Stream clientStream, SynInfo clientSynInfo, ProxyEngineSelector.ProxyServerInfo proxyServerInfo)
    {
        Headers headers = new Headers(clientSynInfo.getHeaders(), false);

        short serverVersion = getVersion(proxyServerInfo.getProtocol());
        InetSocketAddress address = proxyServerInfo.getAddress();
        Session serverSession = produceSession(proxyServerInfo.getHost(), serverVersion, address);
        if (serverSession == null)
        {
            rst(clientStream);
            return null;
        }

        final Session clientSession = clientStream.getSession();

        addRequestProxyHeaders(clientStream, headers);
        customizeRequestHeaders(clientStream, headers);
        convert(clientSession.getVersion(), serverVersion, headers);

        SynInfo serverSynInfo = new SynInfo(headers, clientSynInfo.isClose());
        StreamFrameListener listener = new ProxyStreamFrameListener(clientStream);
        StreamHandler handler = new StreamHandler(clientStream, serverSynInfo);
        clientStream.setAttribute(STREAM_HANDLER_ATTRIBUTE, handler);
        serverSession.syn(serverSynInfo, listener, timeout, TimeUnit.MILLISECONDS, handler);
        return this;
    }

    private static short getVersion(String protocol)
    {
        switch (protocol)
        {
            case "spdy/2":
                return SPDY.V2;
            case "spdy/3":
                return SPDY.V3;
            default:
                throw new IllegalArgumentException("Procotol: " + protocol + " is not a known SPDY protocol");
        }
    }

    @Override
    public void onReply(Stream stream, ReplyInfo replyInfo)
    {
        // Servers do not receive replies
    }

    @Override
    public void onHeaders(Stream stream, HeadersInfo headersInfo)
    {
        // TODO
        throw new UnsupportedOperationException("Not Yet Implemented");
    }

    @Override
    public void onData(Stream clientStream, final DataInfo clientDataInfo)
    {
        logger.debug("C -> P {} on {}", clientDataInfo, clientStream);

        ByteBufferDataInfo serverDataInfo = new ByteBufferDataInfo(clientDataInfo.asByteBuffer(false), clientDataInfo.isClose())
        {
            @Override
            public void consume(int delta)
            {
                super.consume(delta);
                clientDataInfo.consume(delta);
            }
        };

        StreamHandler streamHandler = (StreamHandler)clientStream.getAttribute(STREAM_HANDLER_ATTRIBUTE);
        streamHandler.data(serverDataInfo);
    }

    private Session produceSession(String host, short version, InetSocketAddress address)
    {
        try
        {
            Session session = serverSessions.get(host);
            if (session == null)
            {
                SPDYClient client = factory.newSPDYClient(version);
                session = client.connect(address, sessionListener).get(getConnectTimeout(), TimeUnit.MILLISECONDS);
                logger.debug("Proxy session connected to {}", address);
                Session existing = serverSessions.putIfAbsent(host, session);
                if (existing != null)
                {
                    session.goAway(getTimeout(), TimeUnit.MILLISECONDS, new Handler.Adapter<Void>());
                    session = existing;
                }
            }
            return session;
        }
        catch (Exception x)
        {
            logger.debug(x);
            return null;
        }
    }

    private void convert(short fromVersion, short toVersion, Headers headers)
    {
        if (fromVersion != toVersion)
        {
            for (HTTPSPDYHeader httpHeader : HTTPSPDYHeader.values())
            {
                Headers.Header header = headers.remove(httpHeader.name(fromVersion));
                if (header != null)
                {
                    String toName = httpHeader.name(toVersion);
                    for (String value : header.values())
                        headers.add(toName, value);
                }
            }
        }
    }

    private void rst(Stream stream)
    {
        RstInfo rstInfo = new RstInfo(stream.getId(), StreamStatus.REFUSED_STREAM);
        stream.getSession().rst(rstInfo, getTimeout(), TimeUnit.MILLISECONDS, new Handler.Adapter<Void>());
    }

    private class ProxyStreamFrameListener extends StreamFrameListener.Adapter
    {
        private final Stream clientStream;
        private volatile ReplyInfo replyInfo;

        public ProxyStreamFrameListener(Stream clientStream)
        {
            this.clientStream = clientStream;
        }

        @Override
        public void onReply(final Stream stream, ReplyInfo replyInfo)
        {
            logger.debug("S -> P {} on {}", replyInfo, stream);

            short serverVersion = stream.getSession().getVersion();
            Headers headers = new Headers(replyInfo.getHeaders(), false);

            addResponseProxyHeaders(stream, headers);
            customizeResponseHeaders(stream, headers);
            short clientVersion = this.clientStream.getSession().getVersion();
            convert(serverVersion, clientVersion, headers);

            this.replyInfo = new ReplyInfo(headers, replyInfo.isClose());
            if (replyInfo.isClose())
                reply(stream);
        }

        @Override
        public void onHeaders(Stream stream, HeadersInfo headersInfo)
        {
            // TODO
            throw new UnsupportedOperationException("Not Yet Implemented");
        }

        @Override
        public void onData(final Stream stream, final DataInfo dataInfo)
        {
            logger.debug("S -> P {} on {}", dataInfo, stream);

            if (replyInfo != null)
            {
                if (dataInfo.isClose())
                    replyInfo.getHeaders().put("content-length", String.valueOf(dataInfo.available()));
                reply(stream);
            }
            data(stream, dataInfo);
        }

        private void reply(final Stream stream)
        {
            final ReplyInfo replyInfo = this.replyInfo;
            this.replyInfo = null;
            clientStream.reply(replyInfo, getTimeout(), TimeUnit.MILLISECONDS, new Handler<Void>()
            {
                @Override
                public void completed(Void context)
                {
                    logger.debug("P -> C {} from {} to {}", replyInfo, stream, clientStream);
                }

                @Override
                public void failed(Void context, Throwable x)
                {
                    logger.debug(x);
                    rst(clientStream);
                }
            });
        }

        private void data(final Stream stream, final DataInfo dataInfo)
        {
            clientStream.data(dataInfo, getTimeout(), TimeUnit.MILLISECONDS, new Handler<Void>()
            {
                @Override
                public void completed(Void context)
                {
                    dataInfo.consume(dataInfo.length());
                    logger.debug("P -> C {} from {} to {}", dataInfo, stream, clientStream);
                }

                @Override
                public void failed(Void context, Throwable x)
                {
                    logger.debug(x);
                    rst(clientStream);
                }
            });
        }
    }

    /**
     * <p>{@link StreamHandler} implements the forwarding of DATA frames from the client to the server.</p>
     * <p>Instances of this class buffer DATA frames sent by clients and send them to the server.
     * The buffering happens between the send of the SYN_STREAM to the server (where DATA frames may arrive
     * from the client before the SYN_STREAM has been fully sent), and between DATA frames, if the client
     * is a fast producer and the server a slow consumer, or if the client is a SPDY v2 client (and hence
     * without flow control) while the server is a SPDY v3 server (and hence with flow control).</p>
     */
    private class StreamHandler implements Handler<Stream>
    {
        private final Queue<DataInfoHandler> queue = new LinkedList<>();
        private final Stream clientStream;
        private final SynInfo serverSynInfo;
        private Stream serverStream;

        private StreamHandler(Stream clientStream, SynInfo serverSynInfo)
        {
            this.clientStream = clientStream;
            this.serverSynInfo = serverSynInfo;
        }

        @Override
        public void completed(Stream serverStream)
        {
            logger.debug("P -> S {} from {} to {}", serverSynInfo, clientStream, serverStream);

            serverStream.setAttribute(CLIENT_STREAM_ATTRIBUTE, clientStream);

            DataInfoHandler dataInfoHandler;
            synchronized (queue)
            {
                this.serverStream = serverStream;
                dataInfoHandler = queue.peek();
                if (dataInfoHandler != null)
                {
                    if (dataInfoHandler.flushing)
                    {
                        logger.debug("SYN completed, flushing {}, queue size {}", dataInfoHandler.dataInfo, queue.size());
                        dataInfoHandler = null;
                    }
                    else
                    {
                        dataInfoHandler.flushing = true;
                        logger.debug("SYN completed, queue size {}", queue.size());
                    }
                }
                else
                {
                    logger.debug("SYN completed, queue empty");
                }
            }
            if (dataInfoHandler != null)
                flush(serverStream, dataInfoHandler);
        }

        @Override
        public void failed(Stream serverStream, Throwable x)
        {
            logger.debug(x);
            rst(clientStream);
        }

        public void data(DataInfo dataInfo)
        {
            Stream serverStream;
            DataInfoHandler dataInfoHandler = null;
            DataInfoHandler item = new DataInfoHandler(dataInfo);
            synchronized (queue)
            {
                queue.offer(item);
                serverStream = this.serverStream;
                if (serverStream != null)
                {
                    dataInfoHandler = queue.peek();
                    if (dataInfoHandler.flushing)
                    {
                        logger.debug("Queued {}, flushing {}, queue size {}", dataInfo, dataInfoHandler.dataInfo, queue.size());
                        serverStream = null;
                    }
                    else
                    {
                        dataInfoHandler.flushing = true;
                        logger.debug("Queued {}, queue size {}", dataInfo, queue.size());
                    }
                }
                else
                {
                    logger.debug("Queued {}, SYN incomplete, queue size {}", dataInfo, queue.size());
                }
            }
            if (serverStream != null)
                flush(serverStream, dataInfoHandler);
        }

        private void flush(Stream serverStream, DataInfoHandler dataInfoHandler)
        {
            logger.debug("P -> S {} on {}", dataInfoHandler.dataInfo, serverStream);
            serverStream.data(dataInfoHandler.dataInfo, getTimeout(), TimeUnit.MILLISECONDS, dataInfoHandler);
        }

        private class DataInfoHandler implements Handler<Void>
        {
            private final DataInfo dataInfo;
            private boolean flushing;

            private DataInfoHandler(DataInfo dataInfo)
            {
                this.dataInfo = dataInfo;
            }

            @Override
            public void completed(Void context)
            {
                Stream serverStream;
                DataInfoHandler dataInfoHandler;
                synchronized (queue)
                {
                    serverStream = StreamHandler.this.serverStream;
                    assert serverStream != null;
                    dataInfoHandler = queue.poll();
                    assert dataInfoHandler == this;
                    dataInfoHandler = queue.peek();
                    if (dataInfoHandler != null)
                    {
                        assert !dataInfoHandler.flushing;
                        dataInfoHandler.flushing = true;
                        logger.debug("Completed {}, queue size {}", dataInfo, queue.size());
                    }
                    else
                    {
                        logger.debug("Completed {}, queue empty", dataInfo);
                    }
                }
                if (dataInfoHandler != null)
                    flush(serverStream, dataInfoHandler);
            }

            @Override
            public void failed(Void context, Throwable x)
            {
                logger.debug(x);
                rst(clientStream);
            }
        }
    }

    private class ProxySessionFrameListener extends SessionFrameListener.Adapter implements StreamFrameListener
    {
        @Override
        public StreamFrameListener onSyn(Stream serverStream, SynInfo serverSynInfo)
        {
            logger.debug("S -> P pushed {} on {}", serverSynInfo, serverStream);

            Headers headers = new Headers(serverSynInfo.getHeaders(), false);

            addResponseProxyHeaders(serverStream, headers);
            customizeResponseHeaders(serverStream, headers);
            Stream clientStream = (Stream)serverStream.getAssociatedStream().getAttribute(CLIENT_STREAM_ATTRIBUTE);
            convert(serverStream.getSession().getVersion(), clientStream.getSession().getVersion(), headers);

            StreamHandler handler = new StreamHandler(clientStream, serverSynInfo);
            serverStream.setAttribute(STREAM_HANDLER_ATTRIBUTE, handler);
            clientStream.syn(new SynInfo(headers, serverSynInfo.isClose()), getTimeout(), TimeUnit.MILLISECONDS, handler);

            return this;
        }

        @Override
        public void onRst(Session serverSession, RstInfo serverRstInfo)
        {
            Stream serverStream = serverSession.getStream(serverRstInfo.getStreamId());
            if (serverStream != null)
            {
                Stream clientStream = (Stream)serverStream.getAttribute(CLIENT_STREAM_ATTRIBUTE);
                if (clientStream != null)
                {
                    Session clientSession = clientStream.getSession();
                    RstInfo clientRstInfo = new RstInfo(clientStream.getId(), serverRstInfo.getStreamStatus());
                    clientSession.rst(clientRstInfo, getTimeout(), TimeUnit.MILLISECONDS, new Handler.Adapter<Void>());
                }
            }
        }

        @Override
        public void onGoAway(Session serverSession, GoAwayInfo goAwayInfo)
        {
            serverSessions.values().remove(serverSession);
        }

        @Override
        public void onReply(Stream stream, ReplyInfo replyInfo)
        {
            // Push streams never send a reply
        }

        @Override
        public void onHeaders(Stream stream, HeadersInfo headersInfo)
        {
            throw new UnsupportedOperationException(); //TODO
        }

        @Override
        public void onData(Stream serverStream, final DataInfo serverDataInfo)
        {
            logger.debug("S -> P pushed {} on {}", serverDataInfo, serverStream);

            ByteBufferDataInfo clientDataInfo = new ByteBufferDataInfo(serverDataInfo.asByteBuffer(false), serverDataInfo.isClose())
            {
                @Override
                public void consume(int delta)
                {
                    super.consume(delta);
                    serverDataInfo.consume(delta);
                }
            };

            StreamHandler handler = (StreamHandler)serverStream.getAttribute(STREAM_HANDLER_ATTRIBUTE);
            handler.data(clientDataInfo);
        }
    }
}
