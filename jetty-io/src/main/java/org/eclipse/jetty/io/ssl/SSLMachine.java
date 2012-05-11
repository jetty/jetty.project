package org.eclipse.jetty.io.ssl;

import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class SSLMachine
{
    private static final Logger logger = Log.getLogger(SslConnection.class);
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);
    private final Object wrapLock = new Object();
    private final SSLEngine engine;
    private boolean handshaken;
    private boolean remoteClosed;

    public SSLMachine(SSLEngine engine)
    {
        this.engine = engine;
    }

    public ReadState decrypt(ByteBuffer netInput, ByteBuffer appInput) throws SSLException
    {
        while (true)
        {
            SSLEngineResult.HandshakeStatus handshakeStatus = engine.getHandshakeStatus();
            logger.debug("Reading from {}, handshake status: {}", netInput, handshakeStatus);
            switch (handshakeStatus)
            {
                case NEED_UNWRAP:
                {
                    ReadState result = unwrap(netInput, appInput);
                    if (result == null)
                        break;
                    return result;
                }
                case NEED_TASK:
                {
                    executeTasks();
                    break;
                }
                case NEED_WRAP:
                {
                    writeForDecrypt(EMPTY_BUFFER);
                    break;
                }
                case NOT_HANDSHAKING:
                {
                    ReadState result = unwrap(netInput, appInput);
                    if (result == null)
                        break;
                    return result;
                }
                default:
                    throw new IllegalStateException();
            }
        }
    }

    public WriteState encrypt(ByteBuffer appOutput, ByteBuffer netOutput) throws SSLException
    {
        return wrap(appOutput, netOutput);
    }

    protected abstract void writeForDecrypt(ByteBuffer appOutput);

    private ReadState unwrap(ByteBuffer netInput, ByteBuffer appInput) throws SSLException
    {
        boolean decrypted = false;
        while (netInput.hasRemaining())
        {
            logger.debug("Decrypting from {} to {}", netInput, appInput);
            SSLEngineResult result = unwrap(engine, netInput, appInput);
            logger.debug("Decrypted from {} to {}, result {}", netInput, appInput, result);
            switch (result.getStatus())
            {
                case OK:
                {
                    SSLEngineResult.HandshakeStatus handshakeStatus = result.getHandshakeStatus();
                    if (handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED)
                    {
                        if (engine.getUseClientMode())
                        {
                            logger.debug("Handshake finished (client), new SSL session");
                            handshaken = true;
                            return ReadState.HANDSHAKEN;
                        }
                        else
                        {
                            if (handshaken)
                            {
                                logger.debug("Rehandshake finished (server)");
                            }
                            else
                            {
                                logger.debug("Handshake finished (server), cached SSL session");
                                handshaken = true;
                                return ReadState.HANDSHAKEN;
                            }
                        }
                    }

                    if (result.bytesProduced() > 0)
                    {
                        decrypted = true;
                        continue;
                    }

                    if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP)
                        continue;

                    if (handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED ||
                            handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
                        break;

                    return null;
                }
                case BUFFER_UNDERFLOW:
                {
                    return decrypted ? ReadState.DECRYPTED : ReadState.UNDERFLOW;
                }
                case CLOSED:
                {
                    // We have read the SSL close message and updated the SSLEngine
                    logger.debug("Close alert received from remote peer");
                    remoteClosed = true;
                    return decrypted ? ReadState.DECRYPTED : ReadState.CLOSED;
                }
                default:
                    throw new IllegalStateException();
            }
        }

        SSLEngineResult.HandshakeStatus handshakeStatus = engine.getHandshakeStatus();
        if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP ||
                engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
            return ReadState.UNDERFLOW;

        return null;
    }

    private SSLEngineResult unwrap(SSLEngine engine, ByteBuffer netInput, ByteBuffer appInput) throws SSLException
    {
        int position = BufferUtil.flipToFill(appInput);
        try
        {
            return engine.unwrap(netInput, appInput);
        }
        finally
        {
            BufferUtil.flipToFlush(appInput, position);
        }
    }

    private void executeTasks()
    {
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null)
        {
            task.run();
            logger.debug("Executed task: {}", task);
        }
    }

    private WriteState wrap(ByteBuffer appOutput, ByteBuffer netOutput) throws SSLException
    {
        while (true)
        {
            // Locking is important because application code may call handshake() (to rehandshake)
            // followed by encrypt(). In this case, the handshake() call may need to wrap, then
            // unwrap and then wrap again to perform the handshake, and the second wrap may be
            // concurrent with the wrap triggered by encrypt(), which would corrupt SSLEngine.
            // It is also important that wrap and write are atomic, because SSL packets needs to
            // be ordered (and therefore the packet created first must be written before the packet
            // created second).
            SSLEngineResult result;
            synchronized (wrapLock)
            {
                logger.debug("Encrypting from {} to {}", appOutput, netOutput);
                result = wrap(engine, appOutput, netOutput);
                logger.debug("Encrypted from {} to {}, result: {}", appOutput, netOutput, result);

/*
                if (result.bytesProduced() > 0)
                {
                    try
                    {
                        netOutput.flip();
                        write(netOutput);
                        netOutput.clear();
                    }
                    catch (RuntimeIOException x)
                    {
                        // If we try to write the SSL close message but we cannot
                        // because the other peer has already closed the connection,
                        // then ignore the exception and continue
                        if (result.getStatus() != SSLEngineResult.Status.CLOSED)
                            throw x;
                    }
                }
*/
            }

            if (result.getStatus() == SSLEngineResult.Status.CLOSED)
                return WriteState.CLOSED;

            SSLEngineResult.HandshakeStatus handshakeStatus = result.getHandshakeStatus();
            if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP)
                continue;

            if (handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED)
            {
                if (engine.getUseClientMode())
                {
                    if (handshaken)
                    {
                        logger.debug("Rehandshake finished (client)");
                    }
                    else
                    {
                        logger.debug("Handshake finished (client), cached SSL session");
                        handshaken = true;
                        return WriteState.HANDSHAKEN;
                    }
                }
                else
                {
                    logger.debug("Handshake finished (server), new SSL session");
                    assert !appOutput.hasRemaining();
                    handshaken = true;
                    return WriteState.HANDSHAKEN;
                }
            }

            if (!appOutput.hasRemaining())
                return null;
        }
    }

    private SSLEngineResult wrap(SSLEngine engine, ByteBuffer appOutput, ByteBuffer netOutput) throws SSLException
    {
        int position = BufferUtil.flipToFill(netOutput);
        try
        {
            return engine.wrap(appOutput, netOutput);
        }
        finally
        {
            BufferUtil.flipToFlush(netOutput, position);
        }
    }

    public boolean isRemoteClosed()
    {
        return remoteClosed;
    }

    public void close()
    {
    }
}
