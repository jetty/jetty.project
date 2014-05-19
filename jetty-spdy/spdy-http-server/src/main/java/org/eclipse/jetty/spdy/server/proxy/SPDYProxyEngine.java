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


package org.eclipse.jetty.spdy.server.proxy;

import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.GoAwayResultInfo;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.Info;
import org.eclipse.jetty.spdy.api.PushInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.client.SPDYClient;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>{@link SPDYProxyEngine} implements a SPDY to SPDY proxy, that is, converts SPDY events received by clients into
 * SPDY events for the servers.</p>
 */
public class SPDYProxyEngine extends ProxyEngine implements StreamFrameListener
{
    private static final Logger LOG = Log.getLogger(SPDYProxyEngine.class);

    private static final String STREAM_PROMISE_ATTRIBUTE = "org.eclipse.jetty.spdy.server.http.proxy.streamPromise";
    private static final String CLIENT_STREAM_ATTRIBUTE = "org.eclipse.jetty.spdy.server.http.proxy.clientStream";

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
        Fields headers = new Fields(clientSynInfo.getHeaders(), false);

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
        StreamPromise promise = new StreamPromise(clientStream, serverSynInfo);
        clientStream.setAttribute(STREAM_PROMISE_ATTRIBUTE, promise);
        serverSession.syn(serverSynInfo, listener, promise);
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
    public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
    {
        throw new IllegalStateException("We shouldn't receive pushes from clients");
    }

    @Override
    public void onReply(Stream stream, ReplyInfo replyInfo)
    {
        throw new IllegalStateException("Servers do not receive replies");
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
        LOG.debug("C -> P {} on {}", clientDataInfo, clientStream);

        ByteBufferDataInfo serverDataInfo = new ByteBufferDataInfo(clientDataInfo.asByteBuffer(false), clientDataInfo.isClose())
        {
            @Override
            public void consume(int delta)
            {
                super.consume(delta);
                clientDataInfo.consume(delta);
            }
        };

        StreamPromise streamPromise = (StreamPromise)clientStream.getAttribute(STREAM_PROMISE_ATTRIBUTE);
        streamPromise.data(serverDataInfo);
    }

    @Override
    public void onFailure(Stream stream, Throwable x)
    {
        LOG.debug(x);
    }

    private Session produceSession(String host, short version, InetSocketAddress address)
    {
        try
        {
            Session session = serverSessions.get(host);
            if (session == null)
            {
                SPDYClient client = factory.newSPDYClient(version);
                session = client.connect(address, sessionListener);
                LOG.debug("Proxy session connected to {}", address);
                Session existing = serverSessions.putIfAbsent(host, session);
                if (existing != null)
                {
                    session.goAway(new GoAwayInfo(), Callback.Adapter.INSTANCE);
                    session = existing;
                }
            }
            return session;
        }
        catch (Exception x)
        {
            LOG.debug(x);
            return null;
        }
    }

    private void convert(short fromVersion, short toVersion, Fields headers)
    {
        if (fromVersion != toVersion)
        {
            for (HTTPSPDYHeader httpHeader : HTTPSPDYHeader.values())
            {
                Fields.Field header = headers.remove(httpHeader.name(fromVersion));
                if (header != null)
                {
                    String toName = httpHeader.name(toVersion);
                    for (String value : header.getValues())
                        headers.add(toName, value);
                }
            }
        }
    }

    private void rst(Stream stream)
    {
        RstInfo rstInfo = new RstInfo(stream.getId(), StreamStatus.REFUSED_STREAM);
        stream.getSession().rst(rstInfo, Callback.Adapter.INSTANCE);
    }

    private class ProxyPushStreamFrameListener implements StreamFrameListener
    {
        private PushStreamPromise pushStreamPromise;

        private ProxyPushStreamFrameListener(PushStreamPromise pushStreamPromise)
        {
            this.pushStreamPromise = pushStreamPromise;
        }

        @Override
        public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
        {
            LOG.debug("S -> P pushed {} on {}. Opening new PushStream P -> C now.", pushInfo, stream);
            PushStreamPromise newPushStreamPromise = new PushStreamPromise(stream, pushInfo);
            this.pushStreamPromise.push(newPushStreamPromise);
            return new ProxyPushStreamFrameListener(newPushStreamPromise);
        }

        @Override
        public void onReply(Stream stream, ReplyInfo replyInfo)
        {
            // Push streams never send a reply
            throw new UnsupportedOperationException();
        }

        @Override
        public void onHeaders(Stream stream, HeadersInfo headersInfo)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onData(Stream serverStream, final DataInfo serverDataInfo)
        {
            LOG.debug("S -> P pushed {} on {}", serverDataInfo, serverStream);

            ByteBufferDataInfo clientDataInfo = new ByteBufferDataInfo(serverDataInfo.asByteBuffer(false), serverDataInfo.isClose())
            {
                @Override
                public void consume(int delta)
                {
                    super.consume(delta);
                    serverDataInfo.consume(delta);
                }
            };

            pushStreamPromise.data(clientDataInfo);
        }

