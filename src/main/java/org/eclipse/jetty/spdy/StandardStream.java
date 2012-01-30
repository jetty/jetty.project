package org.eclipse.jetty.spdy;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamStatus;
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
    private final AtomicInteger windowSize = new AtomicInteger(65535);
    private final ISession session;
    private final SynStreamFrame frame;
    private volatile FrameListener frameListener;
    private volatile boolean opened;
    private volatile boolean halfClosed;
    private volatile boolean closed;

    public StandardStream(ISession session, SynStreamFrame frame)
    {
        this.session = session;
        this.frame = frame;
        this.halfClosed = frame.isClose();
    }

    @Override
    public int getId()
    {
        return frame.getStreamId();
    }

    @Override
    public short getVersion()
    {
        return frame.getVersion();
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
        logger.debug("Updated window size by {}, new size {}", delta, size);
    }

    @Override
    public Session getSession()
    {
        return session;
    }

    public boolean isHalfClosed()
    {
        return halfClosed;
    }

    @Override
    public void setFrameListener(FrameListener frameListener)
    {
        this.frameListener = frameListener;
    }

    @Override
    public void updateCloseState(boolean close)
    {
        if (close)
        {
            if (isHalfClosed())
                closed = true;
            else
                halfClosed = true;
        }
    }

    @Override
    public void handle(ControlFrame frame)
    {
        switch (frame.getType())
        {
            case SYN_STREAM:
            {
                opened = true;
                break;
            }
            case SYN_REPLY:
            {
                opened = true;
                SynReplyFrame synReply = (SynReplyFrame)frame;
                updateCloseState(synReply.isClose());
                notifyOnReply(synReply);
                break;
            }
            case HEADERS:
            {
                HeadersFrame headers = (HeadersFrame)frame;
                updateCloseState(headers.isClose());
                notifyOnHeaders(headers);
                break;
            }
            case WINDOW_UPDATE:
            {
                WindowUpdateFrame windowUpdate = (WindowUpdateFrame)frame;
                updateWindowSize(windowUpdate.getWindowDelta());
                break;
            }
            default:
            {
                throw new IllegalStateException();
            }
        }
    }

    @Override
    public void handle(DataFrame dataFrame, ByteBuffer data)
    {
        if (!opened)
        {
            session.rst(getVersion(), new RstInfo(getId(), StreamStatus.PROTOCOL_ERROR));
            return;
        }

        updateCloseState(dataFrame.isClose());
        int length = data.remaining();

        // TODO: here we should do decompression if the frame contains compressed data
        // because the decompressor is per-stream in case of data frames

        notifyOnData(dataFrame, data);
        if (!isClosed())
        {
            // Send the window update after having notified
            // the application listeners because they may block
            windowUpdate(length);
        }
    }

    private void windowUpdate(int delta)
    {
        try
        {
            // TODO: if the read buffer is small, but the default window size is big,
            // we will send many window update frames... perhaps we can delay
            // window update frames until we have a bigger delta to send
            WindowUpdateFrame windowUpdateFrame = new WindowUpdateFrame(getVersion(), getId(), delta);
            session.control(this, windowUpdateFrame);
        }
        catch (StreamException x)
        {
            logger.debug("Could not send window update on stream " + this, x);
            session.rst(getVersion(), new RstInfo(getId(), x.getStreamStatus()));
        }
    }

    private void notifyOnReply(SynReplyFrame synReply)
    {
        final FrameListener frameListener = this.frameListener;
        try
        {
            if (frameListener != null)
            {
                logger.debug("Invoking reply callback with frame {} on listener {}", synReply, frameListener);
                frameListener.onReply(this, new ReplyInfo(synReply.getHeaders(), synReply.isClose()));
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + frameListener, x);
        }
    }

    private void notifyOnHeaders(HeadersFrame frame)
    {
        final FrameListener frameListener = this.frameListener;
        try
        {
            if (frameListener != null)
            {
                logger.debug("Invoking headers callback with frame {} on listener {}", frame, frameListener);
                frameListener.onHeaders(this, new HeadersInfo(frame.getHeaders(), frame.isClose(), frame.isResetCompression()));
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + frameListener, x);
        }
    }

    private void notifyOnData(DataFrame frame, ByteBuffer data)
    {
        final FrameListener frameListener = this.frameListener;
        try
        {
            if (frameListener != null)
            {
                logger.debug("Invoking data callback with frame {} on listener {}", frame, frameListener);
                frameListener.onData(this, new ByteBufferDataInfo(data, frame.isClose(), frame.isCompress()));
            }
        }
        catch (Exception x)
        {
            logger.info("Exception while notifying listener " + frameListener, x);
        }
    }

    @Override
    public void reply(ReplyInfo replyInfo)
    {
        try
        {
            updateCloseState(replyInfo.isClose());
            SynReplyFrame frame = new SynReplyFrame(getVersion(), replyInfo.getFlags(), getId(), replyInfo.getHeaders());
            session.control(this, frame);
        }
        catch (StreamException x)
        {
            logger.debug("Could not send reply on stream " + this, x);
            session.rst(getVersion(), new RstInfo(getId(), x.getStreamStatus()));
        }
    }

    @Override
    public void data(DataInfo dataInfo)
    {
        updateCloseState(dataInfo.isClose());
        session.data(this, dataInfo);
    }

    @Override
    public void headers(HeadersInfo headersInfo)
    {
        try
        {
            updateCloseState(headersInfo.isClose());
            HeadersFrame frame = new HeadersFrame(getVersion(), headersInfo.getFlags(), getId(), headersInfo.getHeaders());
            session.control(this, frame);
        }
        catch (StreamException x)
        {
            logger.debug("Could not send headers on stream " + this, x);
            session.rst(getVersion(), new RstInfo(getId(), x.getStreamStatus()));
        }
    }

    @Override
    public boolean isClosed()
    {
        return closed;
    }

    @Override
    public String toString()
    {
        return "stream=" + getId() + " v" + getVersion() + " closed=" + (isClosed() ? "true" : isHalfClosed() ? "half" : "false");
    }
}
