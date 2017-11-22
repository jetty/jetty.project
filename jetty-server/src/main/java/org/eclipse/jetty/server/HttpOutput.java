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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritePendingException;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;

import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.IteratingNestedCallback;
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
         * @param content  The content to be written or an empty buffer.
         * @param last     True if this is the last call to write
         * @param callback The callback to use to indicate {@link Callback#succeeded()}
         *                 or {@link Callback#failed(Throwable)}.
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
         *                               committed and buffers and/or headers cannot be reset.
         */
        default void resetBuffer() throws IllegalStateException
        {
            Interceptor next = getNextInterceptor();
            if (next != null)
                next.resetBuffer();
        }
    }

    private static Logger LOG = Log.getLogger(HttpOutput.class);

    private final HttpChannel _channel;
    private final SharedBlockingCallback _writeBlocker;
    private Interceptor _interceptor;

    /**
     * Bytes written via the write API (excludes bytes written via sendContent). Used to autocommit once content length is written.
     */
    private long _written;

    private ByteBuffer _aggregate;
    private int _bufferSize;
    private int _commitSize;
    private WriteListener _writeListener;
    private volatile Throwable _onError;
    /*
    ACTION             OPEN       ASYNC      READY      PENDING       UNREADY       CLOSED
    -------------------------------------------------------------------------------------------
    setWriteListener() READY->owp ise        ise        ise           ise           ise
    write()            OPEN       ise        PENDING    wpe           wpe           eof
    flush()            OPEN       ise        PENDING    wpe           wpe           eof
    close()            CLOSED     CLOSED     CLOSED     CLOSED        CLOSED        CLOSED
    isReady()          OPEN:true  READY:true READY:true UNREADY:false UNREADY:false CLOSED:true
    write completed    -          -          -          ASYNC         READY->owp    -
    */
    private enum OutputState
    {
        OPEN, ASYNC, READY, PENDING, UNREADY, ERROR, CLOSED
    }

    private final AtomicReference<OutputState> _state = new AtomicReference<>(OutputState.OPEN);

    public HttpOutput(HttpChannel channel)
    {
        _channel = channel;
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
        _state.set(OutputState.OPEN);
    }

    private boolean isLastContentToWrite(int len)
    {
        _written += len;
        return _channel.getResponse().isAllContentWritten(_written);
    }

    public boolean isAllContentWritten()
    {
        return _channel.getResponse().isAllContentWritten(_written);
    }

    protected Blocker acquireWriteBlockingCallback() throws IOException
    {
        return _writeBlocker.acquire();
    }

    private void write(ByteBuffer content, boolean complete) throws IOException
    {
        try (Blocker blocker = _writeBlocker.acquire())
        {
            write(content, complete, blocker);
            blocker.block();
        }
        catch (Exception failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(failure);
            abort(failure);
            if (failure instanceof IOException)
                throw failure;
            throw new IOException(failure);
        }
    }

    protected void write(ByteBuffer content, boolean complete, Callback callback)
    {
        _interceptor.write(content, complete, callback);
    }

    private void abort(Throwable failure)
    {
        closed();
        _channel.abort(failure);
    }

    @Override
    public void close()
    {
        while(true)
        {
            OutputState state = _state.get();
            switch (state)
            {
                case CLOSED:
                {
                    return;
                }
                case ASYNC:
                {
                    // A close call implies a write operation, thus in asynchronous mode
                    // a call to isReady() that returned true should have been made.
                    // However it is desirable to allow a close at any time, specially if 
                    // complete is called.   Thus we simulate a call to isReady here, assuming
                    // that we can transition to READY.
                    if (!_state.compareAndSet(state, OutputState.READY))
                        continue;
                    break;
                }
                case UNREADY:
                case PENDING:
                {
                    // A close call implies a write operation, thus in asynchronous mode
                    // a call to isReady() that returned true should have been made.
                    // However it is desirable to allow a close at any time, specially if 
                    // complete is called.   Because the prior write has not yet completed
                    // and/or isReady has not been called, this close is allowed, but will
                    // abort the response.
                    if (!_state.compareAndSet(state, OutputState.CLOSED))
                        continue;
                    IOException ex = new IOException("Closed while Pending/Unready");
                    LOG.warn(ex.toString());
                    LOG.debug(ex);
                    _channel.abort(ex);
                    return;
                }
                default:
                {
                    if (!_state.compareAndSet(state, OutputState.CLOSED))
                        continue;

                    // Do a normal close by writing the aggregate buffer or an empty buffer. If we are
                    // not including, then indicate this is the last write.
                    try
                    {
                        write(BufferUtil.hasContent(_aggregate) ? _aggregate : BufferUtil.EMPTY_BUFFER, !_channel.getResponse().isIncluding());
                    }
                    catch (IOException x)
                    {
                        LOG.ignore(x); // Ignore it, it's been already logged in write().
                    }
                    finally
                    {
                        releaseBuffer();
                    }
                    // Return even if an exception is thrown by write().
                    return;
                }
            }
        }
    }

    /**
     * Called to indicate that the last write has been performed.
     * It updates the state and performs cleanup operations.
     */
    void closed()
    {
        while (true)
        {
            OutputState state = _state.get();
            switch (state)
            {
                case CLOSED:
                {
                    return;
                }
                case UNREADY:
                {
                    if (_state.compareAndSet(state, OutputState.ERROR))
                        _writeListener.onError(_onError == null ? new EofException("Async closed") : _onError);
                    break;
                }
                default:
                {
                    if (!_state.compareAndSet(state, OutputState.CLOSED))
                        break;

                    try
                    {
                        _channel.getResponse().closeOutput();
                    }
                    catch (Throwable x)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug(x);
                        abort(x);
                    }
                    finally
                    {
                        releaseBuffer();
                    }
                    // Return even if an exception is thrown by closeOutput().
                    return;
                }
            }
        }
    }

    private void releaseBuffer()
    {
        if (_aggregate != null)
        {
            _channel.getConnector().getByteBufferPool().release(_aggregate);
            _aggregate = null;
        }
    }

    public boolean isClosed()
    {
        return _state.get() == OutputState.CLOSED;
    } 

    public boolean isAsync()
    {
        switch (_state.get())
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

    @Override
    public void flush() throws IOException
    {
        while (true)
        {
            switch (_state.get())
            {
                case OPEN:
                    write(BufferUtil.hasContent(_aggregate) ? _aggregate : BufferUtil.EMPTY_BUFFER, false);
                    return;

                case ASYNC:
                    throw new IllegalStateException("isReady() not called");

                case READY:
                    if (!_state.compareAndSet(OutputState.READY, OutputState.PENDING))
                        continue;
                    new AsyncFlush().iterate();
                    return;

                case PENDING:
                    return;
                    
                case UNREADY:
                    throw new WritePendingException();

                case ERROR:
                    throw new EofException(_onError);

                case CLOSED:
                    return;

                default:
                    throw new IllegalStateException();
            }
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        // Async or Blocking ?
        while (true)
        {
            switch (_state.get())
            {
                case OPEN:
                    // process blocking below
                    break;

                case ASYNC:
                    throw new IllegalStateException("isReady() not called");

                case READY:
                    if (!_state.compareAndSet(OutputState.READY, OutputState.PENDING))
                        continue;

                    // Should we aggregate?
                    boolean last = isLastContentToWrite(len);
                    if (!last && len <= _commitSize)
                    {
                        if (_aggregate == null)
                            _aggregate = _channel.getByteBufferPool().acquire(getBufferSize(), _interceptor.isOptimizedForDirectBuffers());

                        // YES - fill the aggregate with content from the buffer
                        int filled = BufferUtil.fill(_aggregate, b, off, len);

                        // return if we are not complete, not full and filled all the content
                        if (filled == len && !BufferUtil.isFull(_aggregate))
                        {
                            if (!_state.compareAndSet(OutputState.PENDING, OutputState.ASYNC))
                                throw new IllegalStateException();
                            return;
                        }

                        // adjust offset/length
                        off += filled;
                        len -= filled;
                    }

                    // Do the asynchronous writing from the callback
                    new AsyncWrite(b, off, len, last).iterate();
                    return;

                case PENDING:
                case UNREADY:
                    throw new WritePendingException();

                case ERROR:
                    throw new EofException(_onError);

                case CLOSED:
                    throw new EofException("Closed");

                default:
                    throw new IllegalStateException();
            }
            break;
        }

        // handle blocking write

        // Should we aggregate?
        int capacity = getBufferSize();
        boolean last = isLastContentToWrite(len);
        if (!last && len <= _commitSize)
        {
            if (_aggregate == null)
                _aggregate = _channel.getByteBufferPool().acquire(capacity, _interceptor.isOptimizedForDirectBuffers());

            // YES - fill the aggregate with content from the buffer
            int filled = BufferUtil.fill(_aggregate, b, off, len);

            // return if we are not complete, not full and filled all the content
            if (filled == len && !BufferUtil.isFull(_aggregate))
                return;

            // adjust offset/length
            off += filled;
            len -= filled;
        }

        // flush any content from the aggregate
        if (BufferUtil.hasContent(_aggregate))
        {
            write(_aggregate, last && len == 0);

            // should we fill aggregate again from the buffer?
            if (len > 0 && !last && len <= _commitSize && len <= BufferUtil.space(_aggregate))
            {
                BufferUtil.append(_aggregate, b, off, len);
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
                view.limit(p + getBufferSize());
                write(view, false);
                len -= getBufferSize();
                view.limit(l + Math.min(len, getBufferSize()));
                view.position(l);
            }
            write(view, last);
        }
        else if (last)
        {
            write(BufferUtil.EMPTY_BUFFER, true);
        }

        if (last)
            closed();
    }

    public void write(ByteBuffer buffer) throws IOException
    {
        // Async or Blocking ?
        while (true)
        {
            switch (_state.get())
            {
                case OPEN:
                    // process blocking below
                    break;

                case ASYNC:
                    throw new IllegalStateException("isReady() not called");

                case READY:
                    if (!_state.compareAndSet(OutputState.READY, OutputState.PENDING))
                        continue;

                    // Do the asynchronous writing from the callback
                    boolean last = isLastContentToWrite(buffer.remaining());
                    new AsyncWrite(buffer, last).iterate();
                    return;

                case PENDING:
                case UNREADY:
                    throw new WritePendingException();

                case ERROR:
                    throw new EofException(_onError);

                case CLOSED:
                    throw new EofException("Closed");

                default:
                    throw new IllegalStateException();
            }
            break;
        }

        // handle blocking write
        int len = BufferUtil.length(buffer);
        boolean last = isLastContentToWrite(len);

        // flush any content from the aggregate
        if (BufferUtil.hasContent(_aggregate))
            write(_aggregate, last && len == 0);

        // write any remaining content in the buffer directly
        if (len > 0)
            write(buffer, last);
        else if (last)
            write(BufferUtil.EMPTY_BUFFER, true);

        if (last)
            closed();
    }

    @Override
    public void write(int b) throws IOException
    {
        _written += 1;
        boolean complete = _channel.getResponse().isAllContentWritten(_written);

        // Async or Blocking ?
        while (true)
        {
            switch (_state.get())
            {
                case OPEN:
                    if (_aggregate == null)
                        _aggregate = _channel.getByteBufferPool().acquire(getBufferSize(), _interceptor.isOptimizedForDirectBuffers());
                    BufferUtil.append(_aggregate, (byte)b);

                    // Check if all written or full
                    if (complete || BufferUtil.isFull(_aggregate))
                    {
                        write(_aggregate, complete);
                        if (complete)
                            closed();
                    }
                    break;

                case ASYNC:
                    throw new IllegalStateException("isReady() not called");

                case READY:
                    if (!_state.compareAndSet(OutputState.READY, OutputState.PENDING))
                        continue;

                    if (_aggregate == null)
                        _aggregate = _channel.getByteBufferPool().acquire(getBufferSize(), _interceptor.isOptimizedForDirectBuffers());
                    BufferUtil.append(_aggregate, (byte)b);

                    // Check if all written or full
                    if (!complete && !BufferUtil.isFull(_aggregate))
                    {
                        if (!_state.compareAndSet(OutputState.PENDING, OutputState.ASYNC))
                            throw new IllegalStateException();
                        return;
                    }

                    // Do the asynchronous writing from the callback
                    new AsyncFlush().iterate();
                    return;

                case PENDING:
                case UNREADY:
                    throw new WritePendingException();

                case ERROR:
                    throw new EofException(_onError);

                case CLOSED:
                    throw new EofException("Closed");

                default:
                    throw new IllegalStateException();
            }
            break;
        }
    }

    @Override
    public void print(String s) throws IOException
    {
        if (isClosed())
            throw new IOException("Closed");

        write(s.getBytes(_channel.getResponse().getCharacterEncoding()));
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
        write(content, true);
        closed();
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
        catch (Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(failure);
            abort(failure);
            throw failure;
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
        catch (Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(failure);
            abort(failure);
            throw failure;
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
        catch (Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(failure);
            abort(failure);
            throw failure;
        }
    }

    /**
     * Asynchronous send of whole content.
     *
     * @param content  The whole content to send
     * @param callback The callback to use to notify success or failure
     */
    public void sendContent(ByteBuffer content, final Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sendContent(buffer={},{})", BufferUtil.toDetailString(content), callback);

        _written += content.remaining();
        write(content, true, new Callback.Nested(callback)
        {
            @Override
            public void succeeded()
            {
                closed();
                super.succeeded();
            }

            @Override
            public void failed(Throwable x)
            {
                abort(x);
                super.failed(x);
            }
        });
    }

    /**
     * Asynchronous send of stream content.
     * The stream will be closed after reading all content.
     *
     * @param in       The stream content to send
     * @param callback The callback to use to notify success or failure
     */
    public void sendContent(InputStream in, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sendContent(stream={},{})", in, callback);

        new InputStreamWritingCB(in, callback).iterate();
    }

    /**
     * Asynchronous send of channel content.
     * The channel will be closed after reading all content.
     *
     * @param in       The channel content to send
     * @param callback The callback to use to notify success or failure
     */
    public void sendContent(ReadableByteChannel in, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sendContent(channel={},{})", in, callback);

        new ReadableByteChannelWritingCB(in, callback).iterate();
    }

    /**
     * Asynchronous send of HTTP content.
     *
     * @param httpContent The HTTP content to send
     * @param callback    The callback to use to notify success or failure
     */
    public void sendContent(HttpContent httpContent, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sendContent(http={},{})", httpContent, callback);

        if (BufferUtil.hasContent(_aggregate))
        {
            callback.failed(new IOException("cannot sendContent() after write()"));
            return;
        }
        if (_channel.isCommitted())
        {
            callback.failed(new IOException("cannot sendContent(), output already committed"));
            return;
        }

        while (true)
        {
            switch (_state.get())
            {
                case OPEN:
                    if (!_state.compareAndSet(OutputState.OPEN, OutputState.PENDING))
                        continue;
                    break;

                case ERROR:
                    callback.failed(new EofException(_onError));
                    return;

                case CLOSED:
                    callback.failed(new EofException("Closed"));
                    return;

                default:
                    throw new IllegalStateException();
            }
            break;
        }

        ByteBuffer buffer = _channel.useDirectBuffers() ? httpContent.getDirectBuffer() : null;
        if (buffer == null)
            buffer = httpContent.getIndirectBuffer();

        if (buffer != null)
        {
            sendContent(buffer, callback);
            return;
        }

        try
        {
            ReadableByteChannel rbc = httpContent.getReadableByteChannel();
            if (rbc != null)
            {
                // Close of the rbc is done by the async sendContent
                sendContent(rbc, callback);
                return;
            }

            InputStream in = httpContent.getInputStream();
            if (in != null)
            {
                sendContent(in, callback);
                return;
            }

            throw new IllegalArgumentException("unknown content for " + httpContent);
        }
        catch (Throwable th)
        {
            abort(th);
            callback.failed(th);
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

    public void recycle()
    {
        _interceptor = _channel;
        HttpConfiguration config = _channel.getHttpConfiguration();
        _bufferSize = config.getOutputBufferSize();
        _commitSize = config.getOutputAggregationSize();
        if (_commitSize > _bufferSize)
            _commitSize = _bufferSize;
        releaseBuffer();
        _written = 0;
        _writeListener = null;
        _onError = null;
        reopen();
    }

    public void resetBuffer()
    {
        _interceptor.resetBuffer();
        if (BufferUtil.hasContent(_aggregate))
            BufferUtil.clear(_aggregate);
        _written = 0;
        reopen();
    }

    @Override
    public void setWriteListener(WriteListener writeListener)
    {
        if (!_channel.getState().isAsync())
            throw new IllegalStateException("!ASYNC");

        if (_state.compareAndSet(OutputState.OPEN, OutputState.READY))
        {
            _writeListener = writeListener;
            if (_channel.getState().onWritePossible())
                _channel.execute(_channel);
        }
        else
            throw new IllegalStateException();
    }

    @Override
    public boolean isReady()
    {
        while (true)
        {
            switch (_state.get())
            {
                case OPEN:
                    return true;

                case ASYNC:
                    if (!_state.compareAndSet(OutputState.ASYNC, OutputState.READY))
                        continue;
                    return true;

                case READY:
                    return true;

                case PENDING:
                    if (!_state.compareAndSet(OutputState.PENDING, OutputState.UNREADY))
                        continue;
                    return false;

                case UNREADY:
                    return false;

                case ERROR:
                    return true;

                case CLOSED:
                    return true;

                default:
                    throw new IllegalStateException();
            }
        }
    }

    @Override
    public void run()
    {
        while (true)
        {
            OutputState state = _state.get();

            if (_onError != null)
            {
                switch (state)
                {
                    case CLOSED:
                    case ERROR:
                    {
                        _onError = null;
                        return;
                    }
                    default:
                    {
                        if (_state.compareAndSet(state, OutputState.ERROR))
                        {
                            Throwable th = _onError;
                            _onError = null;
                            if (LOG.isDebugEnabled())
                                LOG.debug("onError", th);
                            _writeListener.onError(th);
                            close();
                            return;
                        }
                    }
                }
                continue;
            }

            // We do not check the state here.  Strictly speaking the state should 
            // always be READY when run is called.  However, other async threads or 
            // a prior call by this thread to onDataAvailable may have called write
            // after onWritePossible was called, so the state could be any of the 
            // write states.  
            //
            // Even if the state is CLOSED, we need to call onWritePossible to tell 
            // async producer that the last write completed.
            //
            // We have to trust the scheduling of this run was done 
            // for good reason, that is protected correctly by HttpChannelState and 
            // that implementations of onWritePossible will 
            // themselves check isReady().  If multiple threads are calling write,
            // then they must either rely on only a single container thread being
            // dispatched or perform their own mutual exclusion.
            try
            {
                _writeListener.onWritePossible();
                break;
            }
            catch (Throwable e)
            {
                _onError = e;
            }            
        }
    }

    private void close(Closeable resource)
    {
        try
        {
            resource.close();
        }
        catch (Throwable x)
        {
            LOG.ignore(x);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s}", this.getClass().getSimpleName(), hashCode(), _state.get());
    }

    private abstract class AsyncICB extends IteratingCallback
    {
        final boolean _last;

        AsyncICB(boolean last)
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
            while (true)
            {
                OutputState last = _state.get();
                switch (last)
                {
                    case PENDING:
                        if (!_state.compareAndSet(OutputState.PENDING, OutputState.ASYNC))
                            continue;
                        break;

                    case UNREADY:
                        if (!_state.compareAndSet(OutputState.UNREADY, OutputState.READY))
                            continue;
                        if (_last)
                            closed();
                        if (_channel.getState().onWritePossible())
                            _channel.execute(_channel);
                        break;

                    case CLOSED:
                        break;

                    default:
                        throw new IllegalStateException();
                }
                break;
            }
        }

        @Override
        public void onCompleteFailure(Throwable e)
        {
            _onError = e == null ? new IOException() : e;
            if (_channel.getState().onWritePossible())
                _channel.execute(_channel);
        }
    }

    private class AsyncFlush extends AsyncICB
    {
        protected volatile boolean _flushed;

        public AsyncFlush()
        {
            super(false);
        }

        @Override
        protected Action process()
        {
            if (BufferUtil.hasContent(_aggregate))
            {
                _flushed = true;
                write(_aggregate, false, this);
                return Action.SCHEDULED;
            }

            if (!_flushed)
            {
                _flushed = true;
                write(BufferUtil.EMPTY_BUFFER, false, this);
                return Action.SCHEDULED;
            }

            return Action.SUCCEEDED;
        }
    }

    private class AsyncWrite extends AsyncICB
    {
        private final ByteBuffer _buffer;
        private final ByteBuffer _slice;
        private final int _len;
        protected volatile boolean _completed;

        public AsyncWrite(byte[] b, int off, int len, boolean last)
        {
            super(last);
            _buffer = ByteBuffer.wrap(b, off, len);
            _len = len;
            // always use a view for large byte arrays to avoid JVM pooling large direct buffers
            _slice = _len < getBufferSize() ? null : _buffer.duplicate();
        }

        public AsyncWrite(ByteBuffer buffer, boolean last)
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
        protected Action process()
        {
            // flush any content from the aggregate
            if (BufferUtil.hasContent(_aggregate))
            {
                _completed = _len == 0;
                write(_aggregate, _last && _completed, this);
                return Action.SCHEDULED;
            }

            // Can we just aggregate the remainder?
            if (!_last && _len < BufferUtil.space(_aggregate) && _len < _commitSize)
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
                    write(_buffer, _last, this);
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
                write(_slice, _last && _completed, this);
                return Action.SCHEDULED;
            }

            // all content written, but if we have not yet signal completion, we
            // need to do so
            if (_last && !_completed)
            {
                _completed = true;
                write(BufferUtil.EMPTY_BUFFER, true, this);
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
    private class InputStreamWritingCB extends IteratingNestedCallback
    {
        private final InputStream _in;
        private final ByteBuffer _buffer;
        private boolean _eof;

        public InputStreamWritingCB(InputStream in, Callback callback)
        {
            super(callback);
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
                // Handle EOF
                _in.close();
                closed();
                _channel.getByteBufferPool().release(_buffer);
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
            write(_buffer, _eof, this);
            return Action.SCHEDULED;
        }

        @Override
        public void onCompleteFailure(Throwable x)
        {
            abort(x);
            _channel.getByteBufferPool().release(_buffer);
            HttpOutput.this.close(_in);
            super.onCompleteFailure(x);
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
    private class ReadableByteChannelWritingCB extends IteratingNestedCallback
    {
        private final ReadableByteChannel _in;
        private final ByteBuffer _buffer;
        private boolean _eof;

        public ReadableByteChannelWritingCB(ReadableByteChannel in, Callback callback)
        {
            super(callback);
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
                _in.close();
                closed();
                _channel.getByteBufferPool().release(_buffer);
                return Action.SUCCEEDED;
            }

            // Read from stream until buffer full or EOF
            BufferUtil.clearToFill(_buffer);
            while (_buffer.hasRemaining() && !_eof)
                _eof = (_in.read(_buffer)) < 0;

            // write what we have
            BufferUtil.flipToFlush(_buffer, 0);
            _written += _buffer.remaining();
            write(_buffer, _eof, this);

            return Action.SCHEDULED;
        }

        @Override
        public void onCompleteFailure(Throwable x)
        {
            abort(x);
            _channel.getByteBufferPool().release(_buffer);
            HttpOutput.this.close(_in);
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
}
