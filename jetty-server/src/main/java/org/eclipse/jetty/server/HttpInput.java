//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * {@link HttpInput} provides an implementation of {@link ServletInputStream} for {@link HttpChannel}.
 * <p>
 * Content may arrive in patterns such as [content(), content(), messageComplete()] so that this class
 * maintains two states: the content state that tells whether there is content to consume and the EOF
 * state that tells whether an EOF has arrived.
 * Only once the content has been consumed the content state is moved to the EOF state.
 */
public class HttpInput extends ServletInputStream implements Runnable
{
    private final static Logger LOG = Log.getLogger(HttpInput.class);
    private final static Content EOF_CONTENT = new EofContent("EOF");
    private final static Content EARLY_EOF_CONTENT = new EofContent("EARLY_EOF");

    private final byte[] _oneByteBuffer = new byte[1];
    private final Deque<Content> _inputQ = new ArrayDeque<>();
    private final HttpChannelState _channelState;
    private ReadListener _listener;
    private State _state = STREAM;
    private long _firstByteTimeStamp = -1;
    private long _contentArrived;
    private long _contentConsumed;
    private long _blockUntil;
    private boolean _waitingForContent;

    public HttpInput(HttpChannelState state)
    {
        _channelState = state;
    }

    protected HttpChannelState getHttpChannelState()
    {
        return _channelState;
    }

    public void recycle()
    {
        synchronized (_inputQ)
        {
            Content item = _inputQ.poll();
            while (item != null)
            {
                item.failed(null);
                item = _inputQ.poll();
            }
            _listener = null;
            _state = STREAM;
            _contentArrived = 0;
            _contentConsumed = 0;
            _firstByteTimeStamp = -1;
            _blockUntil = 0;
        }
    }

    @Override
    public int available()
    {
        int available = 0;
        boolean woken = false;
        synchronized (_inputQ)
        {
            Content content = _inputQ.peek();
            if (content == null)
            {
                try
                {
                    produceContent();
                }
                catch (IOException e)
                {
                    woken = failed(e);
                }
                content = _inputQ.peek();
            }

            if (content != null)
                available = remaining(content);
        }

        if (woken)
            wake();
        return available;
    }

    private void wake()
    {
        HttpChannel channel = _channelState.getHttpChannel();
        Executor executor = channel.getConnector().getServer().getThreadPool();
        executor.execute(channel);
    }

    private long getBlockingTimeout()
    {
        return getHttpChannelState().getHttpChannel().getHttpConfiguration().getBlockingTimeout();
    }

    @Override
    public int read() throws IOException
    {
        int read = read(_oneByteBuffer, 0, 1);
        if (read == 0)
            throw new IllegalStateException("unready read=0");
        return read < 0 ? -1 : _oneByteBuffer[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        synchronized (_inputQ)
        {
            if (!isAsync())
            {
                if (_blockUntil == 0)
                {
                    long blockingTimeout = getBlockingTimeout();
                    if (blockingTimeout > 0)
                        _blockUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(blockingTimeout);
                }
            }

            long minRequestDataRate = _channelState.getHttpChannel().getHttpConfiguration().getMinRequestDataRate();
            if (minRequestDataRate > 0 && _firstByteTimeStamp != -1)
            {
                long period = System.nanoTime() - _firstByteTimeStamp;
                if (period > 0)
                {
                    long minimum_data = minRequestDataRate * TimeUnit.NANOSECONDS.toMillis(period) / TimeUnit.SECONDS.toMillis(1);
                    if (_contentArrived < minimum_data)
                        throw new BadMessageException(HttpStatus.REQUEST_TIMEOUT_408, String.format("Request data rate < %d B/s", minRequestDataRate));
                }
            }

            while (true)
            {
                Content item = nextContent();
                if (item != null)
                {
                    int l = get(item, b, off, len);
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} read {} from {}", this, l, item);

                    consumeNonContent();

                    return l;
                }

                if (!_state.blockForContent(this))
                    return _state.noContent();
            }
        }
    }

    /**
     * Called when derived implementations should attempt to
     * produce more Content and add it via {@link #addContent(Content)}.
     * For protocols that are constantly producing (eg HTTP2) this can
     * be left as a noop;
     *
     * @throws IOException if unable to produce content
     */
    protected void produceContent() throws IOException
    {
    }

    /**
     * Get the next content from the inputQ, calling {@link #produceContent()}
     * if need be.  EOF is processed and state changed.
     *
     * @return the content or null if none available.
     * @throws IOException if retrieving the content fails
     */
    protected Content nextContent() throws IOException
    {
        Content content = pollContent();
        if (content == null && !isFinished())
        {
            produceContent();
            content = pollContent();
        }
        return content;
    }

