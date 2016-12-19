//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.io.InterruptedIOException;
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
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * {@link HttpInput} provides an implementation of {@link ServletInputStream} for {@link HttpChannel}.
 * <p>
 * Content may arrive in patterns such as [content(), content(), messageComplete()] so that this class maintains two states: the content state that tells
 * whether there is content to consume and the EOF state that tells whether an EOF has arrived. Only once the content has been consumed the content state is
 * moved to the EOF state.
 */
public class HttpInput extends ServletInputStream implements Runnable
{
    /**
     * An interceptor for HTTP Request input.
     * <p>
     * Unlike inputstream wrappers that can be applied by filters, an interceptor
     * is entirely transparent and works with async IO APIs.
     * <p>
     * An Interceptor may consume data from the passed content and the interceptor 
     * will continue to be called for the same content until the interceptor returns 
     * null or an empty content.   Thus even if the passed content is completely consumed
     * the interceptor will be called with the same content until it can no longer 
     * produce more content. 
     * @see HttpInput#setInterceptor(Interceptor)
     * @see HttpInput#addInterceptor(Interceptor)
     */
    public interface Interceptor
    {
        /**
         * @param content The content to be intercepted (may be empty or a {@link SentinelContent}. 
         * The content will be modified with any data the interceptor consumes, but there is no requirement
         * that all the data is consumed by the interceptor.
         * @return The intercepted content or null if interception is completed for that content.
         */
        Content readFrom(Content content);
    }
    
    /**
     * An {@link Interceptor} that chains two other {@link Interceptor}s together.
     * The {@link #readFrom(Content)} calls the previous {@link Interceptor}'s 
     * {@link #readFrom(Content)} and then passes any {@link Content} returned 
     * to the next {@link Interceptor}.
     */
    public static class ChainedInterceptor implements Interceptor, Destroyable
    {
        private final Interceptor _prev;
        private final Interceptor _next;
        
        public ChainedInterceptor(Interceptor prev, Interceptor next)
        {
            _prev = prev;
            _next = next;
        }

        public Interceptor getPrev()
        {
            return _prev;
        }

        public Interceptor getNext()
        {
            return _next;
        }

        @Override
        public Content readFrom(Content content)
        {
            return getNext().readFrom(getPrev().readFrom(content));
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

    private final static Logger LOG = Log.getLogger(HttpInput.class);
    final static Content EOF_CONTENT = new EofContent("EOF");
    final static Content EARLY_EOF_CONTENT = new EofContent("EARLY_EOF");

    private final byte[] _oneByteBuffer = new byte[1];
    private Content _content;
    private Content _intercepted;
    private final Deque<Content> _inputQ = new ArrayDeque<>();
    private final HttpChannelState _channelState;
    private ReadListener _listener;
    private State _state = STREAM;
    private long _firstByteTimeStamp = -1;
    private long _contentArrived;
    private long _contentConsumed;
    private long _blockUntil;
    private Interceptor _interceptor;

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
            if (_content!=null)
                _content.failed(null);
            _content = null;
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
            if (_interceptor instanceof Destroyable)
                ((Destroyable)_interceptor).destroy();
            _interceptor = null;
        }
    }

    /**
     * @return The current Interceptor, or null if none set
     */
    public Interceptor getInterceptor()
    {
        return _interceptor;
    }

    /**
     * Set the interceptor.
     * @param interceptor The interceptor to use.
     */
    public void setInterceptor(Interceptor interceptor)
    {
        _interceptor = interceptor;
    }

    /**
     * Set the {@link Interceptor}, using a {@link ChainedInterceptor} if 
     * an {@link Interceptor} is already set.
     * @param interceptor the next {@link Interceptor} in a chain
     */
    public void addInterceptor(Interceptor interceptor)
    {
        if (_interceptor == null)
            _interceptor = interceptor;
        else
            _interceptor = new ChainedInterceptor(_interceptor,interceptor);
    }

    @Override
    public int available()
    {
        int available = 0;
        boolean woken = false;
        synchronized (_inputQ)
        {
            if (_content == null)
                _content = _inputQ.poll();
            if (_content == null)
            {
                try
                {
                    produceContent();
                }
                catch (IOException e)
                {
                    woken = failed(e);
                }
                if (_content == null)
                    _content = _inputQ.poll();
            }

            if (_content != null)
                available = _content.remaining();
        }

        if (woken)
            wake();
        return available;
    }

