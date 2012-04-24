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

package org.eclipse.jetty.spdy;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.DataFrame;
import org.eclipse.jetty.spdy.frames.HeadersFrame;
import org.eclipse.jetty.spdy.frames.SynReplyFrame;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;
import org.eclipse.jetty.spdy.frames.WindowUpdateFrame;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class StandardStream implements IStream
{
    private static final Logger logger = Log.getLogger(Stream.class);
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final IStream associatedStream;
    private final SynStreamFrame frame;
    private final ISession session;
    private final AtomicInteger windowSize;
    private final Set<Stream> associatedStreams = Collections.newSetFromMap(new ConcurrentHashMap<Stream, Boolean>());
    private volatile StreamFrameListener listener;
    private volatile OpenState openState = OpenState.SYN_SENT;
    private volatile CloseState closeState = CloseState.OPENED;
    private volatile boolean reset = false;
    private final boolean unidirectional;

    public StandardStream(SynStreamFrame frame, ISession session, int windowSize, IStream associatedStream)
    {
        this.frame = frame;
        this.session = session;
        this.windowSize = new AtomicInteger(windowSize);
        this.associatedStream = associatedStream;
        if (associatedStream != null)
            unidirectional = true;
        else
            unidirectional = false;
    }

    @Override
    public int getId()
    {
        return frame.getStreamId();
    }

    @Override
    public IStream getAssociatedStream()
    {
        return associatedStream;
    }

    @Override
    public Set<Stream> getPushedStreams()
    {
        return associatedStreams;
    }

    @Override
    public void associate(IStream stream)
    {
        associatedStreams.add(stream);
    }

    @Override
    public void disassociate(IStream stream)
    {
        associatedStreams.remove(stream);
    }

    @Override
    public byte getPriority()
    {
        return frame.getPriority();
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
        logger.debug("Updated window size by {}, new window size {}",delta,size);
    }

    @Override
    public Session getSession()
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

    @Override
    public void updateCloseState(boolean close, boolean local)
    {
        if (close)
        {
            switch (closeState)
            {
                case OPENED:
                {
                    closeState = local?CloseState.LOCALLY_CLOSED:CloseState.REMOTELY_CLOSED;
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
                updateCloseState(synReply.isClose(),false);
                ReplyInfo replyInfo = new ReplyInfo(synReply.getHeaders(),synReply.isClose());
                notifyOnReply(replyInfo);
                break;
            }
            case HEADERS:
            {
                HeadersFrame headers = (HeadersFrame)frame;
                updateCloseState(headers.isClose(),false);
                HeadersInfo headersInfo = new HeadersInfo(headers.getHeaders(),headers.isClose(),headers.isResetCompression());
                notifyOnHeaders(headersInfo);
                break;
            }
            case WINDOW_UPDATE:
            {
                WindowUpdateFrame windowUpdate = (WindowUpdateFrame)frame;
                updateWindowSize(windowUpdate.getWindowDelta());
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
    public void process(DataFrame frame, ByteBuffer data)
    {
        // TODO: in v3 we need to send a rst instead of just ignoring
        // ignore data frame if this stream is remotelyClosed already
        if (isHalfClosed() && !isLocallyClosed())
        {
            logger.debug("Ignoring received dataFrame as this stream is remotely closed: " + frame);
            return;
        }

        if (!canReceive())
        {
            logger.debug("Can't receive. Sending rst: " + frame);
            session.rst(new RstInfo(getId(),StreamStatus.PROTOCOL_ERROR));
            return;
        }

        updateCloseState(frame.isClose(),false);

        ByteBufferDataInfo dataInfo = new ByteBufferDataInfo(data,frame.isClose(),frame.isCompress())
        {
            @Override
            public void consume(int delta)
            {
                super.consume(delta);

                // This is the algorithm for flow control.
                // This method may be called multiple times with delta=1, but we only send a window
                // update when the whole dataInfo has been consumed.
                // Other policies may be to send window updates when consumed() is greater than
                // a certain threshold, etc. but for now the policy is not pluggable for simplicity.
                // Note that the frequency of window updates depends on the read buffer, that
                // should not be too smaller than the window size to avoid frequent window updates.
                // Therefore, a pluggable policy should be able to modify the read buffer capacity.
                if (consumed() == length() && !isClosed())
                    windowUpdate(length());
            }
        };
        notifyOnData(dataInfo);
        session.flush();
    }

    private void windowUpdate(int delta)
    {
        if (delta > 0)
        {
            WindowUpdateFrame windowUpdateFrame = new WindowUpdateFrame(session.getVersion(),getId(),delta);
            session.control(this,windowUpdateFrame,0,TimeUnit.MILLISECONDS,null,null);
        }
    }

    private void notifyOnReply(ReplyInfo replyInfo)
    {
        final StreamFrameListener listener = this.listener;
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking reply callback with {} on listener {}",replyInfo,listener);
                listener.onReply(this,replyInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener,x);
        }
    }

    private void notifyOnHeaders(HeadersInfo headersInfo)
    {
        final StreamFrameListener listener = this.listener;
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking headers callback with {} on listener {}",frame,listener);
                listener.onHeaders(this,headersInfo);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener,x);
        }
    }

    private void notifyOnData(DataInfo dataInfo)
    {
        final StreamFrameListener listener = this.listener;
        try
        {
            if (listener != null)
            {
                logger.debug("Invoking data callback with {} on listener {}",dataInfo,listener);
                listener.onData(this,dataInfo);
                logger.debug("Invoked data callback with {} on listener {}",dataInfo,listener);
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + listener,x);
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
            handler.failed(new StreamException(getId(),StreamStatus.STREAM_ALREADY_CLOSED));
            return;
        }
        PushSynInfo pushSynInfo = new PushSynInfo(getId(),synInfo);
        session.syn(pushSynInfo,null,timeout,unit,handler);
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
        openState = OpenState.REPLY_SENT;
        updateCloseState(replyInfo.isClose(),true);
        SynReplyFrame frame = new SynReplyFrame(session.getVersion(),replyInfo.getFlags(),getId(),replyInfo.getHeaders());
        session.control(this,frame,timeout,unit,handler,null);
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
            session.rst(new RstInfo(getId(),StreamStatus.PROTOCOL_ERROR));
            throw new IllegalStateException("Protocol violation: cannot send a DATA frame before a SYN_REPLY frame");
        }
        if (isLocallyClosed())
        {
            session.rst(new RstInfo(getId(),StreamStatus.PROTOCOL_ERROR));
            throw new IllegalStateException("Protocol violation: cannot send a DATA frame on a closed stream");
        }

        // Cannot update the close state here, because the data that we send may
        // be flow controlled, so we need the stream to update the window size.
        session.data(this,dataInfo,timeout,unit,handler,null);
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
            session.rst(new RstInfo(getId(),StreamStatus.PROTOCOL_ERROR));
            throw new IllegalStateException("Protocol violation: cannot send a HEADERS frame before a SYN_REPLY frame");
        }
        if (isLocallyClosed())
        {
            session.rst(new RstInfo(getId(),StreamStatus.PROTOCOL_ERROR));
            throw new IllegalStateException("Protocol violation: cannot send a HEADERS frame on a closed stream");
        }

        updateCloseState(headersInfo.isClose(),true);
        HeadersFrame frame = new HeadersFrame(session.getVersion(),headersInfo.getFlags(),getId(),headersInfo.getHeaders());
        session.control(this,frame,timeout,unit,handler,null);
    }

    @Override
    public boolean isUnidirectional()
    {
        return unidirectional;
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

    @Override
    public String toString()
    {
        return String.format("stream=%d v%d %s",getId(),session.getVersion(),closeState);
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
