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

package org.eclipse.jetty.spdy;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.HeadersFrame;
import org.eclipse.jetty.spdy.frames.SynReplyFrame;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class StandardStream implements IStream
{
    private static final Logger logger = Log.getLogger(Stream.class);
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final int id;
    private final byte priority;
    private final ISession session;
    private final IStream associatedStream;
    private final AtomicInteger windowSize = new AtomicInteger();
    private final Set<Stream> pushedStreams = Collections.newSetFromMap(new ConcurrentHashMap<Stream, Boolean>());
    private volatile StreamFrameListener listener;
    private volatile OpenState openState = OpenState.SYN_SENT;
    private volatile CloseState closeState = CloseState.OPENED;
    private volatile boolean reset = false;

    public StandardStream(int id, byte priority, ISession session, IStream associatedStream)
    {
        this.id = id;
        this.priority = priority;
        this.session = session;
        this.associatedStream = associatedStream;
    }

    @Override
    public int getId()
    {
        return id;
    }

    @Override
    public IStream getAssociatedStream()
    {
        return associatedStream;
    }

    @Override
    public Set<Stream> getPushedStreams()
    {
        return pushedStreams;
    }

    @Override
    public void associate(IStream stream)
    {
        pushedStreams.add(stream);
    }

    @Override
    public void disassociate(IStream stream)
    {
        pushedStreams.remove(stream);
    }

    @Override
    public byte getPriority()
    {
        return priority;
    }

    @Override
    public int getWindowSize()
    {
        return windowSize.get();
    }

    @Override
    public void updateWindowSize(int delta)
    {
        int size = windowSize.addAndGet(delta);
        logger.debug("Updated window size {} -> {} for {}", size - delta, size, this);
    }

    @Override
    public ISession getSession()
    {
        return session;
    }

    @Override
    public Object getAttribute(String key)
    {
        return attributes.get(key);
    }

    @Override
    public void setAttribute(String key, Object value)
    {
        attributes.put(key,value);
    }

    @Override
    public Object removeAttribute(String key)
    {
        return attributes.remove(key);
    }

    @Override
    public void setStreamFrameListener(StreamFrameListener listener)
    {
        this.listener = listener;
    }

    public StreamFrameListener getStreamFrameListener()
    {
        return listener;
    }

    @Override
    public void updateCloseState(boolean close, boolean local)
    {
        if (close)
        {
            switch (closeState)
            {
                case OPENED:
                {
                    closeState = local ? CloseState.LOCALLY_CLOSED : CloseState.REMOTELY_CLOSED;
                    break;
                }
                case LOCALLY_CLOSED:
                {
                    if (local)
                        throw new IllegalStateException();
                    else
                        closeState = CloseState.CLOSED;
                    break;
                }
                case REMOTELY_CLOSED:
                {
                    if (local)
                        closeState = CloseState.CLOSED;
                    else
                        throw new IllegalStateException();
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
    }

    @Override
    public void process(ControlFrame frame)
    {
        switch (frame.getType())
        {
            case SYN_STREAM:
            {
                openState = OpenState.SYN_RECV;
                break;
            }
            case SYN_REPLY:
            {
                openState = OpenState.REPLY_RECV;
                SynReplyFrame synReply = (SynReplyFrame)frame;
                updateCloseState(synReply.isClose(), false);
                ReplyInfo replyInfo = new ReplyInfo(synReply.getHeaders(), synReply.isClose());
                notifyOnReply(replyInfo);
                break;
            }
            case HEADERS:
            {
                HeadersFrame headers = (HeadersFrame)frame;
                updateCloseState(headers.isClose(), false);
                HeadersInfo headersInfo = new HeadersInfo(headers.getHeaders(), headers.isClose(), headers.isResetCompression());
                notifyOnHeaders(headersInfo);
                break;
            }
            case RST_STREAM:
            {
                reset = true;
                break;
            }
            default:
            {
                throw new IllegalStateException();
            }
        }
        session.flush();
    }

    @Override
    public void process(DataInfo dataInfo)
    {
        // TODO: in v3 we need to send a rst instead of just ignoring
        // ignore data frame if this stream is remotelyClosed already
        if (isRemotelyClosed())
        {
            logger.debug("Stream is remotely closed, ignoring {}", dataInfo);
            return;
        }

        if (!canReceive())
        {
            logger.debug("Protocol error receiving {}, resetting" + dataInfo);
            session.rst(new RstInfo(getId(), StreamStatus.PROTOCOL_ERROR));
            return;
        }

        updateCloseState(dataInfo.isClose(), false);
        notifyOnData(dataInfo);
        session.flush();
    }

    private void notifyOnReply(ReplyInfo replyInfo)
    {
        final StreamFrameListener listener = this.listener;
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking reply callback with {} on listener {}", replyInfo, listener);
                listener.onReply(this, replyInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
        catch (Error x)
        {
            logger.info("Exception while notifying listener " + listener, x);
            throw x;
        }
    }

    private void notifyOnHeaders(HeadersInfo headersInfo)
    {
        final StreamFrameListener listener = this.listener;
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking headers callback with {} on listener {}", headersInfo, listener);
                listener.onHeaders(this, headersInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
        catch (Error x)
        {
            logger.info("Exception while notifying listener " + listener, x);
            throw x;
        }
    }

    private void notifyOnData(DataInfo dataInfo)
    {
        final StreamFrameListener listener = this.listener;
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking data callback with {} on listener {}", dataInfo, listener);
                listener.onData(this, dataInfo);
                logger.debug("Invoked data callback with {} on listener {}", dataInfo, listener);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener, x);
        }
        catch (Error x)
        {
            logger.info("Exception while notifying listener " + listener, x);
            throw x;
        }
    }

    @Override
    public Future<Stream> syn(SynInfo synInfo)
    {
        Promise<Stream> result = new Promise<>();
        syn(synInfo,0,TimeUnit.MILLISECONDS,result);
        return result;
    }

    @Override
    public void syn(SynInfo synInfo, long timeout, TimeUnit unit, Handler<Stream> handler)
    {
        if (isClosed() || isReset())
        {
            handler.failed(this, new StreamException(getId(), StreamStatus.STREAM_ALREADY_CLOSED));
            return;
        }
        PushSynInfo pushSynInfo = new PushSynInfo(getId(), synInfo);
        session.syn(pushSynInfo, null, timeout, unit, handler);
    }

    @Override
    public Future<Void> reply(ReplyInfo replyInfo)
    {
        Promise<Void> result = new Promise<>();
        reply(replyInfo,0,TimeUnit.MILLISECONDS,result);
        return result;
    }

    @Override
    public void reply(ReplyInfo replyInfo, long timeout, TimeUnit unit, Handler<Void> handler)
    {
        if (isUnidirectional())
            throw new IllegalStateException("Protocol violation: cannot send SYN_REPLY frames in unidirectional streams");
        openState = OpenState.REPLY_SENT;
        updateCloseState(replyInfo.isClose(), true);
        SynReplyFrame frame = new SynReplyFrame(session.getVersion(), replyInfo.getFlags(), getId(), replyInfo.getHeaders());
        session.control(this, frame, timeout, unit, handler, null);
    }

    @Override
    public Future<Void> data(DataInfo dataInfo)
    {
        Promise<Void> result = new Promise<>();
        data(dataInfo,0,TimeUnit.MILLISECONDS,result);
        return result;
    }

    @Override
    public void data(DataInfo dataInfo, long timeout, TimeUnit unit, Handler<Void> handler)
    {
        if (!canSend())
        {
            session.rst(new RstInfo(getId(), StreamStatus.PROTOCOL_ERROR));
            throw new IllegalStateException("Protocol violation: cannot send a DATA frame before a SYN_REPLY frame");
        }
        if (isLocallyClosed())
        {
            session.rst(new RstInfo(getId(), StreamStatus.PROTOCOL_ERROR));
            throw new IllegalStateException("Protocol violation: cannot send a DATA frame on a closed stream");
        }

        // Cannot update the close state here, because the data that we send may
        // be flow controlled, so we need the stream to update the window size.
        session.data(this, dataInfo, timeout, unit, handler, null);
    }

    @Override
    public Future<Void> headers(HeadersInfo headersInfo)
    {
        Promise<Void> result = new Promise<>();
        headers(headersInfo,0,TimeUnit.MILLISECONDS,result);
        return result;
    }

    @Override
    public void headers(HeadersInfo headersInfo, long timeout, TimeUnit unit, Handler<Void> handler)
    {
        if (!canSend())
        {
            session.rst(new RstInfo(getId(), StreamStatus.PROTOCOL_ERROR));
            throw new IllegalStateException("Protocol violation: cannot send a HEADERS frame before a SYN_REPLY frame");
        }
        if (isLocallyClosed())
        {
            session.rst(new RstInfo(getId(), StreamStatus.PROTOCOL_ERROR));
            throw new IllegalStateException("Protocol violation: cannot send a HEADERS frame on a closed stream");
        }

        updateCloseState(headersInfo.isClose(), true);
        HeadersFrame frame = new HeadersFrame(session.getVersion(), headersInfo.getFlags(), getId(), headersInfo.getHeaders());
        session.control(this, frame, timeout, unit, handler, null);
    }

    @Override
    public boolean isUnidirectional()
    {
        return associatedStream != null;
    }

    @Override
    public boolean isReset()
    {
        return reset;
    }

    @Override
    public boolean isHalfClosed()
    {
        CloseState closeState = this.closeState;
        return closeState == CloseState.LOCALLY_CLOSED || closeState == CloseState.REMOTELY_CLOSED || closeState == CloseState.CLOSED;
    }

    @Override
    public boolean isClosed()
    {
        return closeState == CloseState.CLOSED;
    }

    private boolean isLocallyClosed()
    {
        CloseState closeState = this.closeState;
        return closeState == CloseState.LOCALLY_CLOSED || closeState == CloseState.CLOSED;
    }

    private boolean isRemotelyClosed()
    {
        CloseState closeState = this.closeState;
        return closeState == CloseState.REMOTELY_CLOSED || closeState == CloseState.CLOSED;
    }

    @Override
    public String toString()
    {
        return String.format("stream=%d v%d windowSize=%db reset=%s %s %s", getId(), session.getVersion(), getWindowSize(), isReset(), openState, closeState);
    }

    private boolean canSend()
    {
        OpenState openState = this.openState;
        return openState == OpenState.SYN_SENT || openState == OpenState.REPLY_RECV || openState == OpenState.REPLY_SENT;
    }

    private boolean canReceive()
    {
        OpenState openState = this.openState;
        return openState == OpenState.SYN_RECV || openState == OpenState.REPLY_RECV || openState == OpenState.REPLY_SENT;
    }

    private enum OpenState
    {
        SYN_SENT, SYN_RECV, REPLY_SENT, REPLY_RECV
    }

    private enum CloseState
    {
        OPENED, LOCALLY_CLOSED, REMOTELY_CLOSED, CLOSED
    }
}