    protected void wake()
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
        int read = read(_oneByteBuffer,0,1);
        if (read == 0)
            throw new IllegalStateException("unready read=0");
        return read < 0?-1:_oneByteBuffer[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        boolean wake = false;
        int l;
        synchronized (_inputQ)
        {
            // Setup blocking only if not async
            if (!isAsync())
            {
                if (_blockUntil == 0)
                {
                    long blockingTimeout = getBlockingTimeout();
                    if (blockingTimeout > 0)
                        _blockUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(blockingTimeout);
                }
            }

            // Caclulate minimum request rate for DOS protection
            long minRequestDataRate = _channelState.getHttpChannel().getHttpConfiguration().getMinRequestDataRate();
            if (minRequestDataRate > 0 && _firstByteTimeStamp != -1)
            {
                long period = System.nanoTime() - _firstByteTimeStamp;
                if (period > 0)
                {
                    long minimum_data = minRequestDataRate * TimeUnit.NANOSECONDS.toMillis(period) / TimeUnit.SECONDS.toMillis(1);
                    if (_contentArrived < minimum_data)
                        throw new BadMessageException(HttpStatus.REQUEST_TIMEOUT_408,String.format("Request data rate < %d B/s",minRequestDataRate));
                }
            }

            // Consume content looking for bytes to read
            while (true)
            {
                Content item = nextContent();
                if (item != null)
                {
                    l = get(item,b,off,len);
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} read {} from {}",this,l,item);

                    // Consume any following poison pills
                    if (item.isEmpty())
                        pollReadableContent();
                    break;
                }

                // No content, so should we block?
                if (!_state.blockForContent(this))
                {
                    // Not blocking, so what should we return?
                    l = _state.noContent();
                    
                    // If EOF do we need to wake for allDataRead callback?
                    if (l<0)
                        wake = _channelState.onReadEof();
                    break;
                }
            }
        }
        
