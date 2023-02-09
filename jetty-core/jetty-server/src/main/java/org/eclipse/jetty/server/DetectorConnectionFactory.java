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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ConnectionFactory} combining multiple {@link Detecting} instances that will upgrade to
 * the first one recognizing the bytes in the buffer.
 */
public class DetectorConnectionFactory extends AbstractConnectionFactory implements ConnectionFactory.Detecting
{
    private static final Logger LOG = LoggerFactory.getLogger(DetectorConnectionFactory.class);

    private final List<Detecting> _detectingConnectionFactories;

    /**
     * <p>When the first bytes are not recognized by the {@code detectingConnectionFactories}, the default behavior is to
     * upgrade to the protocol returned by {@link #findNextProtocol(Connector)}.</p>
     * @param detectingConnectionFactories the {@link Detecting} instances.
     */
    public DetectorConnectionFactory(Detecting... detectingConnectionFactories)
    {
        super(toProtocolString(detectingConnectionFactories));
        _detectingConnectionFactories = Arrays.asList(detectingConnectionFactories);
        for (Detecting detectingConnectionFactory : detectingConnectionFactories)
        {
            addBean(detectingConnectionFactory);
        }
    }

    private static String toProtocolString(Detecting... detectingConnectionFactories)
    {
        if (detectingConnectionFactories.length == 0)
            throw new IllegalArgumentException("At least one detecting instance is required");

        // remove protocol duplicates while keeping their ordering -> use LinkedHashSet
        LinkedHashSet<String> protocols = Arrays.stream(detectingConnectionFactories).map(ConnectionFactory::getProtocol).collect(Collectors.toCollection(LinkedHashSet::new));

        String protocol = protocols.stream().collect(Collectors.joining("|", "[", "]"));
        if (LOG.isDebugEnabled())
            LOG.debug("Detector generated protocol name : {}", protocol);
        return protocol;
    }

    /**
     * Performs a detection using multiple {@link Detecting} instances and returns the aggregated outcome.
     * @param buffer the buffer to perform a detection against.
     * @return A {@link Detection} value with the detection outcome of the {@code detectingConnectionFactories}.
     */
    @Override
    public Detection detect(ByteBuffer buffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Detector {} detecting from buffer {} using {}", getProtocol(), BufferUtil.toHexString(buffer), _detectingConnectionFactories);
        boolean needMoreBytes = true;
        for (Detecting detectingConnectionFactory : _detectingConnectionFactories)
        {
            Detection detection = detectingConnectionFactory.detect(buffer);
            if (detection == Detection.RECOGNIZED)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Detector {} recognized bytes using {}", getProtocol(), detection);
                return Detection.RECOGNIZED;
            }
            needMoreBytes &= detection == Detection.NEED_MORE_BYTES;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Detector {} {}", getProtocol(), (needMoreBytes ? "requires more bytes" : "failed to recognize bytes"));
        return needMoreBytes ? Detection.NEED_MORE_BYTES : Detection.NOT_RECOGNIZED;
    }

