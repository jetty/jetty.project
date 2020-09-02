//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritePendingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;

import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.util.SharedBlockingCallback.Blocker;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>{@link HttpOutput} implements {@link ServletOutputStream}
 * as required by the Servlet specification.</p>
 * <p>{@link HttpOutput} buffers content written by the application until a
 * further write will overflow the buffer, at which point it triggers a commit
 * of the response.</p>
 * <p>{@link HttpOutput} can be closed and reopened, to allow requests included
 * via {@link RequestDispatcher#include(ServletRequest, ServletResponse)} to
 * close the stream, to be reopened after the inclusion ends.</p>
 */
public class HttpOutput extends ServletOutputStream implements Runnable
{
    private static final String LSTRING_FILE = "javax.servlet.LocalStrings";
    private static ResourceBundle lStrings = ResourceBundle.getBundle(LSTRING_FILE);

    /**
     * The output state
     */
    enum State
    {
        OPEN,     // Open
        CLOSE,    // Close needed from onWriteCompletion
        CLOSING,  // Close in progress after close API called
        CLOSED    // Closed
    }

    /**
     * The API State which combines with the output State:
     * <pre>
              OPEN/BLOCKING---last----------------------------+                      CLOSED/BLOCKING
             /   |    ^                                        \                         ^  ^
            /    w    |                                         \                       /   |
           /     |   owc   +--owcL------------------->--owcL-----\---------------------+    |
          |      v    |   /                         /             V                         |
         swl  OPEN/BLOCKED----last---->CLOSE/BLOCKED----owc----->CLOSING/BLOCKED--owcL------+
          |
           \
            \
             V
          +-->OPEN/READY------last---------------------------+
         /    ^    |                                          \
        /    /     w                                           \
       |    /      |       +--owcL------------------->--owcL----\---------------------------+
       |   /       v      /                         /            V                          |
       | irt  OPEN/PENDING----last---->CLOSE/PENDING----owc---->CLOSING/PENDING--owcL----+  |
       |   \  /    |                        |                    ^     |                 |  |
      owc   \/    owc                      irf                  /     irf                |  |
       |    /\     |                        |                  /       |                 |  |
       |   /  \    V                        |                 /        |                 V  V
       | irf  OPEN/ASYNC------last----------|----------------+         |             CLOSED/ASYNC
       |   \                                |                          |                 ^  ^
        \   \                               |                          |                 |  |
         \   \                              |                          |                 |  |
          \   v                             v                          v                 |  |
           +--OPEN/UNREADY----last---->CLOSE/UNREADY----owc----->CLOSING/UNREADY--owcL---+  |
                          \                         \                                       |
                           +--owcL------------------->--owcL--------------------------------+

      swl  : setWriteListener
      w    : write
      owc  : onWriteComplete last == false
      owcL : onWriteComplete last == true
      irf  : isReady() == false
      irt  : isReady() == true
      last : close() or complete(Callback) or write of known last content
     </pre>
     */
    enum ApiState
    {
        BLOCKING, // Open in blocking mode
        BLOCKED,  // Blocked in blocking operation
        ASYNC,    // Open in async mode
        READY,    // isReady() has returned true
        PENDING,  // write operating in progress
        UNREADY,  // write operating in progress, isReady has returned false
    }

    /**
     * The HttpOutput.Interceptor is a single intercept point for all
     * output written to the HttpOutput: via writer; via output stream;
     * asynchronously; or blocking.
     * <p>
     * The Interceptor can be used to implement translations (eg Gzip) or
     * additional buffering that acts on all output.  Interceptors are
     * created in a chain, so that multiple concerns may intercept.
     * <p>
     * The {@link HttpChannel} is an {@link Interceptor} and is always the
     * last link in any Interceptor chain.
     * <p>
     * Responses are committed by the first call to
     * {@link #write(ByteBuffer, boolean, Callback)}
     * and closed by a call to {@link #write(ByteBuffer, boolean, Callback)}
     * with the last boolean set true.  If no content is available to commit
     * or close, then a null buffer is passed.
     */
    public interface Interceptor
    {
        /**
         * Write content.
         * The response is committed by the first call to write and is closed by
         * a call with last == true. Empty content buffers may be passed to
         * force a commit or close.
         *
         * @param content The content to be written or an empty buffer.
         * @param last True if this is the last call to write
         * @param callback The callback to use to indicate {@link Callback#succeeded()}
         * or {@link Callback#failed(Throwable)}.
         */
        void write(ByteBuffer content, boolean last, Callback callback);

        /**
         * @return The next Interceptor in the chain or null if this is the
         * last Interceptor in the chain.
         */
        Interceptor getNextInterceptor();

        /**
         * @return True if the Interceptor is optimized to receive direct
         * {@link ByteBuffer}s in the {@link #write(ByteBuffer, boolean, Callback)}
         * method.   If false is returned, then passing direct buffers may cause
         * inefficiencies.
         */
        boolean isOptimizedForDirectBuffers();

        /**
         * Reset the buffers.
         * <p>If the Interceptor contains buffers then reset them.
         *
         * @throws IllegalStateException Thrown if the response has been
         * committed and buffers and/or headers cannot be reset.
         */
        default void resetBuffer() throws IllegalStateException
        {
            Interceptor next = getNextInterceptor();
            if (next != null)
                next.resetBuffer();
        }
    }

    private static Logger LOG = Log.getLogger(HttpOutput.class);
    private static final ThreadLocal<CharsetEncoder> _encoder = new ThreadLocal<>();

    private final HttpChannel _channel;
    private final HttpChannelState _channelState;
    private final SharedBlockingCallback _writeBlocker;
    private ApiState _apiState = ApiState.BLOCKING;
    private State _state = State.OPEN;
    private boolean _softClose = false;
    private Interceptor _interceptor;
    private long _written;
    private long _flushed;
    private long _firstByteTimeStamp = -1;
    private ByteBuffer _aggregate;
    private int _bufferSize;
    private int _commitSize;
    private WriteListener _writeListener;
    private volatile Throwable _onError;
    private Callback _closedCallback;

    public HttpOutput(HttpChannel channel)
    {
        _channel = channel;
        _channelState = channel.getState();
        _interceptor = channel;
        _writeBlocker = new WriteBlocker(channel);
        HttpConfiguration config = channel.getHttpConfiguration();
        _bufferSize = config.getOutputBufferSize();
        _commitSize = config.getOutputAggregationSize();
        if (_commitSize > _bufferSize)
        {
            LOG.warn("OutputAggregationSize {} exceeds bufferSize {}", _commitSize, _bufferSize);
            _commitSize = _bufferSize;
        }
    }

    public HttpChannel getHttpChannel()
    {
        return _channel;
    }

    public Interceptor getInterceptor()
    {
        return _interceptor;
    }

    public void setInterceptor(Interceptor interceptor)
    {
        _interceptor = interceptor;
    }

    public boolean isWritten()
    {
        return _written > 0;
    }

    public long getWritten()
    {
        return _written;
    }

    public void reopen()
    {
        synchronized (_channelState)
        {
            _softClose = false;
        }
    }

    protected Blocker acquireWriteBlockingCallback() throws IOException
    {
        return _writeBlocker.acquire();
    }

    private void channelWrite(ByteBuffer content, boolean complete) throws IOException
    {
        try (Blocker blocker = _writeBlocker.acquire())
        {
            channelWrite(content, complete, blocker);
            blocker.block();
        }
    }

    private void channelWrite(ByteBuffer content, boolean last, Callback callback)
    {
        if (_firstByteTimeStamp == -1)
        {
            long minDataRate = getHttpChannel().getHttpConfiguration().getMinResponseDataRate();
            if (minDataRate > 0)
                _firstByteTimeStamp = System.nanoTime();
            else
                _firstByteTimeStamp = Long.MAX_VALUE;
        }

        _interceptor.write(content, last, callback);
    }

    private void onWriteComplete(boolean last, Throwable failure)
    {
        String state = null;
        boolean wake = false;
        Callback closedCallback = null;
        ByteBuffer closeContent = null;
        synchronized (_channelState)
        {
            if (LOG.isDebugEnabled())
                state = stateString();

            // Transition to CLOSED state if we were the last write or we have failed
            if (last || failure != null)
            {
                _state = State.CLOSED;
                closedCallback = _closedCallback;
                _closedCallback = null;
                releaseBuffer(failure);
                wake = updateApiState(failure);
            }
            else if (_state == State.CLOSE)
            {
                // Somebody called close or complete while we were writing.
                // We can now send a (probably empty) last buffer and then when it completes
                // onWriteCompletion will be called again to actually execute the _completeCallback
                _state = State.CLOSING;
                closeContent = BufferUtil.hasContent(_aggregate) ? _aggregate : BufferUtil.EMPTY_BUFFER;
            }
            else
            {
                wake = updateApiState(null);
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("onWriteComplete({},{}) {}->{} c={} cb={} w={}",
                last, failure, state, stateString(), BufferUtil.toDetailString(closeContent), closedCallback, wake);

        try
        {
            if (failure != null)
                _channel.abort(failure);

            if (closedCallback != null)
            {
                if (failure == null)
                    closedCallback.succeeded();
                else
                    closedCallback.failed(failure);
            }
            else if (closeContent != null)
            {
                channelWrite(closeContent, true, new WriteCompleteCB());
            }
        }
        finally
        {
            if (wake)
                _channel.execute(_channel); // TODO review in jetty-10 if execute is needed
        }
    }

    private boolean updateApiState(Throwable failure)
    {
        boolean wake = false;
        switch (_apiState)
        {
            case BLOCKED:
                _apiState = ApiState.BLOCKING;
                break;

            case PENDING:
                _apiState = ApiState.ASYNC;
                if (failure != null)
                {
                    _onError = failure;
                    wake = _channelState.onWritePossible();
                }
                break;

            case UNREADY:
                _apiState = ApiState.READY;
                if (failure != null)
                    _onError = failure;
                wake = _channelState.onWritePossible();
                break;

            default:
                if (_state == State.CLOSED)
                    break;
                throw new IllegalStateException(stateString());
        }
        return wake;
    }

    private int maximizeAggregateSpace()
    {
        // If no aggregate, we can allocate one of bufferSize
        if (_aggregate == null)
            return getBufferSize();

        // compact to maximize space
        BufferUtil.compact(_aggregate);

        return BufferUtil.space(_aggregate);
    }

    public void softClose()
    {
        synchronized (_channelState)
        {
            _softClose = true;
        }
    }

    public void complete(Callback callback)
    {
        // This method is invoked for the COMPLETE action handling in
        // HttpChannel.handle.  The callback passed typically will call completed
        // to finish the request cycle and so may need to asynchronously wait for:
        // a pending/blocked operation to finish and then either an async close or
        // wait for an application close to complete.
        boolean succeeded = false;
        Throwable error = null;
        ByteBuffer content = null;
        synchronized (_channelState)
        {
            switch (_state)
            {
                case CLOSED:
                    succeeded = true;
                    break;

                case CLOSE:
                case CLOSING:
                    _closedCallback = Callback.combine(_closedCallback, callback);
                    break;

                case OPEN:
                    if (_onError != null)
                    {
                        error = _onError;
                        break;
                    }

                    _closedCallback = Callback.combine(_closedCallback, callback);

                    switch (_apiState)
                    {
                        case BLOCKING:
                            // Output is idle blocking state, but we still do an async close
                            _apiState = ApiState.BLOCKED;
                            _state = State.CLOSING;
                            content = BufferUtil.hasContent(_aggregate) ? _aggregate : BufferUtil.EMPTY_BUFFER;
                            break;

                        case ASYNC:
                        case READY:
                            // Output is idle in async state, so we can do an async close
                            _apiState = ApiState.PENDING;
                            _state = State.CLOSING;
                            content = BufferUtil.hasContent(_aggregate) ? _aggregate : BufferUtil.EMPTY_BUFFER;
                            break;

                        case BLOCKED:
                        case UNREADY:
                        case PENDING:
                            // An operation is in progress, so we soft close now
                            _softClose = true;
                            // then trigger a close from onWriteComplete
                            _state = State.CLOSE;
                            break;
                    }
                    break;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("complete({}) {} s={} e={}, c={}", callback, stateString(), succeeded, error, BufferUtil.toDetailString(content));

        if (succeeded)
        {
            callback.succeeded();
            return;
        }

        if (error != null)
        {
            callback.failed(error);
            return;
        }

        if (content != null)
            channelWrite(content, true, new WriteCompleteCB());
    }

    /**
     * Called to indicate that the request cycle has been completed.
     */
    public void completed(Throwable failure)
    {
        synchronized (_channelState)
        {
            _state = State.CLOSED;
            releaseBuffer(failure);
        }
    }

    @Override
    public void close() throws IOException
    {
        ByteBuffer content = null;
        Blocker blocker = null;
        synchronized (_channelState)
        {
            if (_onError != null)
            {
                if (_onError instanceof IOException)
                    throw (IOException)_onError;
                if (_onError instanceof RuntimeException)
                    throw (RuntimeException)_onError;
                if (_onError instanceof Error)
                    throw (Error)_onError;
                throw new IOException(_onError);
            }

            switch (_state)
            {
                case CLOSED:
                    break;

                case CLOSE:
                case CLOSING:
                    switch (_apiState)
                    {
                        case BLOCKING:
                        case BLOCKED:
                            // block until CLOSED state reached.
                            blocker = _writeBlocker.acquire();
                            _closedCallback = Callback.combine(_closedCallback, blocker);
                            break;

                        default:
                            // async close with no callback, so nothing to do
                            break;
                    }
                    break;

                case OPEN:
                    switch (_apiState)
                    {
                        case BLOCKING:
                            // Output is idle blocking state, but we still do an async close
                            _apiState = ApiState.BLOCKED;
                            _state = State.CLOSING;
                            blocker = _writeBlocker.acquire();
                            content = BufferUtil.hasContent(_aggregate) ? _aggregate : BufferUtil.EMPTY_BUFFER;
                            break;

                        case BLOCKED:
                            // An blocking operation is in progress, so we soft close now
                            _softClose = true;
                            // then trigger a close from onWriteComplete
                            _state = State.CLOSE;
                            // and block until it is complete
                            blocker = _writeBlocker.acquire();
                            _closedCallback = Callback.combine(_closedCallback, blocker);
                            break;

                        case ASYNC:
                        case READY:
                            // Output is idle in async state, so we can do an async close
                            _apiState = ApiState.PENDING;
                            _state = State.CLOSING;
                            content = BufferUtil.hasContent(_aggregate) ? _aggregate : BufferUtil.EMPTY_BUFFER;
                            break;

                        case UNREADY:
                        case PENDING:
                            // An async operation is in progress, so we soft close now
                            _softClose = true;
                            // then trigger a close from onWriteComplete
                            _state = State.CLOSE;
                            break;
                    }
                    break;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("close() {} c={} b={}", stateString(), BufferUtil.toDetailString(content), blocker);

        if (content == null)
        {
            if (blocker == null)
                // nothing to do or block for.
                return;

            // Just wait for some other close to finish.
            try (Blocker b = blocker)
            {
                b.block();
            }
        }
        else
        {
            if (blocker == null)
            {
                // Do an async close
                channelWrite(content, true, new WriteCompleteCB());
            }
            else
            {
                // Do a blocking close
                try (Blocker b = blocker)
                {
                    channelWrite(content, true, blocker);
                    b.block();
                    onWriteComplete(true, null);
                }
                catch (Throwable t)
                {
                    onWriteComplete(true, t);
                }
            }
        }
    }

    public ByteBuffer getBuffer()
    {
        synchronized (_channelState)
        {
            return acquireBuffer();
        }
    }

    private ByteBuffer acquireBuffer()
    {
        if (_aggregate == null)
            _aggregate = _channel.getByteBufferPool().acquire(getBufferSize(), _interceptor.isOptimizedForDirectBuffers());
        return _aggregate;
    }

    private void releaseBuffer(Throwable failure)
    {
        if (_aggregate != null)
        {
            ByteBufferPool bufferPool = _channel.getConnector().getByteBufferPool();
            if (failure == null)
                bufferPool.release(_aggregate);
            else
                bufferPool.remove(_aggregate);
            _aggregate = null;
        }
    }

    public boolean isClosed()
    {
        synchronized (_channelState)
        {
            return _softClose || (_state != State.OPEN);
        }
    }

    public boolean isAsync()
    {
        synchronized (_channelState)
        {
            switch (_apiState)
            {
                case ASYNC:
                case READY:
                case PENDING:
                case UNREADY:
                    return true;
                default:
                    return false;
            }
        }
    }

    @Override
    public void flush() throws IOException
    {
        ByteBuffer content = null;
        synchronized (_channelState)
        {
            switch (_state)
            {
                case CLOSED:
                case CLOSING:
                    return;

                default:
                {
                    switch (_apiState)
                    {
                        case BLOCKING:
                            _apiState = ApiState.BLOCKED;
                            content = BufferUtil.hasContent(_aggregate) ? _aggregate : BufferUtil.EMPTY_BUFFER;
                            break;

                        case ASYNC:
                        case PENDING:
                            throw new IllegalStateException("isReady() not called: " + stateString());

                        case READY:
                            _apiState = ApiState.PENDING;
                            break;

                        case UNREADY:
                            throw new WritePendingException();

                        default:
                            throw new IllegalStateException(stateString());
                    }
                }
            }
        }

        if (content == null)
            new AsyncFlush(false).iterate();
        else
        {
            try
            {
                channelWrite(content, false);
                onWriteComplete(false, null);
            }
            catch (Throwable t)
            {
                onWriteComplete(false, t);
                throw t;
            }
        }
    }

    private void checkWritable() throws EofException
    {
        if (_softClose)
                throw new EofException("Closed");

        switch (_state)
        {
            case CLOSED:
            case CLOSING:
                throw new EofException("Closed");

            default:
                break;
        }

        if (_onError != null)
            throw new EofException(_onError);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("write(array {})", BufferUtil.toDetailString(ByteBuffer.wrap(b, off, len)));

        boolean last;
        boolean aggregate;
        boolean flush;

        // Async or Blocking ?
        boolean async;
        synchronized (_channelState)
        {
            checkWritable();
            long written = _written + len;
            int space = maximizeAggregateSpace();
            last = _channel.getResponse().isAllContentWritten(written);
            // Write will be aggregated if:
            //  + it is smaller than the commitSize
            //  + is not the last one, or is last but will fit in an already allocated aggregate buffer.
            aggregate = len <= _commitSize && (!last || BufferUtil.hasContent(_aggregate) && len <= space);
            flush = last || !aggregate || len >= space;

            if (last && _state == State.OPEN)
                _state = State.CLOSING;

            switch (_apiState)
            {
                case BLOCKING:
                    _apiState = flush ? ApiState.BLOCKED : ApiState.BLOCKING;
                    async = false;
                    break;

                case ASYNC:
                    throw new IllegalStateException("isReady() not called: " + stateString());

                case READY:
                    async = true;
                    _apiState = flush ? ApiState.PENDING : ApiState.ASYNC;
                    break;

                case PENDING:
                case UNREADY:
                    throw new WritePendingException();

                default:
                    throw new IllegalStateException(stateString());
            }

            _written = written;

            // Should we aggregate?
            if (aggregate)
            {
                acquireBuffer();
                int filled = BufferUtil.fill(_aggregate, b, off, len);

                // return if we are not complete, not full and filled all the content
                if (!flush)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("write(array) {} aggregated !flush {}",
                            stateString(), BufferUtil.toDetailString(_aggregate));
                    return;
                }

                // adjust offset/length
                off += filled;
                len -= filled;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("write(array) {} last={} agg={} flush=true async={}, len={} {}",
                stateString(), last, aggregate, async, len, BufferUtil.toDetailString(_aggregate));

        if (async)
        {
            // Do the asynchronous writing from the callback
            new AsyncWrite(b, off, len, last).iterate();
            return;
        }

        // Blocking write
        try
        {
            // flush any content from the aggregate
            if (BufferUtil.hasContent(_aggregate))
            {
                channelWrite(_aggregate, last && len == 0);

                // should we fill aggregate again from the buffer?
                if (len > 0 && !last && len <= _commitSize && len <= maximizeAggregateSpace())
                {
                    BufferUtil.append(_aggregate, b, off, len);
                    onWriteComplete(false, null);
                    return;
                }
            }

            // write any remaining content in the buffer directly
            if (len > 0)
            {
                // write a buffer capacity at a time to avoid JVM pooling large direct buffers
                // http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6210541
                ByteBuffer view = ByteBuffer.wrap(b, off, len);

                while (len > getBufferSize())
                {
                    int p = view.position();
                    int l = p + getBufferSize();
                    view.limit(l);
                    channelWrite(view, false);
                    view.limit(p + len);
                    view.position(l);
                    len -= getBufferSize();
                }
                channelWrite(view, last);
            }
            else if (last)
            {
                channelWrite(BufferUtil.EMPTY_BUFFER, true);
            }

            onWriteComplete(last, null);
        }
        catch (Throwable t)
        {
            onWriteComplete(last, t);
            throw t;
        }
    }

    public void write(ByteBuffer buffer) throws IOException
    {
        // This write always bypasses aggregate buffer
        int len = BufferUtil.length(buffer);
        boolean flush;
        boolean last;

        // Async or Blocking ?
        boolean async;
        synchronized (_channelState)
        {
            checkWritable();
            long written = _written + len;
            last = _channel.getResponse().isAllContentWritten(_written);
            flush = last || len > 0 || BufferUtil.hasContent(_aggregate);

            if (last && _state == State.OPEN)
                _state = State.CLOSING;

            switch (_apiState)
            {
                case BLOCKING:
                    async = false;
                    _apiState = flush ? ApiState.BLOCKED : ApiState.BLOCKING;
                    break;

                case ASYNC:
                    throw new IllegalStateException("isReady() not called: " + stateString());

                case READY:
                    async = true;
                    _apiState = flush ? ApiState.PENDING : ApiState.ASYNC;
                    break;

                case PENDING:
                case UNREADY:
                    throw new WritePendingException();

                default:
                    throw new IllegalStateException(stateString());
            }
            _written = written;
        }

        if (!flush)
            return;

        if (async)
        {
            new AsyncWrite(buffer, last).iterate();
        }
        else
        {
            try
            {
                // Blocking write
                // flush any content from the aggregate
                if (BufferUtil.hasContent(_aggregate))
                    channelWrite(_aggregate, last && len == 0);

                // write any remaining content in the buffer directly
                if (len > 0)
                    channelWrite(buffer, last);
                else if (last)
                    channelWrite(BufferUtil.EMPTY_BUFFER, true);

                onWriteComplete(last, null);
            }
            catch (Throwable t)
            {
                onWriteComplete(last, t);
                throw t;
            }
        }
    }

    @Override
    public void write(int b) throws IOException
    {
        boolean flush;
        boolean last;
        // Async or Blocking ?

        boolean async = false;
        synchronized (_channelState)
        {
            checkWritable();
            long written = _written + 1;
            int space = maximizeAggregateSpace();
            last = _channel.getResponse().isAllContentWritten(written);
            flush = last || space == 1;

            if (last && _state == State.OPEN)
                _state = State.CLOSING;

            switch (_apiState)
            {
                case BLOCKING:
                    _apiState = flush ? ApiState.BLOCKED : ApiState.BLOCKING;
                    break;

                case ASYNC:
                    throw new IllegalStateException("isReady() not called: " + stateString());

                case READY:
                    async = true;
                    _apiState = flush ? ApiState.PENDING : ApiState.ASYNC;
                    break;

                case PENDING:
                case UNREADY:
                    throw new WritePendingException();

                default:
                    throw new IllegalStateException(stateString());
            }
            _written = written;

            acquireBuffer();
            BufferUtil.append(_aggregate, (byte)b);
        }

        // Check if all written or full
        if (!flush)
            return;

        if (async)
            // Do the asynchronous writing from the callback
            new AsyncFlush(last).iterate();
        else
        {
            try
            {
                channelWrite(_aggregate, last);
                onWriteComplete(last, null);
            }
            catch (Throwable t)
            {
                onWriteComplete(last, t);
                throw t;
            }
        }
    }

    @Override
    public void print(String s) throws IOException
    {
        print(s, false);
    }

    @Override
    public void println(String s) throws IOException
    {
        print(s, true);
    }

    private void print(String s, boolean eoln) throws IOException
    {
        if (isClosed())
            throw new IOException("Closed");

        String charset = _channel.getResponse().getCharacterEncoding();
        CharsetEncoder encoder = _encoder.get();
        if (encoder == null || !encoder.charset().name().equalsIgnoreCase(charset))
        {
            encoder = Charset.forName(charset).newEncoder();
            encoder.onMalformedInput(CodingErrorAction.REPLACE);
            encoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
            _encoder.set(encoder);
        }
        else
        {
            encoder.reset();
        }

        CharBuffer in = CharBuffer.wrap(s);
        CharBuffer crlf = eoln ? CharBuffer.wrap("\r\n") : null;
        ByteBuffer out = getHttpChannel().getByteBufferPool().acquire((int)(1 + (s.length() + 2) * encoder.averageBytesPerChar()), false);
        BufferUtil.flipToFill(out);

        for (; ; )
        {
            CoderResult result;
            if (in.hasRemaining())
            {
                result = encoder.encode(in, out, crlf == null);
                if (result.isUnderflow())
                    if (crlf == null)
                        break;
                    else
                        continue;
            }
            else if (crlf != null && crlf.hasRemaining())
            {
                result = encoder.encode(crlf, out, true);
                if (result.isUnderflow())
                {
                    if (!encoder.flush(out).isUnderflow())
                        result.throwException();
                    break;
                }
            }
            else
                break;

            if (result.isOverflow())
            {
                BufferUtil.flipToFlush(out, 0);
                ByteBuffer bigger = BufferUtil.ensureCapacity(out, out.capacity() + s.length() + 2);
                getHttpChannel().getByteBufferPool().release(out);
                BufferUtil.flipToFill(bigger);
                out = bigger;
                continue;
            }

            result.throwException();
        }
        BufferUtil.flipToFlush(out, 0);
        write(out.array(), out.arrayOffset(), out.remaining());
        getHttpChannel().getByteBufferPool().release(out);
    }

    @Override
    public void println(boolean b) throws IOException
    {
        println(lStrings.getString(b ? "value.true" : "value.false"));
    }

    @Override
    public void println(char c) throws IOException
    {
        println(String.valueOf(c));
    }

    @Override
    public void println(int i) throws IOException
    {
        println(String.valueOf(i));
    }

    @Override
    public void println(long l) throws IOException
    {
        println(String.valueOf(l));
    }

    @Override
    public void println(float f) throws IOException
    {
        println(String.valueOf(f));
    }

    @Override
    public void println(double d) throws IOException
    {
        println(String.valueOf(d));
    }

    /**
     * Blocking send of whole content.
     *
     * @param content The whole content to send
     * @throws IOException if the send fails
     */
    public void sendContent(ByteBuffer content) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sendContent({})", BufferUtil.toDetailString(content));

        _written += content.remaining();
        channelWrite(content, true);
    }

    /**
     * Blocking send of stream content.
     *
     * @param in The stream content to send
     * @throws IOException if the send fails
     */
    public void sendContent(InputStream in) throws IOException
    {
        try (Blocker blocker = _writeBlocker.acquire())
        {
            new InputStreamWritingCB(in, blocker).iterate();
            blocker.block();
        }
    }

    /**
     * Blocking send of channel content.
     *
     * @param in The channel content to send
     * @throws IOException if the send fails
     */
    public void sendContent(ReadableByteChannel in) throws IOException
    {
        try (Blocker blocker = _writeBlocker.acquire())
        {
            new ReadableByteChannelWritingCB(in, blocker).iterate();
            blocker.block();
        }
    }

    /**
     * Blocking send of HTTP content.
     *
     * @param content The HTTP content to send
     * @throws IOException if the send fails
     */
    public void sendContent(HttpContent content) throws IOException
    {
        try (Blocker blocker = _writeBlocker.acquire())
        {
            sendContent(content, blocker);
            blocker.block();
        }
    }

    /**
     * Asynchronous send of whole content.
     *
     * @param content The whole content to send
     * @param callback The callback to use to notify success or failure
     */
    public void sendContent(ByteBuffer content, final Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sendContent(buffer={},{})", BufferUtil.toDetailString(content), callback);

        if (prepareSendContent(content.remaining(), callback))
            channelWrite(content, true,
                new Callback.Nested(callback)
                {
                    @Override
                    public void succeeded()
                    {
                        onWriteComplete(true, null);
                        super.succeeded();
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        onWriteComplete(true, x);
                        super.failed(x);
                    }
                });
    }

    /**
     * Asynchronous send of stream content.
     * The stream will be closed after reading all content.
     *
     * @param in The stream content to send
     * @param callback The callback to use to notify success or failure
     */
    public void sendContent(InputStream in, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sendContent(stream={},{})", in, callback);

        if (prepareSendContent(0, callback))
            new InputStreamWritingCB(in, callback).iterate();
    }

    /**
     * Asynchronous send of channel content.
     * The channel will be closed after reading all content.
     *
     * @param in The channel content to send
     * @param callback The callback to use to notify success or failure
     */
    public void sendContent(ReadableByteChannel in, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sendContent(channel={},{})", in, callback);

        if (prepareSendContent(0, callback))
            new ReadableByteChannelWritingCB(in, callback).iterate();
    }

    private boolean prepareSendContent(int len, Callback callback)
    {
        synchronized (_channelState)
        {
            if (BufferUtil.hasContent(_aggregate))
            {
                callback.failed(new IOException("cannot sendContent() after write()"));
                return false;
            }
            if (_channel.isCommitted())
            {
                callback.failed(new IOException("cannot sendContent(), output already committed"));
                return false;
            }

            switch (_state)
            {
                case CLOSED:
                case CLOSING:
                    callback.failed(new EofException("Closed"));
                    return false;

                default:
                    _state = State.CLOSING;
                    break;
            }

            if (_onError != null)
            {
                callback.failed(_onError);
                return false;
            }

            if (_apiState != ApiState.BLOCKING)
                throw new IllegalStateException(stateString());
            _apiState = ApiState.PENDING;
            if (len > 0)
                _written += len;
            return true;
        }
    }

    /**
     * Asynchronous send of HTTP content.
     *
     * @param httpContent The HTTP content to send
     * @param callback The callback to use to notify success or failure
     */
    public void sendContent(HttpContent httpContent, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sendContent(http={},{})", httpContent, callback);

        ByteBuffer buffer = _channel.useDirectBuffers() ? httpContent.getDirectBuffer() : null;
        if (buffer == null)
            buffer = httpContent.getIndirectBuffer();

        if (buffer != null)
        {
            sendContent(buffer, callback);
            return;
        }

        ReadableByteChannel rbc = null;
        try
        {
            rbc = httpContent.getReadableByteChannel();
        }
        catch (Throwable x)
        {
            LOG.debug(x);
        }
        if (rbc != null)
        {
            // Close of the rbc is done by the async sendContent
            sendContent(rbc, callback);
            return;
        }

        InputStream in = null;
        try
        {
            in = httpContent.getInputStream();
        }
        catch (Throwable x)
        {
            LOG.debug(x);
        }
        if (in != null)
        {
            sendContent(in, callback);
            return;
        }

        Throwable cause = new IllegalArgumentException("unknown content for " + httpContent);
        _channel.abort(cause);
        callback.failed(cause);
    }

    public int getBufferSize()
    {
        return _bufferSize;
    }

    public void setBufferSize(int size)
    {
        _bufferSize = size;
        _commitSize = size;
    }

    /**
     * <p>Invoked when bytes have been flushed to the network.</p>
     * <p>The number of flushed bytes may be different from the bytes written
     * by the application if an {@link Interceptor} changed them, for example
     * by compressing them.</p>
     *
     * @param bytes the number of bytes flushed
     * @throws IOException if the minimum data rate, when set, is not respected
     * @see org.eclipse.jetty.io.WriteFlusher.Listener
     */
    public void onFlushed(long bytes) throws IOException
    {
        if (_firstByteTimeStamp == -1 || _firstByteTimeStamp == Long.MAX_VALUE)
            return;
        long minDataRate = getHttpChannel().getHttpConfiguration().getMinResponseDataRate();
        _flushed += bytes;
        long elapsed = System.nanoTime() - _firstByteTimeStamp;
        long minFlushed = minDataRate * TimeUnit.NANOSECONDS.toMillis(elapsed) / TimeUnit.SECONDS.toMillis(1);
        if (LOG.isDebugEnabled())
            LOG.debug("Flushed bytes min/actual {}/{}", minFlushed, _flushed);
        if (_flushed < minFlushed)
        {
            IOException ioe = new IOException(String.format("Response content data rate < %d B/s", minDataRate));
            _channel.abort(ioe);
            throw ioe;
        }
    }

    public void recycle()
    {
        synchronized (_channelState)
        {
            _state = State.OPEN;
            _apiState = ApiState.BLOCKING;
            _softClose = false;
            _interceptor = _channel;
            HttpConfiguration config = _channel.getHttpConfiguration();
            _bufferSize = config.getOutputBufferSize();
            _commitSize = config.getOutputAggregationSize();
            if (_commitSize > _bufferSize)
                _commitSize = _bufferSize;
            releaseBuffer(null);
            _written = 0;
            _writeListener = null;
            _onError = null;
            _firstByteTimeStamp = -1;
            _flushed = 0;
            _closedCallback = null;
        }
    }

    public void resetBuffer()
    {
        synchronized (_channelState)
        {
            _interceptor.resetBuffer();
            if (BufferUtil.hasContent(_aggregate))
                BufferUtil.clear(_aggregate);
            _written = 0;
        }
    }

    @Override
    public void setWriteListener(WriteListener writeListener)
    {
        if (!_channel.getState().isAsync())
            throw new IllegalStateException("!ASYNC: " + stateString());
        boolean wake;
        synchronized (_channelState)
        {
            if (_apiState != ApiState.BLOCKING)
                throw new IllegalStateException("!OPEN" + stateString());
            _apiState = ApiState.READY;
            _writeListener = writeListener;
            wake = _channel.getState().onWritePossible();
        }
        if (wake)
            _channel.execute(_channel);
    }

    @Override
    public boolean isReady()
    {
        synchronized (_channelState)
        {
            switch (_apiState)
            {
                case BLOCKING:
                case READY:
                    return true;

                case ASYNC:
                    _apiState = ApiState.READY;
                    return true;

                case PENDING:
                    _apiState = ApiState.UNREADY;
                    return false;

                case BLOCKED:
                case UNREADY:
                    return false;

                default:
                    throw new IllegalStateException(stateString());
            }
        }
    }

    @Override
    public void run()
    {
        Throwable error = null;

        synchronized (_channelState)
        {
            if (_onError != null)
            {
                error = _onError;
                _onError = null;
            }
        }

        try
        {
            if (error == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("onWritePossible");
                _writeListener.onWritePossible();
                return;
            }
        }
        catch (Throwable t)
        {
            error = t;
        }
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onError", error);
            _writeListener.onError(error);
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(t);
        }
        finally
        {
            IO.close(this);
        }
    }

    private String stateString()
    {
        return String.format("s=%s,api=%s,sc=%b,e=%s", _state, _apiState, _softClose, _onError);
    }

    @Override
    public String toString()
    {
        synchronized (_channelState)
        {
            return String.format("%s@%x{%s}", this.getClass().getSimpleName(), hashCode(), stateString());
        }
    }

    private abstract class ChannelWriteCB extends IteratingCallback
    {
        final boolean _last;

        ChannelWriteCB(boolean last)
        {
            _last = last;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        @Override
        protected void onCompleteSuccess()
        {
            onWriteComplete(_last, null);
        }

        @Override
        public void onCompleteFailure(Throwable e)
        {
            onWriteComplete(_last, e);
        }
    }

    private abstract class NestedChannelWriteCB extends ChannelWriteCB
    {
        final Callback _callback;

        NestedChannelWriteCB(Callback callback, boolean last)
        {
            super(last);
            _callback = callback;
        }

        @Override
        protected void onCompleteSuccess()
        {
            try
            {
                super.onCompleteSuccess();
            }
            finally
            {
                _callback.succeeded();
            }
        }

        @Override
        public void onCompleteFailure(Throwable e)
        {
            try
            {
                super.onCompleteFailure(e);
            }
            catch (Throwable t)
            {
                if (t != e)
                    e.addSuppressed(t);
            }
            finally
            {
                _callback.failed(e);
            }
        }
    }

    private class AsyncFlush extends ChannelWriteCB
    {
        volatile boolean _flushed;

        AsyncFlush(boolean last)
        {
            super(last);
        }

        @Override
        protected Action process() throws Exception
        {
            if (BufferUtil.hasContent(_aggregate))
            {
                _flushed = true;
                channelWrite(_aggregate, false, this);
                return Action.SCHEDULED;
            }

            if (!_flushed)
            {
                _flushed = true;
                channelWrite(BufferUtil.EMPTY_BUFFER, false, this);
                return Action.SCHEDULED;
            }

            return Action.SUCCEEDED;
        }
    }

    private class AsyncWrite extends ChannelWriteCB
    {
        private final ByteBuffer _buffer;
        private final ByteBuffer _slice;
        private final int _len;
        private boolean _completed;

        AsyncWrite(byte[] b, int off, int len, boolean last)
        {
            super(last);
            _buffer = ByteBuffer.wrap(b, off, len);
            _len = len;
            // always use a view for large byte arrays to avoid JVM pooling large direct buffers
            _slice = _len < getBufferSize() ? null : _buffer.duplicate();
        }

        AsyncWrite(ByteBuffer buffer, boolean last)
        {
            super(last);
            _buffer = buffer;
            _len = buffer.remaining();
            // Use a slice buffer for large indirect to avoid JVM pooling large direct buffers
            if (_buffer.isDirect() || _len < getBufferSize())
                _slice = null;
            else
            {
                _slice = _buffer.duplicate();
            }
        }

        @Override
        protected Action process() throws Exception
        {
            // flush any content from the aggregate
            if (BufferUtil.hasContent(_aggregate))
            {
                _completed = _len == 0;
                channelWrite(_aggregate, _last && _completed, this);
                return Action.SCHEDULED;
            }

            // Can we just aggregate the remainder?
            if (!_last && _aggregate != null && _len < maximizeAggregateSpace() && _len < _commitSize)
            {
                int position = BufferUtil.flipToFill(_aggregate);
                BufferUtil.put(_buffer, _aggregate);
                BufferUtil.flipToFlush(_aggregate, position);
                return Action.SUCCEEDED;
            }

            // Is there data left to write?
            if (_buffer.hasRemaining())
            {
                // if there is no slice, just write it
                if (_slice == null)
                {
                    _completed = true;
                    channelWrite(_buffer, _last, this);
                    return Action.SCHEDULED;
                }

                // otherwise take a slice
                int p = _buffer.position();
                int l = Math.min(getBufferSize(), _buffer.remaining());
                int pl = p + l;
                _slice.limit(pl);
                _buffer.position(pl);
                _slice.position(p);
                _completed = !_buffer.hasRemaining();
                channelWrite(_slice, _last && _completed, this);
                return Action.SCHEDULED;
            }

            // all content written, but if we have not yet signal completion, we
            // need to do so
            if (_last && !_completed)
            {
                _completed = true;
                channelWrite(BufferUtil.EMPTY_BUFFER, true, this);
                return Action.SCHEDULED;
            }

            if (LOG.isDebugEnabled() && _completed)
                LOG.debug("EOF of {}", this);

            return Action.SUCCEEDED;
        }
    }

    /**
     * An iterating callback that will take content from an
     * InputStream and write it to the associated {@link HttpChannel}.
     * A non direct buffer of size {@link HttpOutput#getBufferSize()} is used.
     * This callback is passed to the {@link HttpChannel#write(ByteBuffer, boolean, Callback)} to
     * be notified as each buffer is written and only once all the input is consumed will the
     * wrapped {@link Callback#succeeded()} method be called.
     */
    private class InputStreamWritingCB extends NestedChannelWriteCB
    {
        private final InputStream _in;
        private final ByteBuffer _buffer;
        private boolean _eof;
        private boolean _closed;

        InputStreamWritingCB(InputStream in, Callback callback)
        {
            super(callback, true);
            _in = in;
            _buffer = _channel.getByteBufferPool().acquire(getBufferSize(), false);
        }

        @Override
        protected Action process() throws Exception
        {
            // Only return if EOF has previously been read and thus
            // a write done with EOF=true
            if (_eof)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("EOF of {}", this);
                if (!_closed)
                {
                    _closed = true;
                    _channel.getByteBufferPool().release(_buffer);
                    IO.close(_in);
                }

                return Action.SUCCEEDED;
            }

            // Read until buffer full or EOF
            int len = 0;
            while (len < _buffer.capacity() && !_eof)
            {
                int r = _in.read(_buffer.array(), _buffer.arrayOffset() + len, _buffer.capacity() - len);
                if (r < 0)
                    _eof = true;
                else
                    len += r;
            }

            // write what we have
            _buffer.position(0);
            _buffer.limit(len);
            _written += len;
            channelWrite(_buffer, _eof, this);
            return Action.SCHEDULED;
        }

        @Override
        public void onCompleteFailure(Throwable x)
        {
            try
            {
                _channel.getByteBufferPool().release(_buffer);
            }
            finally
            {
                super.onCompleteFailure(x);
            }
        }
    }

    /**
     * An iterating callback that will take content from a
     * ReadableByteChannel and write it to the {@link HttpChannel}.
     * A {@link ByteBuffer} of size {@link HttpOutput#getBufferSize()} is used that will be direct if
     * {@link HttpChannel#useDirectBuffers()} is true.
     * This callback is passed to the {@link HttpChannel#write(ByteBuffer, boolean, Callback)} to
     * be notified as each buffer is written and only once all the input is consumed will the
     * wrapped {@link Callback#succeeded()} method be called.
     */
    private class ReadableByteChannelWritingCB extends NestedChannelWriteCB
    {
        private final ReadableByteChannel _in;
        private final ByteBuffer _buffer;
        private boolean _eof;
        private boolean _closed;

        ReadableByteChannelWritingCB(ReadableByteChannel in, Callback callback)
        {
            super(callback, true);
            _in = in;
            _buffer = _channel.getByteBufferPool().acquire(getBufferSize(), _channel.useDirectBuffers());
        }

        @Override
        protected Action process() throws Exception
        {
            // Only return if EOF has previously been read and thus
            // a write done with EOF=true
            if (_eof)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("EOF of {}", this);
                if (!_closed)
                {
                    _closed = true;
                    _channel.getByteBufferPool().release(_buffer);
                    IO.close(_in);
                }
                return Action.SUCCEEDED;
            }

            // Read from stream until buffer full or EOF
            BufferUtil.clearToFill(_buffer);
            while (_buffer.hasRemaining() && !_eof)
            {
                _eof = (_in.read(_buffer)) < 0;
            }

            // write what we have
            BufferUtil.flipToFlush(_buffer, 0);
            _written += _buffer.remaining();
            channelWrite(_buffer, _eof, this);

            return Action.SCHEDULED;
        }

        @Override
        public void onCompleteFailure(Throwable x)
        {
            _channel.getByteBufferPool().release(_buffer);
            IO.close(_in);
            super.onCompleteFailure(x);
        }
    }

    private static class WriteBlocker extends SharedBlockingCallback
    {
        private final HttpChannel _channel;

        private WriteBlocker(HttpChannel channel)
        {
            _channel = channel;
        }

        @Override
        protected long getIdleTimeout()
        {
            long blockingTimeout = _channel.getHttpConfiguration().getBlockingTimeout();
            if (blockingTimeout == 0)
                return _channel.getIdleTimeout();
            return blockingTimeout;
        }
    }

    private class WriteCompleteCB implements Callback
    {
        @Override
        public void succeeded()
        {
            onWriteComplete(true, null);
        }

        @Override
        public void failed(Throwable x)
        {
            onWriteComplete(true, x);
        }
    }
}