        @Override
        public void onFailure(Stream stream, Throwable x)
        {
            LOG.debug(x);
        }
    }

    private class ProxyStreamFrameListener extends StreamFrameListener.Adapter
    {
        private final Stream receiverStream;

        public ProxyStreamFrameListener(Stream receiverStream)
        {
            this.receiverStream = receiverStream;
        }

        @Override
        public StreamFrameListener onPush(Stream senderStream, PushInfo pushInfo)
        {
            LOG.debug("S -> P {} on {}");
            PushInfo newPushInfo = convertPushInfo(pushInfo, senderStream, receiverStream);
            PushStreamPromise pushStreamPromise = new PushStreamPromise(senderStream, newPushInfo);
            receiverStream.push(newPushInfo, pushStreamPromise);
            return new ProxyPushStreamFrameListener(pushStreamPromise);
        }

        @Override
        public void onReply(final Stream stream, ReplyInfo replyInfo)
        {
            LOG.debug("S -> P {} on {}", replyInfo, stream);
            final ReplyInfo clientReplyInfo = new ReplyInfo(convertHeaders(stream, receiverStream, replyInfo.getHeaders()),
                    replyInfo.isClose());
            reply(stream, clientReplyInfo);
        }

        private void reply(final Stream stream, final ReplyInfo clientReplyInfo)
        {
            receiverStream.reply(clientReplyInfo, new Callback()
            {
                @Override
                public void succeeded()
                {
                    LOG.debug("P -> C {} from {} to {}", clientReplyInfo, stream, receiverStream);
                }

                @Override
                public void failed(Throwable x)
                {
                    LOG.debug(x);
                    rst(receiverStream);
                }
            });
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
            LOG.debug("S -> P {} on {}", dataInfo, stream);
            data(stream, dataInfo);
        }

        private void data(final Stream stream, final DataInfo serverDataInfo)
        {
            final ByteBufferDataInfo clientDataInfo = new ByteBufferDataInfo(serverDataInfo.asByteBuffer(false), serverDataInfo.isClose())
            {
                @Override
                public void consume(int delta)
                {
                    super.consume(delta);
                    serverDataInfo.consume(delta);
                }
            };

            receiverStream.data(clientDataInfo, new Callback() //TODO: timeout???
            {
                @Override
                public void succeeded()
                {
                    LOG.debug("P -> C {} from {} to {}", clientDataInfo, stream, receiverStream);
                }

                @Override
                public void failed(Throwable x)
                {
                    LOG.debug(x);
                    rst(receiverStream);
                }
            });
        }
    }

    /**
     * <p>{@link StreamPromise} implements the forwarding of DATA frames from the client to the server and vice
     * versa.</p> <p>Instances of this class buffer DATA frames sent by clients and send them to the server. The
     * buffering happens between the send of the SYN_STREAM to the server (where DATA frames may arrive from the client
     * before the SYN_STREAM has been fully sent), and between DATA frames, if the client is a fast producer and the
     * server a slow consumer, or if the client is a SPDY v2 client (and hence without flow control) while the server is
     * a SPDY v3 server (and hence with flow control).</p>
     */
    private class StreamPromise implements Promise<Stream>
    {
        private final Queue<DataInfoCallback> queue = new LinkedList<>();
        private final Stream senderStream;
        private final Info info;
        private Stream receiverStream;

        private StreamPromise(Stream senderStream, Info info)
        {
            this.senderStream = senderStream;
            this.info = info;
        }

        @Override
        public void succeeded(Stream stream)
        {
            LOG.debug("P -> S {} from {} to {}", info, senderStream, stream);

            stream.setAttribute(CLIENT_STREAM_ATTRIBUTE, senderStream);

            DataInfoCallback dataInfoCallback;
            synchronized (queue)
            {
                this.receiverStream = stream;
                dataInfoCallback = queue.peek();
                if (dataInfoCallback != null)
                {
                    if (dataInfoCallback.flushing)
                    {
                        LOG.debug("SYN completed, flushing {}, queue size {}", dataInfoCallback.dataInfo, queue.size());
                        dataInfoCallback = null;
                    }
                    else
                    {
                        dataInfoCallback.flushing = true;
                        LOG.debug("SYN completed, queue size {}", queue.size());
                    }
                }
                else
                {
                    LOG.debug("SYN completed, queue empty");
                }
            }
            if (dataInfoCallback != null)
                flush(stream, dataInfoCallback);
        }

        @Override
        public void failed(Throwable x)
        {
            LOG.debug(x);
            rst(senderStream);
        }

