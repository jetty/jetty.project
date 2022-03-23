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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.BufferUtil;
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

    private final ServletChannel _servletChannel;
    private final byte[] _oneByteBuffer = new byte[1];
    private BlockingContentProducer _blockingContentProducer;
    private AsyncContentProducer _asyncContentProducer;
    private ServletRequestState _channelState;
    private final LongAdder _contentConsumed = new LongAdder();
    private volatile ContentProducer _contentProducer;
    private volatile boolean _consumedEof;
    private volatile ReadListener _readListener;

    public HttpInput(ServletChannel channel)
    {
        _servletChannel = channel;
    }

    public void init()
    {
        _channelState = _servletChannel.getState(); // TODO can we change lifecycle so this is known in constructor and can be final
        _asyncContentProducer = new AsyncContentProducer(_servletChannel); // TODO avoid object creation or recycle
        _blockingContentProducer = new BlockingContentProducer(_asyncContentProducer);  // TODO avoid object creation or recycle
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

    private int get(Content content, byte[] bytes, int offset, int length)
    {
        int consumed = content.get(bytes, offset, length);
        _contentConsumed.add(consumed);
        return consumed;
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
        if (!_channelState.isAsyncStarted())
            throw new IllegalStateException("Async not started");
        _readListener = Objects.requireNonNull(readListener);

        _contentProducer = _asyncContentProducer;
        // trigger content production
        if (isReady() && _channelState.onReadEof()) // onReadEof b/c we want to transition from WAITING to WOKEN
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

            Content content = _contentProducer.nextContent();
            if (content == null)
                throw new IllegalStateException("read on unready input");
            if (!content.isSpecial())
            {
                int read = get(content, b, off, len);
                if (LOG.isDebugEnabled())
                    LOG.debug("read produced {} byte(s) {}", read, this);
                if (content.isEmpty())
                    _contentProducer.reclaim(content);
                return read;
            }

            Throwable error = content.getError();
            if (LOG.isDebugEnabled())
                LOG.debug("read error={} {}", error, this);
            if (error != null)
            {
                if (error instanceof IOException)
                    throw (IOException)error;
                throw new IOException(error);
            }

            if (content.isEof())
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
        _servletChannel.execute(_servletChannel);
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
        Content content;
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

        if (content.isSpecial())
        {
            Throwable error = content.getError();
            if (error != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("running error={} {}", error, this);
                // TODO is this necessary to add here?
                _servletChannel.getResponse().getHeaders().add(HttpFields.CONNECTION_CLOSE);
                _readListener.onError(error);
            }
            else if (content.isEof())
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
            " cs=" + _channelState +
            " cp=" + _contentProducer +
            " eof=" + _consumedEof;
    }

    /**
     * <p>{@link Content} interceptor that can be registered using {@link #setInterceptor(Interceptor)} or
     * {@link #addInterceptor(Interceptor)}.
     * When {@link Content} instances are generated, they are passed to the registered interceptor (if any)
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
     *     <li>Calling {@link Content#getByteBuffer()} when {@link Content#isSpecial()} returns <code>true</code> throws
     *     {@link IllegalStateException}.</li>
     *     <li>A {@link Content} can both be non-special and have {@link Content#isEof()} return <code>true</code>.</li>
     *     <li>{@link Content} extends {@link Callback} to manage the lifecycle of the contained byte buffer. The code calling
     *     {@link #readFrom(Content)} is responsible for managing the lifecycle of both the passed and the returned content
     *     instances, once {@link ByteBuffer#hasRemaining()} returns <code>false</code> {@code HttpInput} will make sure
     *     {@link Callback#succeeded()} is called, or {@link Callback#failed(Throwable)} if an error occurs.</li>
     *     <li>After {@link #readFrom(Content)} is called for the first time, subsequent {@link #readFrom(Content)} calls will
     *     occur only after the contained byte buffer is empty (see above) or at any time if the returned content was special.</li>
     *     <li>Once {@link #readFrom(Content)} returned a special content, subsequent calls to {@link #readFrom(Content)} must
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
        Content readFrom(Content content);
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

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + "@" + hashCode() + " [p=" + _prev + ",n=" + _next + "]";
        }
    }

    /**
     * A content represents the production of a {@link org.eclipse.jetty.server.Content} returned by {@link Request#readContent()} ()}.
     * There are two fundamental types of content: special and non-special.
     * Non-special content always wraps a byte buffer that can be consumed and must be recycled once it is empty, either
     * via {@link #succeeded()} or {@link #failed(Throwable)}.
     * Special content indicates a special event, like EOF or an error and never wraps a byte buffer. Calling
     * {@link #succeeded()} or {@link #failed(Throwable)} on those have no effect.
     * @deprecated This class should be removed in favour of the {@link org.eclipse.jetty.server.Content} class.
     */
    @Deprecated
    public static class Content implements Callback
    {
        protected final ByteBuffer _content;

        public Content(ByteBuffer content)
        {
            _content = content;
        }

        /**
         * Get the wrapped byte buffer. Throws {@link IllegalStateException} if the content is special.
         * @return the wrapped byte buffer.
         */
        public ByteBuffer getByteBuffer()
        {
            return _content;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        /**
         * Read the wrapped byte buffer. Throws {@link IllegalStateException} if the content is special.
         * @param buffer The array into which bytes are to be written.
         * @param offset The offset within the array of the first byte to be written.
         * @param length The maximum number of bytes to be written to the given array.
         * @return The amount of bytes read from the buffer.
         */
        public int get(byte[] buffer, int offset, int length)
        {
            length = Math.min(_content.remaining(), length);
            _content.get(buffer, offset, length);
            return length;
        }

        /**
         * Skip some bytes from the buffer. Has no effect on a special content.
         * @param length How many bytes to skip.
         * @return How many bytes were skipped.
         */
        public int skip(int length)
        {
            length = Math.min(_content.remaining(), length);
            _content.position(_content.position() + length);
            return length;
        }

        /**
         * Check if there is at least one byte left in the buffer.
         * Always false on a special content.
         * @return true if there is at least one byte left in the buffer.
         */
        public boolean hasContent()
        {
            return _content.hasRemaining();
        }

        /**
         * Get the number of bytes remaining in the buffer.
         * Always 0 on a special content.
         * @return the number of bytes remaining in the buffer.
         */
        public int remaining()
        {
            return _content.remaining();
        }

        /**
         * Check if the buffer is empty.
         * Always true on a special content.
         * @return true if there is 0 byte left in the buffer.
         */
        public boolean isEmpty()
        {
            return !_content.hasRemaining();
        }

        /**
         * Check if the content is special. A content is deemed special
         * if it does not hold bytes but rather conveys a special event,
         * like when EOF has been reached or an error has occurred.
         * @return true if the content is special, false otherwise.
         */
        public boolean isSpecial()
        {
            return false;
        }

        /**
         * Check if EOF was reached. Both special and non-special content
         * can have this flag set to true but in the case of non-special content,
         * this can be interpreted as a hint as it is always going to be followed
         * by another content that is both special and EOF.
         * @return true if EOF was reached, false otherwise.
         */
        public boolean isEof()
        {
            return false;
        }

        /**
         * Get the reported error. Only special contents can have an error.
         * @return the error or null if there is none.
         */
        public Throwable getError()
        {
            return null;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{%s,spc=%s,eof=%s,err=%s}", getClass().getSimpleName(), hashCode(),
                BufferUtil.toDetailString(_content), isSpecial(), isEof(), getError());
        }
    }

    /**
     * Simple non-special content wrapper allow overriding the EOF flag.
     */
    public static class WrappingContent extends Content
    {
        private final Content _delegate;
        private final boolean _eof;

        public WrappingContent(Content delegate, boolean eof)
        {
            super(delegate.getByteBuffer());
            _delegate = delegate;
            _eof = eof;
        }

        @Override
        public boolean isEof()
        {
            return _eof;
        }

        @Override
        public void succeeded()
        {
            _delegate.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            _delegate.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _delegate.getInvocationType();
        }
    }

    /**
     * Abstract class that implements the standard special content behavior.
     */
    public abstract static class SpecialContent extends Content
    {
        public SpecialContent()
        {
            super(null);
        }

        @Override
        public final ByteBuffer getByteBuffer()
        {
            throw new IllegalStateException(this + " has no buffer");
        }

        @Override
        public final int get(byte[] buffer, int offset, int length)
        {
            throw new IllegalStateException(this + " has no buffer");
        }

        @Override
        public final int skip(int length)
        {
            return 0;
        }

        @Override
        public final boolean hasContent()
        {
            return false;
        }

        @Override
        public final int remaining()
        {
            return 0;
        }

        @Override
        public final boolean isEmpty()
        {
            return true;
        }

        @Override
        public final boolean isSpecial()
        {
            return true;
        }
    }

    /**
     * EOF special content.
     */
    public static final class EofContent extends SpecialContent
    {
        @Override
        public boolean isEof()
        {
            return true;
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName();
        }
    }

    /**
     * Error special content.
     */
    public static final class ErrorContent extends SpecialContent
    {
        private final Throwable _error;

        public ErrorContent(Throwable error)
        {
            _error = error;
        }

        @Override
        public Throwable getError()
        {
            return _error;
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + " [" + _error + "]";
        }
    }
}