    /**
     * Poll the inputQ for Content.
     * Consumed buffers and {@link PoisonPillContent}s are removed and
     * EOF state updated if need be.
     *
     * @return Content or null
     */
    protected Content pollContent()
    {
        // Items are removed only when they are fully consumed.
        Content content = _inputQ.peek();
        // Skip consumed items at the head of the queue.
        while (content != null && remaining(content) == 0)
        {
            _inputQ.poll();
            content.succeeded();
            if (LOG.isDebugEnabled())
                LOG.debug("{} consumed {}", this, content);

            if (content == EOF_CONTENT)
            {
                if (_listener == null)
                    _state = EOF;
                else
                {
                    _state = AEOF;
                    boolean woken = _channelState.onReadReady(); // force callback?
                    if (woken)
                        wake();
                }
            }
            else if (content == EARLY_EOF_CONTENT)
                _state = EARLY_EOF;

            content = _inputQ.peek();
        }

        return content;
    }

    /**
     */
    protected void consumeNonContent()
    {
        // Items are removed only when they are fully consumed.
        Content content = _inputQ.peek();
        // Skip consumed items at the head of the queue.
        while (content != null && remaining(content) == 0)
        {
            // Defer EOF until read
            if (content instanceof EofContent)
                break;

            // Consume all other empty content
            _inputQ.poll();
            content.succeeded();
            if (LOG.isDebugEnabled())
                LOG.debug("{} consumed {}", this, content);
            content = _inputQ.peek();
        }
    }

    /**
     * Get the next readable from the inputQ, calling {@link #produceContent()}
     * if need be. EOF is NOT processed and state is not changed.
     *
     * @return the content or EOF or null if none available.
     * @throws IOException if retrieving the content fails
     */
    protected Content nextReadable() throws IOException
    {
        Content content = pollReadable();
        if (content == null && !isFinished())
        {
            produceContent();
            content = pollReadable();
        }
        return content;
    }

    /**
     * Poll the inputQ for Content or EOF.
     * Consumed buffers and non EOF {@link PoisonPillContent}s are removed.
     * EOF state is not updated.
     *
     * @return Content, EOF or null
     */
    protected Content pollReadable()
    {
        // Items are removed only when they are fully consumed.
        Content content = _inputQ.peek();

        // Skip consumed items at the head of the queue except EOF
        while (content != null)
        {
            if (content == EOF_CONTENT || content == EARLY_EOF_CONTENT || remaining(content) > 0)
                return content;

            _inputQ.poll();
            content.succeeded();
            if (LOG.isDebugEnabled())
                LOG.debug("{} consumed {}", this, content);
            content = _inputQ.peek();
        }

        return null;
    }

    /**
     * @param item the content
     * @return how many bytes remain in the given content
     */
    protected int remaining(Content item)
    {
        return item.remaining();
    }

    /**
     * Copies the given content into the given byte buffer.
     *
     * @param content the content to copy from
     * @param buffer  the buffer to copy into
     * @param offset  the buffer offset to start copying from
     * @param length  the space available in the buffer
     * @return the number of bytes actually copied
     */
    protected int get(Content content, byte[] buffer, int offset, int length)
    {
        int l = Math.min(content.remaining(), length);
        content.getContent().get(buffer, offset, l);
        _contentConsumed += l;
        return l;
    }

    /**
     * Consumes the given content.
     * Calls the content succeeded if all content consumed.
     *
     * @param content the content to consume
     * @param length  the number of bytes to consume
     */
    protected void skip(Content content, int length)
    {
        int l = Math.min(content.remaining(), length);
        ByteBuffer buffer = content.getContent();
        buffer.position(buffer.position() + l);
        _contentConsumed += l;
        if (l > 0 && !content.hasContent())
            pollContent(); // hungry succeed

    }

    /**
     * Blocks until some content or some end-of-file event arrives.
     *
     * @throws IOException if the wait is interrupted
     */
    protected void blockForContent() throws IOException
    {
        try
        {
            _waitingForContent = true;
            _channelState.getHttpChannel().onBlockWaitForContent();

            boolean loop = false;
            long timeout = 0;
            while (true)
            {
                if (_blockUntil != 0)
                {
                    timeout = TimeUnit.NANOSECONDS.toMillis(_blockUntil - System.nanoTime());
                    if (timeout <= 0)
                        throw new TimeoutException(String.format("Blocking timeout %d ms", getBlockingTimeout()));
                }

                // This method is called from a loop, so we just
                // need to check the timeout before and after waiting.
                if (loop)
                    break;

                if (LOG.isDebugEnabled())
                    LOG.debug("{} blocking for content timeout={}", this, timeout);
                if (timeout > 0)
                    _inputQ.wait(timeout);
                else
                    _inputQ.wait();

                loop = true;
            }
        }
        catch (Throwable x)
        {
            _channelState.getHttpChannel().onBlockWaitForContentFailure(x);
        }
    }