        if (wake)
            wake();
        return l;
    }

    /**
     * Called when derived implementations should attempt to produce more Content and add it via {@link #addContent(Content)}. For protocols that are constantly
     * producing (eg HTTP2) this can be left as a noop;
     *
     * @throws IOException
     *             if unable to produce content
     */
    protected void produceContent() throws IOException
    {
    }

    /**
     * Get the next content from the inputQ, calling {@link #produceContent()} if need be. EOF is processed and state changed.
     *
     * @return the content or null if none available.
     * @throws IOException
     *             if retrieving the content fails
     */
    protected Content nextContent() throws IOException
    {
        Content content = pollNonEmptyContent();
        if (content == null && !isFinished())
        {
            produceContent();
            content = pollNonEmptyContent();
        }
        return content;
    }

    /**
     * Poll the inputQ for Content. Consumed buffers and {@link SentinelContent}s are removed and EOF state updated if need be.
     *
     * @return Content or null
     */
    protected Content pollNonEmptyContent()
    {
        while (true)
        {            
            // Get the next content (or EOF)
            Content content = pollReadableContent();
            
            // If it is EOF, consume it here
            if (content instanceof SentinelContent)
            {
                if (content instanceof EofContent)
                {
                    if (content == EARLY_EOF_CONTENT)
                        _state = EARLY_EOF;
                    else if (_listener == null)
                        _state = EOF;
                    else
                        _state = AEOF;
                }
                
                // Consume the EOF content, either if it was original content
                // or if it was produced by interception
                content.succeeded();
                if (_content==content)
                    _content = null;
                else if (_intercepted==content)
                    _intercepted = null;
                continue;
            }

            return content;
        }
        
    }

    /**
     * Poll the inputQ for Content or EOF. Consumed buffers and non EOF {@link SentinelContent}s are removed. EOF state is not updated.
     * Interception is done within this method.
     * @return Content with remaining, a {@link SentinelContent},  or null
     */
    protected Content pollReadableContent()
    {
        // If we have a chunk produced by interception
        if (_intercepted!=null)
        {
            // Use it if it has any remaining content
            if (_intercepted.hasContent())
                return _intercepted;
            
            // succeed the chunk
            _intercepted.succeeded();
            _intercepted=null;
        }

        // If we don't have a Content under consideration, get
        // the next one off the input Q.
        if (_content == null)
            _content = _inputQ.poll();
        
        // While we have content to consider.
        while (_content!=null)
        {           
            // Are we intercepting?
            if (_interceptor!=null)
            {
                // Intercept the current content (may be called several
                // times for the same content
                _intercepted = _interceptor.readFrom(_content);
                
                // If interception produced new content
                if (_intercepted!=null && _intercepted!=_content)
                {
                    // if it is not empty use it
                    if (_intercepted.hasContent())
                        return _intercepted;
                    _intercepted.succeeded();
                }   
                
                // intercepted content consumed
                _intercepted=null;
                
                // fall through so that the unintercepted _content is
                // considered for any remaining content, for EOF and to
                // succeed it if it is entirely consumed.
            }

            // If the content has content or is an EOF marker, use it
            if (_content.hasContent() || _content instanceof SentinelContent)
                return _content;
            
            // The content is consumed, so get the next one.  Note that EOF
            // content is never consumed here, but in #pollContent
            _content.succeeded();
            _content = _inputQ.poll();
        }
        
        return null;

    }
    

    /**
     * Get the next readable from the inputQ, calling {@link #produceContent()} if need be. EOF is NOT processed and state is not changed.
     *
     * @return the content or EOF or null if none available.
     * @throws IOException
     *             if retrieving the content fails
     */
    protected Content nextReadable() throws IOException
    {
        Content content = pollReadableContent();
        if (content == null && !isFinished())
        {
            produceContent();
            content = pollReadableContent();
        }
        return content;
    }


    /**
     * Copies the given content into the given byte buffer.
     *
     * @param content
     *            the content to copy from
     * @param buffer
     *            the buffer to copy into
     * @param offset
     *            the buffer offset to start copying from
     * @param length
     *            the space available in the buffer
     * @return the number of bytes actually copied
     */
    protected int get(Content content, byte[] buffer, int offset, int length)
    {
        int l = content.get(buffer,offset,length);
        _contentConsumed += l;
        return l;
    }

    /**
     * Consumes the given content. Calls the content succeeded if all content consumed.
     *
     * @param content
     *            the content to consume
     * @param length
     *            the number of bytes to consume
     */
    protected void skip(Content content, int length)
    {
        int l = content.skip(length);

        _contentConsumed += l;
        if (l > 0 && content.isEmpty())
            pollNonEmptyContent(); // hungry succeed

    }

    /**
     * Blocks until some content or some end-of-file event arrives.
     *
     * @throws IOException
     *             if the wait is interrupted
     */
    protected void blockForContent() throws IOException
    {
        try
        {
            long timeout = 0;
            if (_blockUntil != 0)
            {
                timeout = TimeUnit.NANOSECONDS.toMillis(_blockUntil - System.nanoTime());
                if (timeout <= 0)
                    throw new TimeoutException();
            }

            if (LOG.isDebugEnabled())
                LOG.debug("{} blocking for content timeout={}",this,timeout);
            if (timeout > 0)
                _inputQ.wait(timeout);
            else
                _inputQ.wait();

            // TODO: cannot return unless there is content or timeout,
            // TODO: so spurious wakeups are not handled correctly.

            if (_blockUntil != 0 && TimeUnit.NANOSECONDS.toMillis(_blockUntil - System.nanoTime()) <= 0)
                throw new TimeoutException(String.format("Blocking timeout %d ms",getBlockingTimeout()));
        }
        catch (Throwable e)
        {
            throw (IOException)new InterruptedIOException().initCause(e);
        }
    }

    /**
     * Adds some content to the start of this input stream.
     * <p>
     * Typically used to push back content that has been read, perhaps mutated. The bytes prepended are deducted for the contentConsumed total
     * </p>
     *
     * @param item
     *            the content to add
     * @return true if content channel woken for read
     */
    public boolean prependContent(Content item)
    {
        boolean woken = false;
        synchronized (_inputQ)
        {
            if (_content != null)
                _inputQ.push(_content);
            _content = item;
            _contentConsumed -= item.remaining();
            if (LOG.isDebugEnabled())
                LOG.debug("{} prependContent {}",this,item);

            if (_listener == null)
                _inputQ.notify();
            else
                woken = _channelState.onReadPossible();
        }
        return woken;
    }

    /**
     * Adds some content to this input stream.
     *
     * @param content
     *            the content to add
     * @return true if content channel woken for read
     */
    public boolean addContent(Content content)
    {
        boolean woken = false;
        synchronized (_inputQ)
        {
            if (_firstByteTimeStamp == -1)
                _firstByteTimeStamp = System.nanoTime();

            _contentArrived += content.remaining();
            
            if (_content==null && _inputQ.isEmpty())
                _content=content;
            else
                _inputQ.offer(content);
            
            if (LOG.isDebugEnabled())
                LOG.debug("{} addContent {}",this,content);

            if (pollReadableContent()!=null)
            {
                if (_listener == null)
                    _inputQ.notify();
                else
                    woken = _channelState.onReadPossible();
            }
        }
        return woken;
    }

    public boolean hasContent()
    {
        synchronized (_inputQ)
        {
            return _content!=null || _inputQ.size() > 0;
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
     * This method should be called to signal that an EOF has been detected before all the expected content arrived.
     * <p>
     * Typically this will result in an EOFException being thrown from a subsequent read rather than a -1 return.
     *
     * @return true if content channel woken for read
     */
    public boolean earlyEOF()
    {
        return addContent(EARLY_EOF_CONTENT);
    }

    /**
     * This method should be called to signal that all the expected content arrived.
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
                while (!isFinished())
                {
                    Content item = nextContent();
                    if (item == null)
                        break; // Let's not bother blocking

                    skip(item,item.remaining());
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
               
                _listener = readListener;
                
                Content content = nextReadable();
                if (content!=null)
                {
                    _state = ASYNC;
                    woken = _channelState.onReadReady();
                }
                else if (_state == EOF)
                {
                    _state = AEOF;
                    woken = _channelState.onReadReady();
                }
                else 
                {
                    _state = ASYNC;
                    _channelState.onReadUnready();
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

    public boolean failed(Throwable x)
    {
        boolean woken = false;
        synchronized (_inputQ)
        {
            if (_state instanceof ErrorState)
                LOG.warn(x);
            else
                _state = new ErrorState(x);

            if (_listener == null)
                _inputQ.notify();
            else
                woken = _channelState.onReadPossible();
        }

        return woken;
    }

    /*
     * <p> While this class is-a Runnable, it should never be dispatched in it's own thread. It is a runnable only so that the calling thread can use {@link
     * ContextHandler#handle(Runnable)} to setup classloaders etc. </p>
     */
    @Override
    public void run()
    {
        final ReadListener listener;
        Throwable error;
        boolean aeof = false;

        synchronized (_inputQ)
        {
            listener = _listener;

            if (_state == EOF)
                return;

            if (_state==AEOF)
            {
                _state = EOF;
                aeof = true;
            }
             
            error = _state.getError();
            
            if (!aeof && error==null)
            {
                Content content = pollReadableContent();

                // Consume EOF
                if (content instanceof EofContent)
                {
                    content.succeeded();
                    if (_content==content)
                        _content = null;
                    if (content == EARLY_EOF_CONTENT)
                    {
                        _state = EARLY_EOF;
                        error = _state.getError();
                    }
                    else 
                    {
                        _state = EOF;
                        aeof = true;
                    }
                }
                else if (content==null)
                    return;
            }
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
                synchronized (_inputQ)
                {
                    if (_state == AEOF)
                    {
                        _state = EOF;
                        aeof = !_channelState.isAsyncComplete();
                    }
                }
                if (aeof)
                    listener.onAllDataRead();
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

    /**
     * A Sentinel Content, which has zero length content but
     * indicates some other event in the input stream (eg EOF)
     *
     */
    public static class SentinelContent extends Content
    {
        private final String _name;

        public SentinelContent(String name)
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

    public static class EofContent extends SentinelContent
    {
        EofContent(String name)
        {
            super(name);
        }
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
            length = Math.min(_content.remaining(),length);
            _content.get(buffer,offset,length);
            return length;
        }

        public int skip(int length)
        {
            length = Math.min(_content.remaining(),length);
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
            return String.format("Content@%x{%s}",hashCode(),BufferUtil.toDetailString(_content));
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
        
        public Throwable getError()
        {
            return null;
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
            throw getError();
        }

        @Override
        public String toString()
        {
            return "EARLY_EOF";
        }
        
        public IOException getError()
        {
            return new EofException("Early EOF");
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
