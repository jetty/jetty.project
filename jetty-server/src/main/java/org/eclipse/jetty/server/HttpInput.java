//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p> While this class is-a Runnable, it should never be dispatched in it's own thread. It is a runnable only so that the calling thread can use {@link
 * ContextHandler#handle(Runnable)} to setup classloaders etc. </p>
 */
public class HttpInput extends ServletInputStream implements Runnable
{
    private static final Logger LOG = Log.getLogger(HttpInput.class);

    private final byte[] _oneByteBuffer = new byte[1];

    private final HttpChannelState _channelState;
    private final ContentProducer _contentProducer = new ContentProducer();
    // This semaphore is only used in blocking mode, and a standard lock with a condition variable
    // cannot work here because there is a race condition between the _contentProducer.read() call
    // and the blockForContent() call: content can be produced any time between these two calls so
    // the call to unblock() done by the content-producing thread to wake up the user thread executing read()
    // must 'remember' the unblock() call, such as if it happens before the thread executing read() reaches the
    // blockForContent() method, it will not get stuck in it forever waiting for an unblock() call it missed.
    private final Semaphore _semaphore = new Semaphore(0);

    private Eof _eof = Eof.NOT_YET;
    private Throwable _error;
    private ReadListener _readListener;
    private long _firstByteTimeStamp = Long.MIN_VALUE;

    public HttpInput(HttpChannelState state)
    {
        _channelState = state;
    }

    /* HttpInput */

