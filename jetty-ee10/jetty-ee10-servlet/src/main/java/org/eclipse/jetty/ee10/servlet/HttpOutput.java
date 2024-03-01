//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritePendingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    /**
     * The output state
     */
    enum State
    {
        OPEN,     // Open
        CLOSE,    // Close needed from onWriteComplete
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
    
    private static final Logger LOG = LoggerFactory.getLogger(HttpOutput.class);
    private static final ThreadLocal<CharsetEncoder> _encoder = new ThreadLocal<>();

    private final ServletChannel _servletChannel;
    private final ServletChannelState _channelState;
    private final Blocker.Shared _writeBlocker;
    private ApiState _apiState = ApiState.BLOCKING;
    private State _state = State.OPEN;
    private boolean _softClose = false;
    private long _written;
    private long _flushed;
    private long _firstByteNanoTime = -1;
    private RetainableByteBuffer _aggregate;
    private int _bufferSize;
    private int _commitSize;
    private WriteListener _writeListener;
    private volatile Throwable _onError;
    private Callback _closedCallback;

    public HttpOutput(ServletChannel channel)
    {
        _servletChannel = channel;
        _channelState = _servletChannel.getServletRequestState();
        _writeBlocker = new Blocker.Shared();
        HttpConfiguration config = _servletChannel.getHttpConfiguration();
        _bufferSize = config.getOutputBufferSize();
        _commitSize = config.getOutputAggregationSize();
        if (_commitSize > _bufferSize)
        {
            LOG.warn("OutputAggregationSize {} exceeds bufferSize {}", _commitSize, _bufferSize);
            _commitSize = _bufferSize;
        }
    }

    /**
     * @return True if any content has been written via the {@link jakarta.servlet.http.HttpServletResponse} API.
     */
    public boolean isWritten()
    {
        return _written > 0;
    }

    /**
     * @return The bytes written via the {@link jakarta.servlet.http.HttpServletResponse} API.  This
     * may differ from the bytes reported by {@link org.eclipse.jetty.server.Response#getContentBytesWritten(Response)}
     * due to buffering, compression, other interception or writes that bypass the servlet API.
     */
    public long getWritten()
    {
        return _written;
    }

    public void reopen()
    {
        try (AutoLock l = _channelState.lock())
        {
            _softClose = false;
        }
    }

    private void channelWrite(ByteBuffer content, boolean complete) throws IOException
    {
        try (Blocker.Callback blocker = _writeBlocker.callback())
        {
            channelWrite(content, complete, blocker);
            blocker.block();
        }
    }

    private void channelWrite(ByteBuffer content, boolean last, Callback callback)
    {
        if (_firstByteNanoTime == -1)
        {
            long minDataRate = _servletChannel.getConnectionMetaData().getHttpConfiguration().getMinResponseDataRate();
            if (minDataRate > 0)
                _firstByteNanoTime = NanoTime.now();
            else
                _firstByteNanoTime = Long.MAX_VALUE;
        }
        _servletChannel.getResponse().write(last, content, callback);
    }

    private void onWriteComplete(boolean last, Throwable failure)
    {
        String state = null;
        boolean wake = false;
        Callback closedCallback = null;
        ByteBuffer closeContent = null;
        try (AutoLock l = _channelState.lock())
        {
            if (LOG.isDebugEnabled())
                state = stateString();

            // Transition to CLOSED state if we were the last write or we have failed
            if (last || failure != null)
            {
                _state = State.CLOSED;
                closedCallback = _closedCallback;
                _closedCallback = null;
                releaseBuffer();
                wake = updateApiState(failure);
            }
            else if (_state == State.CLOSE)
            {
                // Somebody called close or complete while we were writing.
                // We can now send a (probably empty) last buffer and then when it completes
                // onWriteComplete will be called again to actually execute the _completeCallback
                _state = State.CLOSING;
                closeContent = _aggregate != null && _aggregate.hasRemaining() ? _aggregate.getByteBuffer() : BufferUtil.EMPTY_BUFFER;
            }
            else
            {
                wake = updateApiState(null);
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("onWriteComplete({},{}) {}->{} c={} cb={} w={}",
                last, failure, state, stateString(), BufferUtil.toDetailString(closeContent), closedCallback, wake, failure);

        try
        {
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
                _servletChannel.execute(_servletChannel::handle);
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

        ByteBuffer byteBuffer = _aggregate.getByteBuffer();

        // compact to maximize space
        BufferUtil.compact(byteBuffer);

        return BufferUtil.space(byteBuffer);
    }

    public void softClose()
    {
        try (AutoLock l = _channelState.lock())
        {
            _softClose = true;
        }
    }

    /**
     * This method is invoked for the COMPLETE action handling in
     * HttpChannel.handle.  The callback passed typically will call completed
     * to finish the request cycle and so may need to asynchronously wait for:
     * a pending/blocked operation to finish and then either an async close or
     * wait for an application close to complete.
     * @param callback The callback to complete when writing the output is complete.
     */
    public void complete(Callback callback)
    {
        boolean succeeded = false;
        Throwable error = null;
        ByteBuffer content = null;
        try (AutoLock l = _channelState.lock())
        {
            // First check the API state for any unrecoverable situations
            switch (_apiState)
            {
                case UNREADY: // isReady() has returned false so a call to onWritePossible may happen at any time
                    error = new CancellationException("Completed whilst write unready");
                    break;

                case PENDING: // an async write is pending and may complete at any time
                    // If this is not the last write, then we must abort
                    if (_servletChannel.getServletContextResponse().isContentIncomplete(_written))
                        error = new CancellationException("Completed whilst write pending");
                    break;

                case BLOCKED: // another thread is blocked in a write or a close
                    error = new CancellationException("Completed whilst write blocked");
                    break;

                default:
                    break;
            }

            // If we can't complete due to the API state, then abort
            if (error != null)
            {
                _servletChannel.abort(error);
                _state = State.CLOSED;
            }
            else
            {
                // Otherwise check the output state to determine how to complete
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
                                content = _aggregate != null && _aggregate.hasRemaining() ? _aggregate.getByteBuffer() : BufferUtil.EMPTY_BUFFER;
                                break;

                            case ASYNC:
                            case READY:
                                // Output is idle in async state, so we can do an async close
                                _apiState = ApiState.PENDING;
                                _state = State.CLOSING;
                                content = _aggregate != null && _aggregate.hasRemaining() ? _aggregate.getByteBuffer() : BufferUtil.EMPTY_BUFFER;
                                break;

                            case UNREADY:
                            case PENDING:
                                // An operation is in progress, so we soft close now
                                _softClose = true;
                                // then trigger a close from onWriteComplete
                                _state = State.CLOSE;
                                break;

                            default:
                                throw new IllegalStateException();
                        }
                        break;
                }
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
        try (AutoLock l = _channelState.lock())
        {
            _state = State.CLOSED;
            releaseBuffer();
        }
    }

    @Override
    public void close() throws IOException
    {
        ByteBuffer content = null;
        Blocker.Callback blocker = null;
        try (AutoLock l = _channelState.lock())
        {
            if (_softClose)
                return;

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
                            blocker = _writeBlocker.callback();
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
                            blocker = _writeBlocker.callback();
                            content = _aggregate != null && _aggregate.hasRemaining() ? _aggregate.getByteBuffer() : BufferUtil.EMPTY_BUFFER;
                            break;

                        case BLOCKED:
                            // An blocking operation is in progress, so we soft close now
                            _softClose = true;
                            // then trigger a close from onWriteComplete
                            _state = State.CLOSE;
                            // and block until it is complete
                            blocker = _writeBlocker.callback();
                            _closedCallback = Callback.combine(_closedCallback, blocker);
                            break;

                        case ASYNC:
                        case READY:
                            // Output is idle in async state, so we can do an async close
                            _apiState = ApiState.PENDING;
                            _state = State.CLOSING;
                            content = _aggregate != null && _aggregate.hasRemaining() ? _aggregate.getByteBuffer() : BufferUtil.EMPTY_BUFFER;
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
            try (Blocker.Callback cb = blocker)
            {
                cb.block();
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
                try (Blocker.Callback b = blocker)
                {
                    channelWrite(content, true, blocker);
                    b.block();
                    onWriteComplete(true, null);
                }
                catch (Throwable t)
                {
                    onWriteComplete(true, t);
                    throw t;
                }
            }
        }
    }

    public ByteBuffer getByteBuffer()
    {
        try (AutoLock l = _channelState.lock())
        {
            return acquireBuffer().getByteBuffer();
        }
    }

    private RetainableByteBuffer acquireBuffer()
    {
        boolean useOutputDirectByteBuffers = _servletChannel.getConnectionMetaData().getHttpConfiguration().isUseOutputDirectByteBuffers();
        ByteBufferPool pool = _servletChannel.getRequest().getComponents().getByteBufferPool();
        if (_aggregate == null)
            _aggregate = pool.acquire(getBufferSize(), useOutputDirectByteBuffers);
        return _aggregate;
    }

    private void releaseBuffer()
    {
        if (_aggregate != null)
        {
            _aggregate.release();
            _aggregate = null;
        }
    }

    public boolean isClosed()
    {
        try (AutoLock l = _channelState.lock())
        {
            return _softClose || (_state != State.OPEN);
        }
    }

    public boolean isAsync()
    {
        try (AutoLock l = _channelState.lock())
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
        try (AutoLock l = _channelState.lock())
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
                            content = _aggregate != null && _aggregate.hasRemaining() ? _aggregate.getByteBuffer() : BufferUtil.EMPTY_BUFFER;
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
        {
            new AsyncFlush(false).iterate();
        }
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
        try (AutoLock l = _channelState.lock())
        {
            checkWritable();
            long written = _written + len;
            int space = maximizeAggregateSpace();
            last = _servletChannel.getServletContextResponse().isAllContentWritten(written);
            // Write will be aggregated if:
            //  + it is smaller than the commitSize
            //  + is not the last one, or is last but will fit in an already allocated aggregate buffer.
            aggregate = len <= _commitSize && (!last || (_aggregate != null && _aggregate.hasRemaining()) && len <= space);
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
                int filled = BufferUtil.fill(_aggregate.getByteBuffer(), b, off, len);

                // return if we are not complete, not full and filled all the content
                if (!flush)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("write(array) {} aggregated !flush {}",
                            stateString(), _aggregate);
                    return;
                }

                // adjust offset/length
                off += filled;
                len -= filled;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("write(array) {} last={} agg={} flush=true async={}, len={} {}",
                stateString(), last, aggregate, async, len, _aggregate);

        if (async)
        {
            // Do the asynchronous writing from the callback
            new AsyncWrite(b, off, len, last).iterate();
            return;
        }

        // Blocking write
        try
        {
            boolean complete = false;
            // flush any content from the aggregate
            if (_aggregate != null && _aggregate.hasRemaining())
            {
                ByteBuffer byteBuffer = _aggregate.getByteBuffer();

                complete = last && len == 0;
                channelWrite(byteBuffer, complete);

                // should we fill aggregate again from the buffer?
                if (len > 0 && !last && len <= _commitSize && len <= maximizeAggregateSpace())
                {
                    BufferUtil.append(byteBuffer, b, off, len);
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
            else if (last && !complete)
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
        try (AutoLock l = _channelState.lock())
        {
            checkWritable();
            long written = _written + len;
            last = _servletChannel.getServletContextResponse().isAllContentWritten(written);
            flush = last || len > 0 || (_aggregate != null && _aggregate.hasRemaining());

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
                boolean complete = false;
                if (_aggregate != null && _aggregate.hasRemaining())
                {
                    complete = last && len == 0;
                    channelWrite(_aggregate.getByteBuffer(), complete);
                }

                // write any remaining content in the buffer directly
                if (len > 0)
                    channelWrite(buffer, last);
                else if (last && !complete)
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
        try (AutoLock l = _channelState.lock())
        {
            checkWritable();
            long written = _written + 1;
            int space = maximizeAggregateSpace();
            last = _servletChannel.getServletContextResponse().isAllContentWritten(written);
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
            BufferUtil.append(_aggregate.getByteBuffer(), (byte)b);
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
                channelWrite(_aggregate.getByteBuffer(), last);
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

        s = String.valueOf(s);

        String charset = _servletChannel.getServletContextResponse().getCharacterEncoding(false);
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
        ByteBufferPool pool = _servletChannel.getRequest().getComponents().getByteBufferPool();
        RetainableByteBuffer out = pool.acquire((int)(1 + (s.length() + 2) * encoder.averageBytesPerChar()), false);
        try
        {
            CharBuffer in = CharBuffer.wrap(s);
            CharBuffer crlf = eoln ? CharBuffer.wrap("\r\n") : null;
            ByteBuffer byteBuffer = out.getByteBuffer();
            BufferUtil.flipToFill(byteBuffer);

            while (true)
            {
                CoderResult result;
                if (in.hasRemaining())
                {
                    result = encoder.encode(in, byteBuffer, crlf == null);
                    if (result.isUnderflow())
                        if (crlf == null)
                            break;
                        else
                            continue;
                }
                else if (crlf != null && crlf.hasRemaining())
                {
                    result = encoder.encode(crlf, byteBuffer, true);
                    if (result.isUnderflow())
                    {
                        if (!encoder.flush(byteBuffer).isUnderflow())
                            result.throwException();
                        break;
                    }
                }
                else
                    break;

                if (result.isOverflow())
                {
                    BufferUtil.flipToFlush(byteBuffer, 0);
                    RetainableByteBuffer bigger = pool.acquire(out.capacity() + s.length() + 2, out.isDirect());
                    BufferUtil.flipToFill(bigger.getByteBuffer());
                    bigger.getByteBuffer().put(byteBuffer);
                    out.release();
                    BufferUtil.flipToFill(bigger.getByteBuffer());
                    out = bigger;
                    byteBuffer = bigger.getByteBuffer();
                    continue;
                }

                result.throwException();
            }

            BufferUtil.flipToFlush(byteBuffer, 0);
            write(byteBuffer.array(), byteBuffer.arrayOffset(), byteBuffer.remaining());
        }
        finally
        {
            out.release();
        }
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
        try (Blocker.Callback blocker = _writeBlocker.callback())
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
        try (Blocker.Callback blocker = _writeBlocker.callback())
        {
            new ReadableByteChannelWritingCB(in, blocker).iterate();
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
        try (AutoLock l = _channelState.lock())
        {
            if (_aggregate != null && _aggregate.hasRemaining())
            {
                callback.failed(new IOException("cannot sendContent() after write()"));
                return false;
            }
            if (_servletChannel.isCommitted())
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
     *
     * @param bytes the number of bytes flushed
     * @throws IOException if the minimum data rate, when set, is not respected
     * @see org.eclipse.jetty.io.WriteFlusher.Listener
     */
    public void onFlushed(long bytes) throws IOException
    {
        if (_firstByteNanoTime == -1 || _firstByteNanoTime == Long.MAX_VALUE)
            return;
        long minDataRate = _servletChannel.getConnectionMetaData().getHttpConfiguration().getMinResponseDataRate();
        _flushed += bytes;
        long minFlushed = minDataRate * NanoTime.millisSince(_firstByteNanoTime) / TimeUnit.SECONDS.toMillis(1);
        if (LOG.isDebugEnabled())
            LOG.debug("Flushed bytes min/actual {}/{}", minFlushed, _flushed);
        if (_flushed < minFlushed)
        {
            IOException ioe = new IOException(String.format("Response content data rate < %d B/s", minDataRate));
            _servletChannel.abort(ioe);
            throw ioe;
        }
    }

    public void recycle()
    {
        try (AutoLock l = _channelState.lock())
        {
            _state = State.OPEN;
            _apiState = ApiState.BLOCKING;
            _softClose = true; // Stay closed until next request
            HttpConfiguration config = _servletChannel.getConnectionMetaData().getHttpConfiguration();
            _bufferSize = config.getOutputBufferSize();
            _commitSize = config.getOutputAggregationSize();
            if (_commitSize > _bufferSize)
                _commitSize = _bufferSize;
            releaseBuffer();
            _written = 0;
            _writeListener = null;
            _onError = null;
            _firstByteNanoTime = -1;
            _flushed = 0;
            _closedCallback = null;
        }
    }

    public void resetBuffer()
    {
        try (AutoLock l = _channelState.lock())
        {
            if (_aggregate != null)
                _aggregate.clear();
            _written = 0;
        }
    }

    @Override
    public void setWriteListener(WriteListener writeListener)
    {
        if (!_servletChannel.getServletRequestState().isAsync())
            throw new IllegalStateException("!ASYNC: " + stateString());
        boolean wake;
        try (AutoLock l = _channelState.lock())
        {
            if (_apiState != ApiState.BLOCKING)
                throw new IllegalStateException("!OPEN" + stateString());
            _apiState = ApiState.READY;
            _writeListener = writeListener;
            wake = _servletChannel.getServletRequestState().onWritePossible();
        }
        if (wake)
            _servletChannel.execute(_servletChannel::handle);
    }

    @Override
    public boolean isReady()
    {
        try (AutoLock l = _channelState.lock())
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

        try (AutoLock l = _channelState.lock())
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
            {
                t.addSuppressed(error);
                LOG.debug("Failed in call onError on {}", _writeListener, t);
            }
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
        try (AutoLock l = _channelState.lock())
        {
            return String.format("%s@%x{%s}", this.getClass().getSimpleName(), hashCode(), stateString());
        }
    }

    private abstract class ChannelWriteCB extends IteratingCallback
    {
        final boolean _last;

        private ChannelWriteCB(boolean last)
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
        private final Callback _callback;

        private NestedChannelWriteCB(Callback callback, boolean last)
        {
            super(last);
            _callback = callback;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _callback.getInvocationType();
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
                ExceptionUtil.addSuppressedIfNotAssociated(e, t);
            }
            finally
            {
                _callback.failed(e);
            }
        }
    }

    private class AsyncFlush extends ChannelWriteCB
    {
        private volatile boolean _flushed;

        private AsyncFlush(boolean last)
        {
            super(last);
        }

        @Override
        protected Action process() throws Exception
        {
            if (_aggregate != null && _aggregate.hasRemaining())
            {
                _flushed = true;
                channelWrite(_aggregate.getByteBuffer(), false, this);
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

        private AsyncWrite(byte[] b, int off, int len, boolean last)
        {
            super(last);
            _buffer = ByteBuffer.wrap(b, off, len);
            _len = len;
            // always use a view for large byte arrays to avoid JVM pooling large direct buffers
            _slice = _len < getBufferSize() ? null : _buffer.duplicate();
        }

        private AsyncWrite(ByteBuffer buffer, boolean last)
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
            if (_aggregate != null && _aggregate.hasRemaining())
            {
                _completed = _len == 0;
                channelWrite(_aggregate.getByteBuffer(), _last && _completed, this);
                return Action.SCHEDULED;
            }

            // Can we just aggregate the remainder?
            if (!_last && _aggregate != null && _len < maximizeAggregateSpace() && _len < _commitSize)
            {
                ByteBuffer byteBuffer = _aggregate.getByteBuffer();
                int position = BufferUtil.flipToFill(byteBuffer);
                BufferUtil.put(_buffer, byteBuffer);
                BufferUtil.flipToFlush(byteBuffer, position);
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
     * InputStream and write it to this HttpOutput.
     * A non direct buffer of size {@link HttpOutput#getBufferSize()} is used.
     * This callback is passed to the {@link Content.Sink#write(boolean, ByteBuffer, Callback)} to
     * be notified as each buffer is written and only once all the input is consumed will the
     * wrapped {@link Callback#succeeded()} method be called.
     */
    private class InputStreamWritingCB extends NestedChannelWriteCB
    {
        private final InputStream _in;
        private final RetainableByteBuffer _buffer;
        private boolean _eof;
        private boolean _closed;

        private InputStreamWritingCB(InputStream in, Callback callback)
        {
            super(callback, true);
            _in = in;
            // Reading from InputStream requires byte[], don't use direct buffers.
            ByteBufferPool pool = _servletChannel.getRequest().getComponents().getByteBufferPool();
            _buffer = pool.acquire(getBufferSize(), false);
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
                    _closed = true;
                return Action.SUCCEEDED;
            }

            ByteBuffer byteBuffer = _buffer.getByteBuffer();

            // Read until buffer full or EOF
            int len = 0;
            while (len < byteBuffer.capacity() && !_eof)
            {
                int r = _in.read(byteBuffer.array(), byteBuffer.arrayOffset() + len, byteBuffer.capacity() - len);
                if (r < 0)
                    _eof = true;
                else
                    len += r;
            }

            // write what we have
            byteBuffer.position(0);
            byteBuffer.limit(len);
            _written += len;
            channelWrite(byteBuffer, _eof, this);
            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            _buffer.release();
            IO.close(_in);
            super.onCompleteSuccess();
        }

        @Override
        public void onCompleteFailure(Throwable x)
        {
            _buffer.release();
            IO.close(_in);
            super.onCompleteFailure(x);
        }
    }

    /**
     * An iterating callback that will take content from a
     * ReadableByteChannel and write it to this HttpOutput.
     * A {@link ByteBuffer} of size {@link HttpOutput#getBufferSize()} is used that will be direct if
     * {@code HttpChannel#isUseOutputDirectByteBuffers()} is true.
     * This callback is passed to the {@link Content.Sink#write(boolean, ByteBuffer, Callback)} to
     * be notified as each buffer is written and only once all the input is consumed will the
     * wrapped {@link Callback#succeeded()} method be called.
     */
    private class ReadableByteChannelWritingCB extends NestedChannelWriteCB
    {
        private final ReadableByteChannel _in;
        private final RetainableByteBuffer _buffer;
        private boolean _eof;
        private boolean _closed;

        private ReadableByteChannelWritingCB(ReadableByteChannel in, Callback callback)
        {
            super(callback, true);
            _in = in;
            boolean useOutputDirectByteBuffers = _servletChannel.getConnectionMetaData().getHttpConfiguration().isUseOutputDirectByteBuffers();
            ByteBufferPool pool = _servletChannel.getRequest().getComponents().getByteBufferPool();
            _buffer = pool.acquire(getBufferSize(), useOutputDirectByteBuffers);
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
                    _closed = true;
                return Action.SUCCEEDED;
            }

            ByteBuffer byteBuffer = _buffer.getByteBuffer();

            // Read from stream until buffer full or EOF
            BufferUtil.clearToFill(byteBuffer);
            while (byteBuffer.hasRemaining() && !_eof)
            {
                _eof = (_in.read(byteBuffer)) < 0;
            }

            // write what we have
            BufferUtil.flipToFlush(byteBuffer, 0);
            _written += byteBuffer.remaining();
            channelWrite(byteBuffer, _eof, this);

            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            _buffer.release();
            IO.close(_in);
            super.onCompleteSuccess();
        }

        @Override
        public void onCompleteFailure(Throwable x)
        {
            _buffer.release();
            IO.close(_in);
            super.onCompleteFailure(x);
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

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }
    }
}
