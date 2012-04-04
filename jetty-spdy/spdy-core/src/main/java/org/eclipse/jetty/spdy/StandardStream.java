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
import java.util.HashSet;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StandardStream implements IStream
{
    private static final Logger logger = LoggerFactory.getLogger(Stream.class);
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final IStream associatedStream;
    private final SynStreamFrame frame;
    private final ISession session;
    private final AtomicInteger windowSize;
    private Set<IStream> associatedStreams = Collections.emptySet();
    private volatile StreamFrameListener listener;
    private volatile boolean isOpened;
    private volatile boolean isDataSent=false;
    private volatile boolean isHalfClosed;
    private volatile boolean isClosed;

    public StandardStream(SynStreamFrame frame, ISession session, int windowSize, IStream associatedStream)
    {
        this.frame = frame;
        this.session = session;
        this.windowSize = new AtomicInteger(windowSize);
        this.isHalfClosed = frame.isClose();
        this.associatedStream = associatedStream;
    }

    @Override
    public int getId()
    {
        return frame.getStreamId();
    }

    @Override
    public IStream getParentStream()
    {
        return associatedStream;
    }
    
    @Override
    public Set<IStream> getAssociatedStreams()
    {
        return associatedStreams;
    }
    
    @Override
    public void addAssociatedStream(IStream stream)
    {
        if(associatedStreams.isEmpty()){
            associatedStreams = new HashSet<>();
        }
        associatedStreams.add(stream);
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
    public boolean isHalfClosed()
    {
        return isHalfClosed;
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
    public void updateCloseState(boolean close)
    {
        if (close)
        {
            if (isHalfClosed())
            {
                if(associatedStream!=null){
                    //TODO: consider making this a method in IStream
                    associatedStream.getAssociatedStreams().remove(this);
                }
                isClosed = true;
            }
            else
            {
                isHalfClosed = true;
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
                isOpened = true;
                break;
            }
            case SYN_REPLY:
            {
                isOpened = true;
                SynReplyFrame synReply = (SynReplyFrame)frame;
                updateCloseState(synReply.isClose());
                ReplyInfo replyInfo = new ReplyInfo(synReply.getHeaders(),synReply.isClose());
                notifyOnReply(replyInfo);
                break;
            }
            case HEADERS:
            {
                HeadersFrame headers = (HeadersFrame)frame;
                updateCloseState(headers.isClose());
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
                // TODO:
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
        if (!isOpened)
        {
            session.rst(new RstInfo(getId(),StreamStatus.PROTOCOL_ERROR));
            return;
        }

        updateCloseState(frame.isClose());

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
        syn(synInfo,0, TimeUnit.MILLISECONDS, result);
        return result;
    }
    
    @Override
    public void syn(SynInfo synInfo, long timeout, TimeUnit unit, Handler<Stream> handler)
    {
        if(isClosed())
            handler.failed(new IllegalStateException("Stream already closed. No push streams can be created on closed streams."));
        PushSynInfo pushSynInfo = new PushSynInfo(getId(),synInfo);
        session.syn(pushSynInfo,null,0,TimeUnit.MILLISECONDS, handler);
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
        updateCloseState(replyInfo.isClose());
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
        // Cannot update the close state here, because the data that we send may
        // be flow controlled, so we need the stream to update the window size.
        session.data(this,dataInfo,timeout,unit,handler,null);
        isDataSent=true;
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
        updateCloseState(headersInfo.isClose());
        HeadersFrame frame = new HeadersFrame(session.getVersion(),headersInfo.getFlags(),getId(),headersInfo.getHeaders());
        session.control(this,frame,timeout,unit,handler,null);
    }

    @Override
    public boolean isClosed()
    {
        return isClosed;
    }
    
    @Override
    public boolean isDataSent()
    {
        return isDataSent;
    }

    @Override
    public String toString()
    {
        return String.format("stream=%d v%d closed=%s",getId(),session.getVersion(),isClosed()?"true":isHalfClosed()?"half":"false");
    }
}
