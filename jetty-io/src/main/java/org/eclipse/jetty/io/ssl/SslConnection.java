// ========================================================================
// Copyright (c) 2004-2011 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.io.ssl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.io.AbstractAsyncConnection;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An AsyncConnection that acts as an interceptor between and EndPoint and another
 * Connection, that implements TLS encryption using an {@link SSLEngine}.
 * <p>
 * The connector uses an {@link EndPoint} (like {@link SelectChannelEndPoint}) as
 * it's source/sink of encrypted data.   It then provides {@link #getAppEndPoint()} to
 * expose a source/sink of unencrypted data to another connection (eg HttpConnection).
 */
public class SslConnection extends AbstractAsyncConnection
{
    private static final Logger logger = Log.getLogger(SslConnection.class);
    private final ByteBufferPool byteBufferPool;
    private final SSLEngine sslEngine;
    private final SSLMachine sslMachine;
    private final AsyncEndPoint appEndPoint;
    private boolean direct = false;
    private ReadState readState = ReadState.HANDSHAKING;
    private WriteState writeState = WriteState.HANDSHAKING;
    private ByteBuffer appInput;
    private ByteBuffer netInput;
    private ByteBuffer netOutput;
    private Callback appReader;
    private Object readContext;

    public SslConnection(ByteBufferPool byteBufferPool, Executor executor, AsyncEndPoint endPoint, SSLEngine sslEngine)
    {
        super(endPoint, executor);
        this.byteBufferPool = byteBufferPool;
        this.sslEngine = sslEngine;
        this.sslMachine = new ConnectionSSLMachine(sslEngine);
        this.appEndPoint = new ApplicationEndPoint();
    }

    public SSLEngine getSSLEngine()
    {
        return sslEngine;
    }

    @Override
    public void onOpen()
    {
        try
        {
            super.onOpen();
            scheduleOnReadable();
            sslEngine.beginHandshake();
        }
        catch (SSLException x)
        {
            throw new RuntimeIOException(x);
        }
    }

    public AsyncEndPoint getAppEndPoint()
    {
        return appEndPoint;
    }

    private void updateReadState(ReadState newReadState)
    {
        ReadState oldReadState = readState;
        switch (oldReadState)
        {
            case HANDSHAKING:
            {
                if (newReadState != ReadState.HANDSHAKEN)
                    throw wrongReadStateUpdate(oldReadState, newReadState);
                readState = newReadState;
                break;
            }
            case HANDSHAKEN:
            {
                switch (newReadState)
                {
                    case IDLE:
                    {
                        if (BufferUtil.hasContent(netInput))
                            throw wrongReadStateUpdate(oldReadState, newReadState);
                        readState = newReadState;
                        break;
                    }
                    case UNDERFLOW:
                    {
                        if (!BufferUtil.hasContent(netInput))
                            throw wrongReadStateUpdate(oldReadState, newReadState);
                        readState = newReadState;
                        break;
                    }
                    case DECRYPTED:
                    {
                        if (!BufferUtil.hasContent(appInput))
                            throw wrongReadStateUpdate(oldReadState, newReadState);
                        readState = newReadState;
                        break;
                    }
                    case CLOSED:
                    {
                        if (BufferUtil.hasContent(appInput))
                            throw wrongReadStateUpdate(oldReadState, newReadState);
                        readState = newReadState;
                        break;
                    }
                    default:
                    {
                        throw wrongReadStateUpdate(oldReadState, newReadState);
                    }
                }
            }
            case IDLE:
            {
                switch (newReadState)
                {
                    case UNDERFLOW:
                    {
                        if (!BufferUtil.hasContent(netInput))
                            throw wrongReadStateUpdate(oldReadState, newReadState);
                        readState = newReadState;
                        break;
                    }
                    case DECRYPTED:
                    {
                        if (!BufferUtil.hasContent(appInput))
                            throw wrongReadStateUpdate(oldReadState, newReadState);
                        readState = newReadState;
                        break;
                    }
                    case CLOSED:
                    {
                        if (BufferUtil.hasContent(appInput))
                            throw wrongReadStateUpdate(oldReadState, newReadState);
                        readState = newReadState;
                        break;
                    }
                    default:
                    {
                        throw wrongReadStateUpdate(oldReadState, newReadState);
                    }
                }
            }
            case DECRYPTED:
            {
                switch (newReadState)
                {
                    case IDLE:
                    {
                        if (BufferUtil.hasContent(netInput))
                            throw wrongReadStateUpdate(oldReadState, newReadState);
                        readState = newReadState;
                        break;
                    }
                    case UNDERFLOW:
                    {
                        if (!BufferUtil.hasContent(netInput))
                            throw wrongReadStateUpdate(oldReadState, newReadState);
                        readState = newReadState;
                        break;
                    }
                    case CLOSED:
                    {
                        if (BufferUtil.hasContent(appInput))
                            throw wrongReadStateUpdate(oldReadState, newReadState);
                        readState = newReadState;
                        break;
                    }
                    default:
                    {
                        throw wrongReadStateUpdate(oldReadState, newReadState);
                    }
                }
            }
            default:
            {
                throw wrongReadStateUpdate(oldReadState, newReadState);
            }
        }
    }