        public void data(DataInfo dataInfo)
        {
            Stream receiverStream;
            DataInfoCallback dataInfoCallbackToFlush = null;
            DataInfoCallback dataInfoCallBackToQueue = new DataInfoCallback(dataInfo);
            synchronized (queue)
            {
                queue.offer(dataInfoCallBackToQueue);
                receiverStream = this.receiverStream;
                if (receiverStream != null)
                {
                    dataInfoCallbackToFlush = queue.peek();
                    if (dataInfoCallbackToFlush.flushing)
                    {
                        LOG.debug("Queued {}, flushing {}, queue size {}", dataInfo, dataInfoCallbackToFlush.dataInfo, queue.size());
                        receiverStream = null;
                    }
                    else
                    {
                        dataInfoCallbackToFlush.flushing = true;
                        LOG.debug("Queued {}, queue size {}", dataInfo, queue.size());
                    }
                }
                else
                {
                    LOG.debug("Queued {}, SYN incomplete, queue size {}", dataInfo, queue.size());
                }
            }
            if (receiverStream != null)
                flush(receiverStream, dataInfoCallbackToFlush);
        }

        private void flush(Stream receiverStream, DataInfoCallback dataInfoCallback)
        {
            LOG.debug("P -> S {} on {}", dataInfoCallback.dataInfo, receiverStream);
            receiverStream.data(dataInfoCallback.dataInfo, dataInfoCallback); //TODO: timeout???
        }

        private class DataInfoCallback implements Callback
        {
            private final DataInfo dataInfo;
            private boolean flushing;

            private DataInfoCallback(DataInfo dataInfo)
            {
                this.dataInfo = dataInfo;
            }

            @Override
            public void succeeded()
            {
                Stream serverStream;
                DataInfoCallback dataInfoCallback;
                synchronized (queue)
                {
                    serverStream = StreamPromise.this.receiverStream;
                    assert serverStream != null;
                    dataInfoCallback = queue.poll();
                    assert dataInfoCallback == this;
                    dataInfoCallback = queue.peek();
                    if (dataInfoCallback != null)
                    {
                        assert !dataInfoCallback.flushing;
                        dataInfoCallback.flushing = true;
                        LOG.debug("Completed {}, queue size {}", dataInfo, queue.size());
                    }
                    else
                    {
                        LOG.debug("Completed {}, queue empty", dataInfo);
                    }
                }
                if (dataInfoCallback != null)
                    flush(serverStream, dataInfoCallback);
            }

            @Override
            public void failed(Throwable x)
            {
                LOG.debug(x);
                rst(senderStream);
            }
        }

        public Stream getSenderStream()
        {
            return senderStream;
        }

        public Info getInfo()
        {
            return info;
        }

        public Stream getReceiverStream()
        {
            synchronized (queue)
            {
                return receiverStream;
            }
        }
    }

    private class PushStreamPromise extends StreamPromise
    {
        private volatile PushStreamPromise pushStreamPromise;

        private PushStreamPromise(Stream senderStream, PushInfo pushInfo)
        {
            super(senderStream, pushInfo);
        }

        @Override
        public void succeeded(Stream receiverStream)
        {
            super.succeeded(receiverStream);

            LOG.debug("P -> C PushStreamPromise.succeeded() called with pushStreamPromise: {}", pushStreamPromise);

            PushStreamPromise promise = pushStreamPromise;
            if (promise != null)
                receiverStream.push(convertPushInfo((PushInfo)getInfo(), getSenderStream(), receiverStream), pushStreamPromise);
        }

        public void push(PushStreamPromise pushStreamPromise)
        {
            Stream receiverStream = getReceiverStream();

            if (receiverStream != null)
                receiverStream.push(convertPushInfo((PushInfo)getInfo(), getSenderStream(), receiverStream), pushStreamPromise);
            else
                this.pushStreamPromise = pushStreamPromise;
        }
    }

    private class ProxySessionFrameListener extends SessionFrameListener.Adapter
    {
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
                    clientSession.rst(clientRstInfo, Callback.Adapter.INSTANCE);
                }
            }
        }

        @Override
        public void onGoAway(Session serverSession, GoAwayResultInfo goAwayResultInfo)
        {
            serverSessions.values().remove(serverSession);
        }
    }

    private PushInfo convertPushInfo(PushInfo pushInfo, Stream from, Stream to)
    {
        Fields headersToConvert = pushInfo.getHeaders();
        Fields headers = convertHeaders(from, to, headersToConvert);
        return new PushInfo(getTimeout(), TimeUnit.MILLISECONDS, headers, pushInfo.isClose());
    }

    private Fields convertHeaders(Stream from, Stream to, Fields headersToConvert)
    {
        Fields headers = new Fields(headersToConvert, false);
        addResponseProxyHeaders(from, headers);
        customizeResponseHeaders(from, headers);
        convert(from.getSession().getVersion(), to.getSession().getVersion(), headers);
        return headers;
    }
}