    public void recycle()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("recycle");
        _contentProducer.recycle();
        _eof = Eof.NOT_YET;
        _error = null;
        _readListener = null;
        _firstByteTimeStamp = Long.MIN_VALUE;
    }

    /**
     * @return The current Interceptor, or null if none set
     */
    public Interceptor getInterceptor()
    {
        return _contentProducer.getInterceptor();
    }

    /**
     * Set the interceptor.
     *
     * @param interceptor The interceptor to use.
     */
    public void setInterceptor(Interceptor interceptor)
    {
        _contentProducer.setInterceptor(interceptor);
    }

    /**
     * Set the {@link Interceptor}, chaining it to the existing one if
     * an {@link Interceptor} is already set.
     *
     * @param interceptor the next {@link Interceptor} in a chain
     */
    public void addInterceptor(Interceptor interceptor)
    {
        Interceptor currentInterceptor = _contentProducer.getInterceptor();
        if (currentInterceptor == null)
            _contentProducer.setInterceptor(interceptor);
        else
            _contentProducer.setInterceptor(new ChainedInterceptor(currentInterceptor, interceptor));
    }

    /**
     * Called by channel when asynchronous IO needs to produce more content
     */
    public void asyncReadProduce()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("asyncReadProduce {}", _contentProducer);
        produceContent();
    }

    /**
     * Adds some content to this input stream.
     *
     * @param content the content to add
     */
    public void addContent(Content content)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("addContent {} {}", content, _contentProducer);
        if (_firstByteTimeStamp == Long.MIN_VALUE)
        {
            _firstByteTimeStamp = System.nanoTime();
            if (_firstByteTimeStamp == Long.MIN_VALUE)
                _firstByteTimeStamp++;
        }
        _contentProducer.addContent(content);
        if (isAsync() && _contentProducer.available(this::produceContent) > 0)
            _channelState.onContentAdded();
    }

    public boolean hasContent()
    {
        return _contentProducer.hasRawContent();
    }

    // There are 3 sources which can call this method in parallel:
    // 1) HTTP2 read() that has a demand served on the app thread;
    // 2) HTTP2 read() that has a demand served by a server thread;
    // 3) onIdleTimeout called by a server thread;
    // which means the semaphore can have up to 2 permits.
    public void unblock()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("signalling blocked thread to wake up");
        if (!isError() && !_eof.isEof() && _semaphore.availablePermits() > 1)
            throw new AssertionError("Only one thread should call unblock and only if we are blocked");
        _semaphore.release();
    }

    public long getContentLength()
    {
        return _contentProducer.getRawContentArrived();
    }

    public long getContentReceived()
    {
        return getContentLength();
    }

    /**
     * This method should be called to signal that an EOF has been detected before all the expected content arrived.
     * <p>
     * Typically this will result in an EOFException being thrown from a subsequent read rather than a -1 return.
     *
     * @return true if content channel woken for read
     */
    public boolean earlyEOF()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("received early EOF");
        _eof = Eof.EARLY_EOF;
        if (isAsync())
            return _channelState.onContentAdded();
        unblock();
        return false;
    }

    /**
     * This method should be called to signal that all the expected content arrived.
     *
     * @return true if content channel woken for read
     */
    public boolean eof()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("received EOF");
        _eof = Eof.EOF;
        if (isAsync())
            return _channelState.onContentAdded();
        unblock();
        return false;
    }

    public boolean consumeAll()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("consume all");
        _contentProducer.consumeTransformedContent(this::failContent, new IOException("Unconsumed content"));
        if (_eof.isEof())
            _eof = Eof.CONSUMED_EOF;

        if (isFinished())
            return !isError();

        _eof = Eof.EARLY_EOF;
        return false;
    }

    public boolean isError()
    {
        return _error != null;
    }

    public boolean isAsync()
    {
        return _readListener != null;
    }

    public boolean onIdleTimeout(Throwable x)
    {
        boolean neverDispatched = _channelState.isIdle();
        boolean waitingForContent = _contentProducer.available(this::produceContent) == 0 && !_eof.isEof();
        if ((waitingForContent || neverDispatched) && !isError())
        {
            x.addSuppressed(new Throwable("HttpInput idle timeout"));
            _error = x;
            if (isAsync())
                return _channelState.onContentAdded();
            unblock();
        }
        return false;
    }

    public boolean failed(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failed " + x);
        if (_error != null && _error != x)
            _error.addSuppressed(x);
        else
            _error = x;

        if (isAsync())
            return _channelState.onContentAdded();
        unblock();
        return false;
    }

    /* ServletInputStream */

    @Override
    public boolean isFinished()
    {
        boolean finished = !_contentProducer.hasRawContent() && _eof.isConsumed();
        if (LOG.isDebugEnabled())
            LOG.debug("isFinished? {}", finished);
        return finished;
    }

    @Override
    public boolean isReady()
    {
        // calling _contentProducer.available() might change the _eof state, so the following test order matters
        if (_contentProducer.available(this::produceContent) > 0 || _eof.isEof())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("isReady? true");
            return true;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("isReady? false");
        _channelState.onReadUnready();
        return false;
    }

    @Override
    public void setReadListener(ReadListener readListener)
    {
        if (_readListener != null)
            throw new IllegalStateException("ReadListener already set");
        _readListener = Objects.requireNonNull(readListener);
        //illegal if async not started
        if (!_channelState.isAsyncStarted())
            throw new IllegalStateException("Async not started");

        if (LOG.isDebugEnabled())
            LOG.debug("setReadListener error=" + _error + " eof=" + _eof + " " + _contentProducer);
        boolean woken;
        if (isError())
        {
            woken = _channelState.onReadReady();
        }
        else
        {
            if (_contentProducer.available(this::produceContent) > 0)
            {
                woken = _channelState.onReadReady();
            }
            else if (_eof.isEof())
            {
                woken = _channelState.onReadEof();
            }
            else
            {
                _channelState.onReadUnready();
                woken = false;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("setReadListener woken=" + woken);
        if (woken)
            scheduleReadListenerNotification();
    }

    private void scheduleReadListenerNotification()
    {
        HttpChannel channel = _channelState.getHttpChannel();
        channel.execute(channel);
    }

    @Override
    public int read() throws IOException
    {
        int read = read(_oneByteBuffer, 0, 1);
        if (read == 0)
            throw new IOException("unready read=0");
        return read < 0 ? -1 : _oneByteBuffer[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        // Calculate minimum request rate for DOS protection
        long minRequestDataRate = _channelState.getHttpChannel().getHttpConfiguration().getMinRequestDataRate();
        if (minRequestDataRate > 0 && _firstByteTimeStamp != Long.MIN_VALUE)
        {
            long period = System.nanoTime() - _firstByteTimeStamp;
            if (period > 0)
            {
                long minimumData = minRequestDataRate * TimeUnit.NANOSECONDS.toMillis(period) / TimeUnit.SECONDS.toMillis(1);
                if (_contentProducer.getRawContentArrived() < minimumData)
                {
                    BadMessageException bad = new BadMessageException(HttpStatus.REQUEST_TIMEOUT_408,
                        String.format("Request content data rate < %d B/s", minRequestDataRate));
                    if (_channelState.isResponseCommitted())
                        _channelState.getHttpChannel().abort(bad);
                    throw bad;
                }
            }
        }

        while (true)
        {
            // The semaphore's permits must be drained before we call read() because:
            // 1) _contentProducer.read() may call unblock() which enqueues a permit even if the content was produced
            //    by the exact thread that called HttpInput.read(), hence leaving around an unconsumed permit that would
            //    be consumed the next time HttpInput.read() is called, mistakenly believing that content was produced.
            // 2) HTTP2 demand served asynchronously does call unblock which does enqueue a permit in the semaphore;
            //    this permit would then be mistakenly consumed by the next call to blockForContent() once all the produced
            //    content got consumed.
            if (!isAsync())
                _semaphore.drainPermits();
            int read = _contentProducer.read(this::produceContent, b, off, len);
            if (LOG.isDebugEnabled())
                LOG.debug("read produced {} byte(s)", read);
            if (read > 0)
                return read;

            if (LOG.isDebugEnabled())
                LOG.debug("read error = " + _error);
            if (_error != null)
                throw new IOException(_error);

            if (LOG.isDebugEnabled())
                LOG.debug("read EOF = {}", _eof);
            if (_eof.isEarly())
                throw new EofException("Early EOF");

            if (LOG.isDebugEnabled())
                LOG.debug("read async = {}", isAsync());
            if (!isAsync())
            {
                if (_eof.isEof())
                {
                    _eof = Eof.CONSUMED_EOF;
                    if (LOG.isDebugEnabled())
                        LOG.debug("read on EOF, switching to CONSUMED_EOF and returning");
                    return -1;
                }
                if (LOG.isDebugEnabled())
                    LOG.debug("read blocked");
                blockForContent();
                if (LOG.isDebugEnabled())
                    LOG.debug("read unblocked");
            }
            else
            {
                if (_eof.isEof())
                {
                    _eof = Eof.CONSUMED_EOF;
                    boolean wasInAsyncWait = _channelState.onReadEof();
                    if (LOG.isDebugEnabled())
                        LOG.debug("async read on EOF (was in async wait? {}), switching to CONSUMED_EOF and returning", wasInAsyncWait);
                    if (wasInAsyncWait)
                        scheduleReadListenerNotification();
                    return -1;
                }
                else
                {
                    //TODO returning 0 breaks the InputStream contract. Shouldn't IOException be thrown instead?
                    _channelState.getHttpChannel().onAsyncWaitForContent(); // switches on fill interested
                    return 0;
                }
            }
        }
    }

    @Override
    public int available()
    {
        int available = _contentProducer.available(this::produceContent);
        if (LOG.isDebugEnabled())
            LOG.debug("available = {}", available);
        return available;
    }

    private void blockForContent()
    {
        try
        {
            _channelState.getHttpChannel().onBlockWaitForContent(); // switches on fill interested
            if (LOG.isDebugEnabled())
                LOG.debug("waiting for signal to wake up");
            _semaphore.acquire();
            if (LOG.isDebugEnabled())
                LOG.debug("signalled to wake up");
        }
        catch (Throwable x)
        {
            _channelState.getHttpChannel().onBlockWaitForContentFailure(x);
        }
    }

    /* Runnable */

    /*
     * <p> While this class is-a Runnable, it should never be dispatched in it's own thread. It is a runnable only so that the calling thread can use {@link
     * ContextHandler#handle(Runnable)} to setup classloaders etc. </p>
     */
    @Override
    public void run()
    {
        if (!_contentProducer.hasRawContent())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("running has no raw content; error: {}, EOF = {}", _error, _eof);
            if (_error != null || _eof.isEarly())
            {
                // TODO is this necessary to add here?
                _channelState.getHttpChannel().getResponse().getHttpFields().add(HttpConnection.CONNECTION_CLOSE);
                if (_error != null)
                    _readListener.onError(_error);
                else
                    _readListener.onError(new EofException("Early EOF"));
            }
            else if (_eof.isEof())
            {
                try
                {
                    _readListener.onAllDataRead();
                }
                catch (Throwable x)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("running failed onAllDataRead", x);
                    _readListener.onError(x);
                }
            }
            // else: !hasContent() && !error && !EOF -> no-op
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("running has raw content");
            try
            {
                _readListener.onDataAvailable();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("running failed onDataAvailable", x);
                _readListener.onError(x);
            }
        }
    }

    private void produceContent()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("produceContent {}", _contentProducer);
        _channelState.getHttpChannel().produceContent();
    }

    private void failContent(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failContent {} - " + failure, _contentProducer);
        _channelState.getHttpChannel().failContent(failure);
    }

    private enum Eof
    {
        NOT_YET(false, false, false),
        EOF(true, false, false),
        CONSUMED_EOF(true, true, false),
        EARLY_EOF(true, false, true),
        ;

        private final boolean _eof;
        private final boolean _consumed;
        private final boolean _early;

        Eof(boolean eof, boolean consumed, boolean early)
        {
            _eof = eof;
            _consumed = consumed;
            _early = early;
        }

        boolean isEof()
        {
            return _eof;
        }

        boolean isConsumed()
        {
            return _consumed;
        }

        boolean isEarly()
        {
            return _early;
        }
    }

    // All methods of this class have to be synchronized because a HTTP2 reset can call consumeTransformedContent()
    // while nextNonEmptyContent() is executing, hence all accesses to _rawContent and _transformedContent must be
    // mutually excluded.
    // TODO: maybe the locking could be more fine grained, by only protecting the if (null|!null) blocks?
    private static class ContentProducer
    {
        // Note: _rawContent can never be null for as long as _transformedContent is not null.
        private Content _rawContent;
        private Content _transformedContent;
        private long _rawContentArrived;
        private Interceptor _interceptor;
        private Throwable _consumeFailure;

        void recycle()
        {
            synchronized (this)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("recycle {}", this);
                if (_transformedContent == _rawContent)
                    _transformedContent = null;
                if (_transformedContent != null)
                    _transformedContent.failed(null);
                _transformedContent = null;
                if (_rawContent != null)
                    _rawContent.failed(null);
                _rawContent = null;
                _rawContentArrived = 0L;
                if (_interceptor instanceof Destroyable)
                    ((Destroyable)_interceptor).destroy();
                _interceptor = null;
                _consumeFailure = null;
            }
        }

        long getRawContentArrived()
        {
            synchronized (this)
            {
                return _rawContentArrived;
            }
        }

        boolean hasRawContent()
        {
            synchronized (this)
            {
                return _rawContent != null;
            }
        }

        Interceptor getInterceptor()
        {
            synchronized (this)
            {
                return _interceptor;
            }
        }

        void setInterceptor(Interceptor interceptor)
        {
            synchronized (this)
            {
                this._interceptor = interceptor;
            }
        }

        void addContent(Content content)
        {
            synchronized (this)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} addContent {}", this, content);
                if (content == null)
                    throw new AssertionError("Cannot add null content");
                if (_consumeFailure != null)
                {
                    content.failed(_consumeFailure);
                    return;
                }
                if (_rawContent != null)
                    throw new AssertionError("Cannot add new content while current one hasn't been processed");

                _rawContent = content;
                _rawContentArrived += content.remaining();
            }
        }

        void consumeTransformedContent(Consumer<Throwable> failRawContent, Throwable failure)
        {
            synchronized (this)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} consumeTransformedContent", this);
                // start by depleting the current _transformedContent
                if (_transformedContent != null)
                {
                    _transformedContent.skip(_transformedContent.remaining());
                    if (_transformedContent != _rawContent)
                        _transformedContent.failed(failure);
                    _transformedContent = null;
                }

                // don't bother transforming content, directly deplete the raw one
                if (_rawContent != null)
                {
                    _rawContent.skip(_rawContent.remaining());
                    _rawContent.failed(failure);
                    _rawContent = null;
                }

                // fail whatever other content the producer may have
                _consumeFailure = failure;
                failRawContent.accept(failure);
            }
        }

        int available(Runnable rawContentProducer)
        {
            synchronized (this)
            {
                Content content = nextNonEmptyContent(rawContentProducer);
                return content == null ? 0 : content.remaining();
            }
        }

        int read(Runnable rawContentProducer, byte[] b, int off, int len)
        {
            synchronized (this)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} read", this);
                Content content = nextNonEmptyContent(rawContentProducer);
                return content == null ? 0 : content.get(b, off, len);
            }
        }

        private Content nextNonEmptyContent(Runnable rawContentProducer)
        {
            if (_rawContent == null)
            {
                rawContentProducer.run();
                if (_rawContent == null)
                    return null;
            }

            if (_transformedContent != null && _transformedContent.isEmpty())
            {
                if (_transformedContent != _rawContent)
                    _transformedContent.succeeded();
                _transformedContent = null;
            }

            while (_transformedContent == null)
            {
                if (_interceptor != null)
                    _transformedContent = _interceptor.readFrom(_rawContent);
                else
                    _transformedContent = _rawContent;

                if (_transformedContent != null && _transformedContent.isEmpty())
                {
                    if (_transformedContent != _rawContent)
                        _transformedContent.succeeded();
                    _transformedContent = null;
                }

                if (_transformedContent == null)
                {
                    if (_rawContent.isEmpty())
                    {
                        _rawContent.succeeded();
                        _rawContent = null;
                        rawContentProducer.run();
                        if (_rawContent == null)
                            return null;
                    }
                }
            }

            return _transformedContent;
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + "[i=" + _interceptor + ",b=" + _rawContentArrived +
                ",r=" + _rawContent + ",t=" + _transformedContent + "]";
        }
    }

    /**
     * An {@link Interceptor} that chains two other {@link Interceptor}s together.
     * The {@link Interceptor#readFrom(Content)} calls the previous {@link Interceptor}'s
     * {@link Interceptor#readFrom(Content)} and then passes any {@link Content} returned
     * to the next {@link Interceptor}.
     */
    private static class ChainedInterceptor implements Interceptor, Destroyable
    {
        private final Interceptor _prev;
        private final Interceptor _next;

        ChainedInterceptor(Interceptor prev, Interceptor next)
        {
            _prev = prev;
            _next = next;
        }

        Interceptor getPrev()
        {
            return _prev;
        }

        Interceptor getNext()
        {
            return _next;
        }

        @Override
        public Content readFrom(Content content)
        {
            Content c = getPrev().readFrom(content);
            if (c == null)
                return null;
            return getNext().readFrom(c);
        }

        @Override
        public void destroy()
        {
            if (_prev instanceof Destroyable)
                ((Destroyable)_prev).destroy();
            if (_next instanceof Destroyable)
                ((Destroyable)_next).destroy();
        }
    }

    public interface Interceptor
    {
        /**
         * @param content The content to be intercepted.
         * The content will be modified with any data the interceptor consumes, but there is no requirement
         * that all the data is consumed by the interceptor.
         * @return The intercepted content or null if interception is completed for that content.
         */
        Content readFrom(Content content);
    }

    public static class Content implements Callback
    {
        protected final ByteBuffer _content;

        public Content(ByteBuffer content)
        {
            _content = content;
        }

        public ByteBuffer getByteBuffer()
        {
            return _content;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        public int get(byte[] buffer, int offset, int length)
        {
            length = Math.min(_content.remaining(), length);
            _content.get(buffer, offset, length);
            return length;
        }

        public int skip(int length)
        {
            length = Math.min(_content.remaining(), length);
            _content.position(_content.position() + length);
            return length;
        }

        public boolean hasContent()
        {
            return _content.hasRemaining();
        }

        public int remaining()
        {
            return _content.remaining();
        }

        public boolean isEmpty()
        {
            return !_content.hasRemaining();
        }

        @Override
        public String toString()
        {
            return String.format("Content@%x{%s}", hashCode(), BufferUtil.toDetailString(_content));
        }
    }

}