    private IllegalStateException wrongReadStateUpdate(ReadState oldReadState, ReadState newReadState)
    {
        String message = String.format("Invalid read state update: %s => %s", oldReadState, newReadState);
        return new IllegalStateException(message);
    }

    @Override
    public void onReadable()
    {
        if (appInput != null)
            throw new IllegalStateException();

        switch (readState)
        {
            case HANDSHAKING:
            case IDLE:
            {
                if (netInput != null)
                    throw new IllegalStateException();
                netInput = byteBufferPool.acquire(sslEngine.getSession().getPacketBufferSize(), direct);
                appInput = byteBufferPool.acquire(sslEngine.getSession().getApplicationBufferSize(), false);
                break;
            }
            case UNDERFLOW:
            {
                if (netInput == null)
                    throw new IllegalStateException();
                BufferUtil.compact(netInput);
                break;
            }
            default:
            {
                throw new IllegalStateException("Unexpected read state " + readState);
            }
        }

        AsyncEndPoint endPoint = getEndPoint();
        try
        {
            while (true) // TODO: writes can close the connection, check that also ?
            {
                BufferUtil.compact(netInput);
                int filled = endPoint.fill(netInput);
                if (filled == 0)
                {
                    scheduleOnReadable();
                    break;
                }
                else if (filled < 0)
                {
                    updateReadState(ReadState.CLOSED);
                    sslEngine.closeInbound();
                    break;
                }
                else if (filled > 0)
                {
                    boolean readMore = decrypt();
                    if (!readMore)
                        break;
                }
            }
        }
        catch (IOException x)
        {
            endPoint.close();
        }
    }

    private boolean decrypt() throws SSLException
    {
        while (true)
        {
            updateReadState(sslMachine.decrypt(netInput, appInput));
            switch (readState)
            {
                case UNDERFLOW:
                {
                    return true;
                }
                case HANDSHAKEN:
                {
                    getAppEndPoint().getAsyncConnection().onOpen();
                    if (!netInput.hasRemaining())
                    {
                        updateReadState(ReadState.IDLE);
                        return true;
                    }
                    break;
                }
                case DECRYPTED:
                {
                    appReader.completed(readContext);
                    return false;
                }
                case CLOSED:
                {
                    appReader.completed(readContext);
                    return false;
                }
                default:
                {
                    throw new IllegalStateException("Unexpected read state " + readState);
                }
            }
        }
    }

    public void setAllowRenegotiate(boolean allowRenegotiate)
    {
        // TODO
    }

    public class ApplicationEndPoint extends AbstractEndPoint implements AsyncEndPoint
    {
        private final AtomicBoolean writing = new AtomicBoolean();
        private boolean oshut;
        private Object context;
        private Callback callback;
        private ByteBuffer[] buffers;
        private AsyncConnection connection;

        public ApplicationEndPoint()
        {
            super(getEndPoint().getLocalAddress(), getEndPoint().getRemoteAddress());
        }

        @Override
        public <C> void readable(C context, Callback<C> callback) throws IllegalStateException
        {
            if (appReader != null)
                throw new ReadPendingException();

            switch (readState)
            {
                case IDLE:
                {
                    if (BufferUtil.hasContent(netInput))
                        throw new IllegalStateException();
                    appReader = callback;
                    readContext = context;
                    scheduleOnReadable();
                    break;
                }
                case UNDERFLOW:
                {
                    if (!BufferUtil.hasContent(netInput))
                        throw new IllegalStateException();
                    appReader = callback;
                    readContext = context;
                    scheduleOnReadable();
                    break;
                }
                case DECRYPTED:
                {
                    if (!BufferUtil.hasContent(appInput))
                        throw new IllegalStateException();
                    callback.completed(context);
                    break;
                }
                case CLOSED:
                {
                    if (BufferUtil.hasContent(appInput))
                        throw new IllegalStateException();
                    callback.completed(context);
                    break;
                }
                default:
                {
                    throw new IllegalStateException("Unexpected read state " + readState);
                }
            }
        }