    /**
     * Utility method that performs an upgrade to the specified connection factory, disposing of the given resources when needed.
     * @param connectionFactory the connection factory to upgrade to.
     * @param connector the connector.
     * @param endPoint the endpoint.
     */
    protected static void upgradeToConnectionFactory(ConnectionFactory connectionFactory, Connector connector, EndPoint endPoint) throws IllegalStateException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Upgrading to connection factory {}", connectionFactory);
        if (connectionFactory == null)
            throw new IllegalStateException("Cannot upgrade: connection factory must not be null for " + endPoint);
        Connection nextConnection = connectionFactory.newConnection(connector, endPoint);
        if (!(nextConnection instanceof Connection.UpgradeTo))
            throw new IllegalStateException("Cannot upgrade: " + nextConnection + " does not implement " + Connection.UpgradeTo.class.getName() + " for " + endPoint);
        endPoint.upgrade(nextConnection);
        if (LOG.isDebugEnabled())
            LOG.debug("Upgraded to connection factory {} and released buffer", connectionFactory);
    }

    /**
     * <p>Callback method called when detection was unsuccessful.
     * This implementation upgrades to the protocol returned by {@link #findNextProtocol(Connector)}.</p>
     * @param connector the connector.
     * @param endPoint the endpoint.
     * @param buffer the buffer.
     */
    protected void nextProtocol(Connector connector, EndPoint endPoint, ByteBuffer buffer) throws IllegalStateException
    {
        String nextProtocol = findNextProtocol(connector);
        if (LOG.isDebugEnabled())
            LOG.debug("Detector {} detection unsuccessful, found '{}' as the next protocol to upgrade to", getProtocol(), nextProtocol);
        if (nextProtocol == null)
            throw new IllegalStateException("Cannot find protocol following '" + getProtocol() + "' in connector's protocol list " + connector.getProtocols() + " for " + endPoint);
        upgradeToConnectionFactory(connector.getConnectionFactory(nextProtocol), connector, endPoint);
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        return configure(new DetectorConnection(endPoint, connector), connector, endPoint);
    }

    private class DetectorConnection extends AbstractConnection implements Connection.UpgradeFrom, Connection.UpgradeTo
    {
        private final Connector _connector;
        private final RetainableByteBuffer _buffer;

        private DetectorConnection(EndPoint endp, Connector connector)
        {
            super(endp, connector.getExecutor());
            _connector = connector;
            _buffer = connector.getByteBufferPool().acquire(getInputBufferSize(), true);
        }

        @Override
        public void onUpgradeTo(ByteBuffer byteBuffer)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Detector {} adopting {}", getProtocol(), BufferUtil.toDetailString(byteBuffer));
            // Throws if the ByteBuffer to adopt is too big, but it is handled.
            BufferUtil.append(_buffer.getByteBuffer(), byteBuffer);
        }

        @Override
        public ByteBuffer onUpgradeFrom()
        {
            if (_buffer.hasRemaining())
            {
                ByteBuffer unconsumed = ByteBuffer.allocateDirect(_buffer.remaining());
                unconsumed.put(_buffer.getByteBuffer());
                unconsumed.flip();
                if (LOG.isDebugEnabled())
                    LOG.debug("Detector {} abandoning {}", getProtocol(), BufferUtil.toDetailString(unconsumed));
                return unconsumed;
            }
            return null;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            try
            {
                boolean upgraded = detectAndUpgrade();
                if (upgraded)
                    _buffer.release();
                else
                    fillInterested();
            }
            catch (Throwable x)
            {
                releaseAndClose(x);
            }
        }

        @Override
        public void onFillable()
        {
            try
            {
                ByteBuffer byteBuffer = _buffer.getByteBuffer();
                while (BufferUtil.space(byteBuffer) > 0)
                {
                    // Read data
                    int fill = getEndPoint().fill(byteBuffer);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Detector {} filled buffer with {} bytes", getProtocol(), fill);
                    if (fill < 0)
                    {
                        _buffer.release();
                        getEndPoint().shutdownOutput();
                        return;
                    }
                    if (fill == 0)
                    {
                        fillInterested();
                        return;
                    }

                    if (detectAndUpgrade())
                    {
                        _buffer.release();
                        return;
                    }
                }

                // all Detecting instances want more bytes than this buffer can store
                LOG.warn("Detector {} failed to detect upgrade target on {} for {}", getProtocol(), _detectingConnectionFactories, getEndPoint());
                releaseAndClose(new IOException("Detector %s buffer overflow %d".formatted(getProtocol(), _buffer.capacity())));
            }
            catch (Throwable x)
            {
                LOG.warn("Detector {} error for {}", getProtocol(), getEndPoint(), x);
                releaseAndClose(x);
            }
        }

        /**
         * @return true when upgrade was performed, false otherwise.
         */
        private boolean detectAndUpgrade()
        {
            if (!_buffer.hasRemaining())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Detector {} skipping detection on an empty buffer", getProtocol());
                return false;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Detector {} performing detection with {} bytes", getProtocol(), _buffer.remaining());
            boolean notRecognized = true;
            for (Detecting detectingConnectionFactory : _detectingConnectionFactories)
            {
                Detection detection = detectingConnectionFactory.detect(_buffer.getByteBuffer());
                if (LOG.isDebugEnabled())
                    LOG.debug("Detector {} performed detection from {} with {} which returned {}", getProtocol(), _buffer, detectingConnectionFactory, detection);
                if (detection == Detection.RECOGNIZED)
                {
                    try
                    {
                        // This DetectingConnectionFactory recognized those bytes -> upgrade to the next one.
                        Connection nextConnection = detectingConnectionFactory.newConnection(_connector, getEndPoint());
                        if (!(nextConnection instanceof UpgradeTo))
                            throw new IllegalStateException("Cannot upgrade: " + nextConnection + " does not implement " + UpgradeTo.class.getName());
                        getEndPoint().upgrade(nextConnection);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Detector {} upgraded to {}", getProtocol(), nextConnection);
                        return true;
                    }
                    catch (DetectionFailureException x)
                    {
                        // It's just bubbling up from a nested Detector, so it's already handled, just rethrow it.
                        if (LOG.isDebugEnabled())
                            LOG.debug("Detector {} failed to upgrade, rethrowing", getProtocol(), x);
                        throw x;
                    }
                    catch (Throwable x)
                    {
                        // Two reasons that can make us end up here:
                        // 1) detectingConnectionFactory.newConnection() failed, probably because it cannot find the next protocol
                        // 2) nextConnection is not instanceof UpgradeTo, rethrow as DetectionFailureException
                        if (LOG.isDebugEnabled())
                            LOG.debug("Detector {} failed to upgrade", getProtocol());
                        throw new DetectionFailureException(x);
                    }
                }
                notRecognized &= detection == Detection.NOT_RECOGNIZED;
            }

            if (notRecognized)
            {
                // No DetectingConnectionFactory recognized those bytes -> call unsuccessful detection callback.
                if (LOG.isDebugEnabled())
                    LOG.debug("Detector {} failed to detect a known protocol, falling back to nextProtocol()", getProtocol());
                nextProtocol(_connector, getEndPoint(), _buffer.getByteBuffer());
                if (LOG.isDebugEnabled())
                    LOG.debug("Detector {} call to nextProtocol() succeeded, assuming upgrade performed", getProtocol());
                return true;
            }

            return false;
        }

        private void releaseAndClose(Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Detector {} releasing buffer and closing", getProtocol());
            _buffer.release();
            getEndPoint().close(failure);
        }
    }

    private static class DetectionFailureException extends RuntimeException
    {
        public DetectionFailureException(Throwable cause)
        {
            super(cause);
        }
    }
}
