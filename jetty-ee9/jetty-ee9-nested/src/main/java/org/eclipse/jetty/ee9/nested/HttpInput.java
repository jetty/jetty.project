//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p> While this class is-a Runnable, it should never be dispatched in it's own thread. It is a runnable only so that the calling thread can use {@link
 * Context#run(Runnable)} to setup classloaders etc. </p>
 */
public class HttpInput extends ServletInputStream implements Runnable
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpInput.class);

    private final HttpChannel _httpChannel;
    private final byte[] _oneByteBuffer = new byte[1];
    private BlockingContentProducer _blockingContentProducer;
    private AsyncContentProducer _asyncContentProducer;
    private final LongAdder _contentConsumed = new LongAdder();
    private volatile ContentProducer _contentProducer;
    private volatile boolean _consumedEof;
    private volatile ReadListener _readListener;

    public HttpInput(HttpChannel channel)
    {
        _httpChannel = channel;
        _asyncContentProducer = new AsyncContentProducer(_httpChannel);
        _blockingContentProducer = new BlockingContentProducer(_asyncContentProducer);
        _contentProducer = _blockingContentProducer;
    }

    public void recycle()
    {
        try (AutoLock lock = _contentProducer.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("recycle {}", this);
            _blockingContentProducer.recycle();
        }
    }

    public void reopen()
    {
        try (AutoLock lock = _contentProducer.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("reopen {}", this);
            _blockingContentProducer.reopen();
            _contentProducer = _blockingContentProducer;
            _consumedEof = false;
            _readListener = null;
            _contentConsumed.reset();
        }
    }

    /**
     * @return The current Interceptor, or null if none set
     */
    public Interceptor getInterceptor()
    {
        try (AutoLock lock = _contentProducer.lock())
        {
            return _contentProducer.getInterceptor();
        }
    }

    /**
     * Set the interceptor.
     *
     * @param interceptor The interceptor to use.
     */
    public void setInterceptor(Interceptor interceptor)
    {
        try (AutoLock lock = _contentProducer.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("setting interceptor to {} on {}", interceptor, this);
            _contentProducer.setInterceptor(interceptor);
        }
    }

    /**
     * Set the {@link Interceptor}, chaining it to the existing one if
     * an {@link Interceptor} is already set.
     *
     * @param interceptor the next {@link Interceptor} in a chain
     */
    public void addInterceptor(Interceptor interceptor)
    {
        try (AutoLock lock = _contentProducer.lock())
        {
            Interceptor currentInterceptor = _contentProducer.getInterceptor();
            if (currentInterceptor == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("adding single interceptor: {} on {}", interceptor, this);
                _contentProducer.setInterceptor(interceptor);
            }
            else
            {
                ChainedInterceptor chainedInterceptor = new ChainedInterceptor(currentInterceptor, interceptor);
                if (LOG.isDebugEnabled())
                    LOG.debug("adding chained interceptor: {} on {}", chainedInterceptor, this);
                _contentProducer.setInterceptor(chainedInterceptor);
            }
        }
    }

    private int get(Content.Chunk content, byte[] bytes, int offset, int length)
    {
        length = Math.min(content.remaining(), length);
        content.getByteBuffer().get(bytes, offset, length);
        _contentConsumed.add(length);
        return length;
    }

    public long getContentConsumed()
    {
        return _contentConsumed.sum();
    }

    public long getContentReceived()
    {
        try (AutoLock lock = _contentProducer.lock())
        {
            return _contentProducer.getRawContentArrived();
        }
    }

    public void releaseContent()
    {
        try (AutoLock lock = _contentProducer.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("consumeAll {}", this);
            boolean eof = _contentProducer.releaseContent();
            if (eof)
                _consumedEof = true;
        }
    }

    public boolean consumeAll()
    {
        try (AutoLock lock = _contentProducer.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("consumeAll {}", this);
            boolean atEof = _contentProducer.consumeAll();
            if (atEof)
                _consumedEof = true;

            if (isFinished())
                return !isError();

            return false;
        }
    }

    public boolean isError()
    {
        try (AutoLock lock = _contentProducer.lock())
        {
            boolean error = _contentProducer.isError();
            if (LOG.isDebugEnabled())
                LOG.debug("isError={} {}", error, this);
            return error;
        }
    }

    public boolean isAsync()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("isAsync read listener {} {}", _readListener, this);
        return _readListener != null;
    }

    /* ServletInputStream */

    @Override
    public boolean isFinished()
    {
        boolean finished = _consumedEof;
        if (LOG.isDebugEnabled())
            LOG.debug("isFinished={} {}", finished, this);
        return finished;
    }

    @Override
    public boolean isReady()
    {
        try (AutoLock lock = _contentProducer.lock())
        {
            boolean ready = _contentProducer.isReady();
            if (LOG.isDebugEnabled())
                LOG.debug("isReady={} {}", ready, this);
            return ready;
        }
    }

    @Override
    public void setReadListener(ReadListener readListener)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("setting read listener to {} {}", readListener, this);
        if (_readListener != null)
            throw new IllegalStateException("ReadListener already set");
        //illegal if async not started
        if (!_httpChannel.getState().isAsyncStarted())
            throw new IllegalStateException("Async not started");
        _readListener = Objects.requireNonNull(readListener);

        _contentProducer = _asyncContentProducer;
        // trigger content production
        if (isReady() && _httpChannel.getState().onReadEof()) // onReadEof b/c we want to transition from WAITING to WOKEN
            scheduleReadListenerNotification(); // this is needed by AsyncServletIOTest.testStolenAsyncRead
    }

    public boolean onContentProducible()
    {
        try (AutoLock lock = _contentProducer.lock())
        {
            return _contentProducer.onContentProducible();
        }
    }

    @Override
    public int read() throws IOException
    {
        try (AutoLock lock = _contentProducer.lock())
        {
            int read = read(_oneByteBuffer, 0, 1);
            if (read == 0)
                throw new IOException("unready read=0");
            return read < 0 ? -1 : _oneByteBuffer[0] & 0xFF;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        try (AutoLock lock = _contentProducer.lock())
        {
            // Don't try to get content if no bytes were requested to be read.
            if (len == 0)
                return 0;

            // Calculate minimum request rate for DoS protection
            _contentProducer.checkMinDataRate();

            Content.Chunk content = _contentProducer.nextContent();
            if (content == null)
                throw new IllegalStateException("read on unready input");
            if (!content.isTerminal())
            {
                int read = get(content, b, off, len);
                if (LOG.isDebugEnabled())
                    LOG.debug("read produced {} byte(s) {}", read, this);
                if (!content.hasRemaining())
                    _contentProducer.reclaim(content);
                return read;
            }

            if (content instanceof Content.Chunk.Error errorContent)
            {
                Throwable error = errorContent.getCause();
                if (LOG.isDebugEnabled())
                    LOG.debug("read error={} {}", error, this);
                if (error instanceof IOException)
                    throw (IOException)error;
                throw new IOException(error);
            }

            if (content == Content.Chunk.EOF)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("read at EOF, setting consumed EOF to true {}", this);
                _consumedEof = true;
                // If EOF do we need to wake for allDataRead callback?
                if (onContentProducible())
                    scheduleReadListenerNotification();
                return -1;
            }

            throw new AssertionError("no data, no error and not EOF");
        }
    }

    private void scheduleReadListenerNotification()
    {
        _httpChannel.execute(_httpChannel);
    }

    /**
     * Check if this HttpInput instance has content stored internally, without fetching/parsing
     * anything from the underlying channel.
     * @return true if the input contains content, false otherwise.
     */
    public boolean hasContent()
    {
        try (AutoLock lock = _contentProducer.lock())
        {
            // Do not call _contentProducer.available() as it calls HttpChannel.produceContent()
            // which is forbidden by this method's contract.
            boolean hasContent = _contentProducer.hasContent();
            if (LOG.isDebugEnabled())
                LOG.debug("hasContent={} {}", hasContent, this);
            return hasContent;
        }
    }

    @Override
    public int available()
    {
        try (AutoLock lock = _contentProducer.lock())
        {
            int available = _contentProducer.available();
            if (LOG.isDebugEnabled())
                LOG.debug("available={} {}", available, this);
            return available;
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
        Content.Chunk content;
        try (AutoLock lock = _contentProducer.lock())
        {
            // Call isReady() to make sure that if not ready we register for fill interest.
            if (!_contentProducer.isReady())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("running but not ready {}", this);
                return;
            }
            content = _contentProducer.nextContent();
            if (LOG.isDebugEnabled())
                LOG.debug("running on content {} {}", content, this);
        }

        // This check is needed when a request is started async but no read listener is registered.
        if (_readListener == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("running without a read listener {}", this);
            onContentProducible();
            return;
        }

        if (content.isTerminal())
        {
            if (content instanceof Content.Chunk.Error errorContent)
            {
                Throwable error = errorContent.getCause();
                if (LOG.isDebugEnabled())
                    LOG.debug("running error={} {}", error, this);
                // TODO is this necessary to add here?
                _httpChannel.getResponse().getHttpFields().add(HttpFields.CONNECTION_CLOSE);
                _readListener.onError(error);
            }
            else if (content == Content.Chunk.EOF)
            {
                try
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("running at EOF {}", this);
                    _readListener.onAllDataRead();
                }
                catch (Throwable x)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("running failed onAllDataRead {}", this, x);
                    _readListener.onError(x);
                }
            }
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("running has content {}", this);
            try
            {
                _readListener.onDataAvailable();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("running failed onDataAvailable {}", this, x);
                _readListener.onError(x);
            }
        }
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "@" + hashCode() +
            " cs=" + _httpChannel.getState() +
            " cp=" + _contentProducer +
            " eof=" + _consumedEof;
    }

    /**
     * <p>{@link Content.Chunk} interceptor that can be registered using {@link #setInterceptor(Interceptor)} or
     * {@link #addInterceptor(Interceptor)}.
     * When {@link Content.Chunk} instances are generated, they are passed to the registered interceptor (if any)
     * that is then responsible for providing the actual content that is consumed by {@link #read(byte[], int, int)} and its
     * sibling methods.</p>
     * A minimal implementation could be as simple as:
     * <pre>
     * public HttpInput.Content readFrom(HttpInput.Content content)
     * {
     *     LOGGER.debug("read content: {}", asString(content));
     *     return content;
     * }
     * </pre>
     * which would not do anything with the content besides logging it. A more involved implementation could look like the
     * following:
     * <pre>
     * public HttpInput.Content readFrom(HttpInput.Content content)
     * {
     *     if (content.hasContent())
     *         this.processedContent = processContent(content.getByteBuffer());
     *     if (content.isEof())
     *         disposeResources();
     *     return content.isSpecial() ? content : this.processedContent;
     * }
     * </pre>
     * Implementors of this interface must keep the following in mind:
     * <ul>
     *     <li>Calling {@link Content.Chunk#getByteBuffer()} when {@link Content.Chunk#isTerminal()} returns <code>true</code> throws
     *     {@link IllegalStateException}.</li>
     *     <li>A {@link Content.Chunk} can both be non-special and have {@code content == Content.EOF} return <code>true</code>.</li>
     *     <li>{@link Content.Chunk} extends {@link Callback} to manage the lifecycle of the contained byte buffer. The code calling
     *     {@link #readFrom(Content.Chunk)} is responsible for managing the lifecycle of both the passed and the returned content
     *     instances, once {@link ByteBuffer#hasRemaining()} returns <code>false</code> {@code HttpInput} will make sure
     *     {@link Callback#succeeded()} is called, or {@link Callback#failed(Throwable)} if an error occurs.</li>
     *     <li>After {@link #readFrom(Content.Chunk)} is called for the first time, subsequent {@link #readFrom(Content.Chunk)} calls will
     *     occur only after the contained byte buffer is empty (see above) or at any time if the returned content was special.</li>
     *     <li>Once {@link #readFrom(Content.Chunk)} returned a special content, subsequent calls to {@link #readFrom(Content.Chunk)} must
     *     always return the same special content.</li>
     *     <li>Implementations implementing both this interface and {@link Destroyable} will have their
     *     {@link Destroyable#destroy()} method called when {@link #recycle()} is called.</li>
     * </ul>
     */
    public interface Interceptor
    {
        /**
         * @param content The content to be intercepted.
         * The content will be modified with any data the interceptor consumes. There is no requirement
         * that all the data is consumed by the interceptor but at least one byte must be consumed
         * unless the returned content is the passed content instance.
         * @return The intercepted content or null if interception is completed for that content.
         */
        Content.Chunk readFrom(Content.Chunk content);
    }

    /**
     * An {@link Interceptor} that chains two other {@link Interceptor}s together.
     * The {@link Interceptor#readFrom(Content.Chunk)} calls the previous {@link Interceptor}'s
     * {@link Interceptor#readFrom(Content.Chunk)} and then passes any {@link Content.Chunk} returned
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
        public Content.Chunk readFrom(Content.Chunk content)
        {
            Content.Chunk c = getPrev().readFrom(content);
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

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + "@" + hashCode() + " [p=" + _prev + ",n=" + _next + "]";
        }
    }
}