        @Override
        public int fill(ByteBuffer buffer) throws IOException
        {
            switch (readState)
            {
                case IDLE:
                case UNDERFLOW:
                {
                    return 0;
                }
                case DECRYPTED:
                {
                    if (!BufferUtil.hasContent(appInput))
                        throw new IllegalStateException();

                    int filled = BufferUtil.append(appInput, buffer);
                    if (!BufferUtil.hasContent(appInput))
                    {
                        byteBufferPool.release(appInput);
                        appInput = null;
                        updateReadState(BufferUtil.hasContent(netInput) ? ReadState.UNDERFLOW :
                                sslMachine.isRemoteClosed() ? ReadState.CLOSED : ReadState.IDLE);
                    }
                    return filled;
                }
                case CLOSED:
                {
                    return -1;
                }
                default:
                {
                    throw new IllegalStateException("Unexpected read state " + readState);
                }
            }
        }

        @Override
        public void shutdownOutput()
        {
            oshut = true;
            sslMachine.close();
        }

        @Override
        public boolean isOutputShutdown()
        {
            return oshut;
        }

        @Override
        public void close()
        {
            getEndPoint().close();
        }

        @Override
        public boolean isOpen()
        {
            return getEndPoint().isOpen();
        }

        @Override
        public Object getTransport()
        {
            return getEndPoint();
        }

        @Override
        public boolean isInputShutdown()
        {
            return sslMachine.isRemoteClosed();
        }

        @Override
        public int flush(ByteBuffer... appOutputs) throws IOException
        {
            switch (writeState)
            {
                case HANDSHAKING:
                {
                    if (netOutput != null)
                        throw new IllegalStateException();
                    netOutput = byteBufferPool.acquire(sslEngine.getSession().getPacketBufferSize(), direct);
                    break;
                }
                default:
                {
                    throw new IllegalStateException("Unexpected write state " + readState);
                }
            }

            ByteBuffer appOutput = appOutputs[0];
            if (!appOutput.hasRemaining() && appOutputs.length > 1)
            {
                for (int i = 1; i < appOutputs.length; ++i)
                {
                    if (appOutputs[i].hasRemaining())
                    {
                        appOutput = appOutputs[i];
                        break;
                    }
                }
            }

            int remaining = appOutput.remaining();
            sslMachine.encrypt(appOutput, netOutput);
            int result = remaining - appOutput.remaining();

            getEndPoint().write(null, new Callback<Object>()
            {
                @Override
                public void completed(Object context)
                {
                    completeWrite();
                }

                @Override
                public void failed(Object context, Throwable x)
                {
                    // TODO
                }
            }, netOutput);

            return result;
        }

        @Override
        public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws IllegalStateException
        {
            if (!writing.compareAndSet(false, true))
                throw new WritePendingException();

            boolean writePending = false;
            try
            {
                flush(buffers);

                for (ByteBuffer buffer : buffers)
                {
                    if (buffer.hasRemaining())
                    {
                        this.context = context;
                        this.callback = callback;
                        this.buffers = buffers;
                        writePending = true;
                        return;
                    }
                }

                callback.completed(context);
            }
            catch (IOException x)
            {
                callback.failed(context, x);
            }
            finally
            {
                writing.set(writePending);
            }
        }

        private void completeWrite()
        {
            if (buffers == null)
                return;

            try
            {
                flush(buffers);

                for (ByteBuffer buffer : buffers)
                {
                    if (buffer.hasRemaining())
                        return;
                }

                callback.completed(context);
            }
            catch (IOException x)
            {
                callback.failed(context, x);
            }
            finally
            {
                context = null;
                callback = null;
                buffers = null;
            }
        }

        @Override
        public AsyncConnection getAsyncConnection()
        {
            return connection;
        }

        @Override
        public void setAsyncConnection(AsyncConnection connection)
        {
            this.connection = connection;
        }
    }

    private class ConnectionSSLMachine extends SSLMachine
    {
        private ConnectionSSLMachine(SSLEngine engine)
        {
            super(engine);
        }

        @Override
        protected void writeForDecrypt(ByteBuffer appOutput)
        {
            AsyncEndPoint endPoint = getAppEndPoint();
            try
            {
                endPoint.flush(appOutput);
            }
            catch (IOException x)
            {
                endPoint.close();
            }
        }
    }
}