    /**
     * Adds some content to this input stream.
     *
     * @param item the content to add
     * @return true if content channel woken for read
     */
    public boolean addContent(Content item)
    {
        synchronized (_inputQ)
        {
            _waitingForContent = false;
            if (_firstByteTimeStamp == -1)
                _firstByteTimeStamp = System.nanoTime();
            if (isFinished())
            {
                Throwable failure = isError() ? ((ErrorState)_state).getError() : new EOFException("Content after EOF");
                item.failed(failure);
                return false;
            }
            else
            {
                _contentArrived += item.remaining();
                _inputQ.offer(item);
                if (LOG.isDebugEnabled())
                    LOG.debug("{} addContent {}", this, item);
                return wakeup();
            }
        }
    }

    public boolean hasContent()
    {
        synchronized (_inputQ)
        {
            return _inputQ.size() > 0;
        }
    }

    public void unblock()
    {
        synchronized (_inputQ)
        {
            _inputQ.notify();
        }
    }

    public long getContentConsumed()
    {
        synchronized (_inputQ)
        {
            return _contentConsumed;
        }
    }

    /**
     * This method should be called to signal that an EOF has been
     * detected before all the expected content arrived.
     * <p>
     * Typically this will result in an EOFException being thrown
     * from a subsequent read rather than a -1 return.
     *
     * @return true if content channel woken for read
     */
    public boolean earlyEOF()
    {
        return addContent(EARLY_EOF_CONTENT);
    }

    /**
     * This method should be called to signal that all the expected
     * content arrived.
     *
     * @return true if content channel woken for read
     */
    public boolean eof()
    {
        return addContent(EOF_CONTENT);
    }

    public boolean consumeAll()
    {
        synchronized (_inputQ)
        {
            try
            {
                while (true)
                {
                    Content item = nextContent();
                    if (item == null)
                        break; // Let's not bother blocking

                    skip(item, remaining(item));
                }
                return isFinished() && !isError();
            }
            catch (IOException e)
            {
                LOG.debug(e);
                return false;
            }
        }
    }

    public boolean isError()
    {
        synchronized (_inputQ)
        {
            return _state instanceof ErrorState;
        }
    }

    public boolean isAsync()
    {
        synchronized (_inputQ)
        {
            return _state == ASYNC;
        }
    }

    @Override
    public boolean isFinished()
    {
        synchronized (_inputQ)
        {
            return _state instanceof EOFState;
        }
    }

    @Override
    public boolean isReady()
    {
        try
        {
            synchronized (_inputQ)
            {
                if (_listener == null)
                    return true;
                if (_state instanceof EOFState)
                    return true;
                if (nextReadable() != null)
                    return true;
                _channelState.onReadUnready();
                _waitingForContent = true;
            }
            return false;
        }
        catch (IOException e)
        {
            LOG.ignore(e);
            return true;
        }
    }

    @Override
    public void setReadListener(ReadListener readListener)
    {
        readListener = Objects.requireNonNull(readListener);
        boolean woken = false;
        try
        {
            synchronized (_inputQ)
            {
                if (_listener != null)
                    throw new IllegalStateException("ReadListener already set");
                if (_state != STREAM)
                    throw new IllegalStateException("State " + STREAM + " != " + _state);

                _state = ASYNC;
                _listener = readListener;
                boolean content = nextContent() != null;

                if (content)
                {
                    woken = _channelState.onReadReady();
                }
                else
                {
                    _channelState.onReadUnready();
                    _waitingForContent = true;
                }
            }
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }

        if (woken)
            wake();
    }

    public boolean onIdleTimeout(Throwable x)
    {
        synchronized (_inputQ)
        {
            if (_waitingForContent && !isError())
            {
                x.addSuppressed(new Throwable("HttpInput idle timeout"));
                _state = new ErrorState(x);
                return wakeup();
            }
            return false;
        }
    }

    public boolean failed(Throwable x)
    {
        synchronized (_inputQ)
        {
            // Errors may be reported multiple times, for example
            // a local idle timeout and a remote I/O failure.
            if (isError())
            {
                if (LOG.isDebugEnabled())
                {
                    // Log both the original and current failure
                    // without modifying the original failure.
                    Throwable failure = new Throwable(((ErrorState)_state).getError());
                    failure.addSuppressed(x);
                    LOG.debug(failure);
                }
            }
            else
            {
                // Add a suppressed throwable to capture this stack
                // trace without wrapping/hiding the original failure.
                x.addSuppressed(new Throwable("HttpInput failure"));
                _state = new ErrorState(x);
            }
            return wakeup();
        }
    }

