/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.proxy;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
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
public class SPDYProxyEngine extends ProxyEngine
{
    private static final String STREAM_HANDLER_ATTRIBUTE = "org.eclipse.jetty.spdy.http.proxy.streamHandler";

    private final ConcurrentMap<String, Future<Session>> serverSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<Session, Set<Session>> clientSessions = new ConcurrentHashMap<>();
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

    @Override
    public void onGoAway(Session clientSession, GoAwayInfo goAwayInfo)
    {
        Set<Session> target = null;
        for (Set<Session> sessions : clientSessions.values())
        {
            for (Session session : sessions)
            {
                if (session == clientSession)
                {
                    target = sessions;
                    break;
                }
            }
            if (target != null)
                break;
        }
        if (target != null)
        {
            target.remove(clientSession);
            // Do not remove the Set if it's empty: there is one Set per proxied
            // host, so we can afford this small leak and avoid synchronization
        }
    }

    @Override
    public StreamFrameListener onSyn(final Stream clientStream, SynInfo clientSynInfo)
    {
        logger.debug("C -> P {} on {}", clientSynInfo, clientStream);

        final Session clientSession = clientStream.getSession();
        short clientVersion = clientSession.getVersion();
        Headers headers = new Headers(clientSynInfo.getHeaders(), false);

        Headers.Header hostHeader = headers.get(HTTPSPDYHeader.HOST.name(clientVersion));
        if (hostHeader == null)
        {
            rst(clientStream);
            return null;
        }

        String host = hostHeader.value();
        int colon = host.indexOf(':');
        if (colon >= 0)
            host = host.substring(0, colon);
        ProxyInfo proxyInfo = getProxyInfo(host);
        if (proxyInfo == null)
        {
            rst(clientStream);
            return null;
        }

        // TODO: give a chance to modify headers and rewrite URI

        short serverVersion = proxyInfo.getVersion();
        InetSocketAddress address = proxyInfo.getAddress();
        Session serverSession = produceSession(host, serverVersion, address);
        if (serverSession == null)
        {
            rst(clientStream);
            return null;
        }

        Set<Session> sessions = clientSessions.get(serverSession);
        if (sessions == null)
        {
            sessions = Collections.newSetFromMap(new ConcurrentHashMap<Session, Boolean>());
            Set<Session> existing = clientSessions.putIfAbsent(serverSession, sessions);
            if (existing != null)
                sessions = existing;
        }
        sessions.add(clientSession);

        convert(clientVersion, serverVersion, headers);

        addRequestProxyHeaders(headers);

        SynInfo serverSynInfo = new SynInfo(headers, clientSynInfo.isClose());
        logger.debug("P -> S {}", serverSynInfo);

        StreamFrameListener listener = new ProxyStreamFrameListener(clientStream);
        if (serverSynInfo.isClose())
        {
            serverSession.syn(serverSynInfo, listener, timeout, TimeUnit.MILLISECONDS, new Handler.Adapter<Stream>()
            {
                @Override
                public void failed(Stream context, Throwable x)
                {
                    logger.debug(x);
                    rst(clientStream);
                }
            });
            return null;
        }
        else
        {
            StreamHandler streamHandler = new StreamHandler(clientStream);
            clientStream.setAttribute(STREAM_HANDLER_ATTRIBUTE, streamHandler);
            serverSession.syn(serverSynInfo, listener, timeout, TimeUnit.MILLISECONDS, streamHandler);
            return this;
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
        throw new UnsupportedOperationException("Not yet implemented");
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
            Future<Session> session = serverSessions.get(host);
            if (session == null)
            {
                SPDYClient client = factory.newSPDYClient(version);
                session = client.connect(address, sessionListener);
                Future<Session> existing = serverSessions.putIfAbsent(host, session);
                if (existing != null)
                {
                    session.cancel(true);
                    session = existing;
                }
            }
            return session.get(getConnectTimeout(), TimeUnit.MILLISECONDS);
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
            short serverVersion = stream.getSession().getVersion();
            Headers headers = new Headers(replyInfo.getHeaders(), false);
            short clientVersion = this.clientStream.getSession().getVersion();
            convert(serverVersion, clientVersion, headers);

            addResponseProxyHeaders(headers);

            this.replyInfo = new ReplyInfo(headers, replyInfo.isClose());
            if (replyInfo.isClose())
                reply();
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
            if (replyInfo != null)
            {
                if (dataInfo.isClose())
                    replyInfo.getHeaders().put("content-length", String.valueOf(dataInfo.available()));
                reply();
            }
            data(dataInfo);
        }

        private void reply()
        {
            clientStream.reply(replyInfo, getTimeout(), TimeUnit.MILLISECONDS, new Handler.Adapter<Void>()
            {
                @Override
                public void failed(Void context, Throwable x)
                {
                    logger.debug(x);
                    rst(clientStream);
                }
            });
            replyInfo = null;
        }

        private void data(final DataInfo dataInfo)
        {
            clientStream.data(dataInfo, getTimeout(), TimeUnit.MILLISECONDS, new Handler<Void>()
            {
                @Override
                public void completed(Void context)
                {
                    dataInfo.consume(dataInfo.length());
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
        private Stream serverStream;

        private StreamHandler(Stream clientStream)
        {
            this.clientStream = clientStream;
        }

        @Override
        public void completed(Stream serverStream)
        {
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

    private class ProxySessionFrameListener extends SessionFrameListener.Adapter
    {
        @Override
        public void onGoAway(Session serverSession, GoAwayInfo goAwayInfo)
        {
            Set<Session> sessions = clientSessions.remove(serverSession);
            if (sessions != null)
            {
                for (Session session : sessions)
                    session.goAway(getTimeout(), TimeUnit.MILLISECONDS, new Handler.Adapter<Void>());
            }
        }
    }
}