    private boolean wakeup()
    {
        if (_listener != null)
            return _channelState.onReadPossible();
        _inputQ.notify();
        return false;
    }

    /*
     * <p>
     * While this class is-a Runnable, it should never be dispatched in it's own thread. It is a
     * runnable only so that the calling thread can use {@link ContextHandler#handle(Runnable)}
     * to setup classloaders etc.
     * </p>
     */
    @Override
    public void run()
    {
        final Throwable error;
        final ReadListener listener;
        boolean aeof = false;

        synchronized (_inputQ)
        {
            if (_state == EOF)
                return;

            if (_state == AEOF)
            {
                _state = EOF;
                aeof = true;
            }

            listener = _listener;
            error = _state instanceof ErrorState ? ((ErrorState)_state).getError() : null;
        }

        try
        {
            if (error != null)
            {
                _channelState.getHttpChannel().getResponse().getHttpFields().add(HttpConnection.CONNECTION_CLOSE);
                listener.onError(error);
            }
            else if (aeof)
            {
                listener.onAllDataRead();
            }
            else
            {
                listener.onDataAvailable();
            }
        }
        catch (Throwable e)
        {
            LOG.warn(e.toString());
            LOG.debug(e);
            try
            {
                if (aeof || error == null)
                {
                    _channelState.getHttpChannel().getResponse().getHttpFields().add(HttpConnection.CONNECTION_CLOSE);
                    listener.onError(e);
                }
            }
            catch (Throwable e2)
            {
                LOG.warn(e2.toString());
                LOG.debug(e2);
                throw new RuntimeIOException(e2);
            }
        }
    }

    @Override
    public String toString()
    {
        State state;
        long consumed;
        int q;
        Content content;
        synchronized (_inputQ)
        {
            state = _state;
            consumed = _contentConsumed;
            q = _inputQ.size();
            content = _inputQ.peekFirst();
        }
        return String.format("%s@%x[c=%d,q=%d,[0]=%s,s=%s]",
                getClass().getSimpleName(),
                hashCode(),
                consumed,
                q,
                content,
                state);
    }

    public static class PoisonPillContent extends Content
    {
        private final String _name;

        public PoisonPillContent(String name)
        {
            super(BufferUtil.EMPTY_BUFFER);
            _name = name;
        }

        @Override
        public String toString()
        {
            return _name;
        }
    }

    public static class EofContent extends PoisonPillContent
    {
        EofContent(String name)
        {
            super(name);
        }
    }

    public static class Content implements Callback
    {
        private final ByteBuffer _content;

        public Content(ByteBuffer content)
        {
            _content = content;
        }

        @Override
        public boolean isNonBlocking()
        {
            return true;
        }


        public ByteBuffer getContent()
        {
            return _content;
        }

        public boolean hasContent()
        {
            return _content.hasRemaining();
        }

        public int remaining()
        {
            return _content.remaining();
        }

        @Override
        public String toString()
        {
            return String.format("Content@%x{%s}", hashCode(), BufferUtil.toDetailString(_content));
        }
    }


    protected static abstract class State
    {
        public boolean blockForContent(HttpInput in) throws IOException
        {
            return false;
        }

        public int noContent() throws IOException
        {
            return -1;
        }
    }

    protected static class EOFState extends State
    {
    }

    protected class ErrorState extends EOFState
    {
        final Throwable _error;

        ErrorState(Throwable error)
        {
            _error = error;
        }

        public Throwable getError()
        {
            return _error;
        }

        @Override
        public int noContent() throws IOException
        {
            if (_error instanceof IOException)
                throw (IOException)_error;
            throw new IOException(_error);
        }

        @Override
        public String toString()
        {
            return "ERROR:" + _error;
        }
    }

    protected static final State STREAM = new State()
    {
        @Override
        public boolean blockForContent(HttpInput input) throws IOException
        {
            input.blockForContent();
            return true;
        }

        @Override
        public String toString()
        {
            return "STREAM";
        }
    };

    protected static final State ASYNC = new State()
    {
        @Override
        public int noContent() throws IOException
        {
            return 0;
        }

        @Override
        public String toString()
        {
            return "ASYNC";
        }
    };

    protected static final State EARLY_EOF = new EOFState()
    {
        @Override
        public int noContent() throws IOException
        {
            throw new EofException("Early EOF");
        }

        @Override
        public String toString()
        {
            return "EARLY_EOF";
        }
    };

    protected static final State EOF = new EOFState()
    {
        @Override
        public String toString()
        {
            return "EOF";
        }
    };

    protected static final State AEOF = new EOFState()
    {
        @Override
        public String toString()
        {
            return "AEOF";
        }
    };
}
