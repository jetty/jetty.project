//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.nio.ByteBuffer;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A {@link Connection} that handles the HTTP protocol.</p>
 */
public class HttpConnection extends AbstractConnection
{
    public static final String UPGRADE_CONNECTION_ATTRIBUTE = "org.eclispe.jetty.server.HttpConnection.UPGRADE";
    private static final Logger LOG = Log.getLogger(HttpConnection.class);
    private static final ThreadLocal<HttpConnection> __currentConnection = new ThreadLocal<>();

    private final Server _server;
    private final HttpConfiguration _configuration;
    private final Connector _connector;
    private final HttpParser _parser;
    private final HttpGenerator _generator;
    private final HttpChannel _channel;
    private final ByteBufferPool _bufferPool;

    private ByteBuffer _requestBuffer = null;
    private int _headerBytes;


    public static HttpConnection getCurrentConnection()
    {
        return __currentConnection.get();
    }

    protected static void setCurrentConnection(HttpConnection connection)
    {
        __currentConnection.set(connection);
    }

    public HttpConnection(HttpConfiguration config, Connector connector, EndPoint endPoint)
    {
        super(endPoint, connector.getExecutor());

        _configuration = config;
        _connector = connector;
        _bufferPool = _connector.getByteBufferPool();
        _server = connector.getServer();
        _channel = new HttpChannel(connector, config, endPoint, new HttpTransportOverHttp(_bufferPool, _configuration, endPoint));
        _parser = new HttpParser(_channel);
        _generator = new HttpGenerator();
        _generator.setSendServerVersion(_server.getSendServerVersion());

        LOG.debug("New HTTP Connection {}", this);
    }

    public Server getServer()
    {
        return _server;
    }

    public Connector getConnector()
    {
        return _connector;
    }

    public HttpChannel getHttpChannel()
    {
        return _channel;
    }

    public void reset()
    {
        if (_generator.isPersistent())
            _parser.reset();
        else
            _parser.close();

        _generator.reset();
        _channel.reset();
        releaseRequestBuffer();
    }

    @Override
    public String toString()
    {
        return String.format("%s,g=%s,p=%s",
                super.toString(),
                _generator,
                _parser);
    }

    private void releaseRequestBuffer()
    {
        if (_requestBuffer != null && !_requestBuffer.hasRemaining())
        {
            _bufferPool.release(_requestBuffer);
            _requestBuffer = null;
        }
    }

    /**
     * <p>Parses and handles HTTP messages.</p>
     * <p>This method is called when this {@link Connection} is ready to read bytes from the {@link EndPoint}.
     * However, it can also be called if there is unconsumed data in the _requestBuffer, as a result of
     * resuming a suspended request when there is a pipelined request already read into the buffer.</p>
     * <p>This method fills bytes and parses them until either: EOF is filled; 0 bytes are filled;
     * the HttpChannel finishes handling; or the connection has changed.</p>
     */
    @Override
    public void onFillable()
    {
        LOG.debug("{} onReadable {}", this, _channel.getState());

        setCurrentConnection(this);
        try
        {
            while (true)
            {
                // Fill the request buffer with data only if it is totally empty.
                if (BufferUtil.isEmpty(_requestBuffer))
                {
                    if (_requestBuffer == null)
                        _requestBuffer = _bufferPool.acquire(_configuration.getRequestHeaderSize(), false);

                    int filled = getEndPoint().fill(_requestBuffer);

                    LOG.debug("{} filled {}", this, filled);

                    // If we failed to fill
                    if (filled == 0)
                    {
                        // Somebody wanted to read, we didn't so schedule another attempt
                        releaseRequestBuffer();
                        fillInterested();
                        return;
                    }
                    else if (filled < 0)
                    {
                        _parser.inputShutdown();
                        // We were only filling if fully consumed, so if we have
                        // read -1 then we have nothing to parse and thus nothing that
                        // will generate a response.  If we had a suspended request pending
                        // a response or a request waiting in the buffer, we would not be here.
                        if (getEndPoint().isOutputShutdown())
                            getEndPoint().close();
                        else
                            getEndPoint().shutdownOutput();
                        // buffer must be empty and the channel must be idle, so we can release.
                        releaseRequestBuffer();
                        return;
                    }
                    else
                    {
                        _headerBytes += filled;
                    }
                }

                // Parse the buffer
                if (_parser.parseNext(_requestBuffer))
                {
                    // reset header count
                    _headerBytes = 0;

                    // For most requests, there will not be a body, so we can try to recycle the buffer now
                    releaseRequestBuffer();

                    if (!_channel.getRequest().isPersistent())
                        _generator.setPersistent(false);

                    // The parser returned true, which indicates the channel is ready to handle a request.
                    // Call the channel and this will either handle the request/response to completion OR,
                    // if the request suspends, the request/response will be incomplete so the outer loop will exit.
                    boolean complete = _channel.handle(); // TODO: should we perform special processing if we are complete ?

                    // Handle connection upgrades
                    if (_channel.getResponse().getStatus() == HttpStatus.SWITCHING_PROTOCOLS_101)
                    {
                        Connection connection = (Connection)_channel.getRequest().getAttribute(UPGRADE_CONNECTION_ATTRIBUTE);
                        if (connection != null)
                        {
                            LOG.debug("Upgrade from {} to {}", this, connection);
                            getEndPoint().setConnection(connection);
                        }
                    }

                    HttpConnection.this.reset();

                    // Is this thread dispatched from a resume ?
                    if (getCurrentConnection() != HttpConnection.this)
                    {
                        if (_parser.isStart())
                        {
                            // it wants to eat more
                            if (_requestBuffer == null)
                            {
                                fillInterested();
                            }
                            else if (getConnector().isStarted())
                            {
                                LOG.debug("{} pipelined", this);

                                try
                                {
                                    // TODO: avoid object creation
                                    getExecutor().execute(new Runnable()
                                    {
                                        @Override
                                        public void run()
                                        {
                                            onFillable();
                                        }
                                    });
                                }
                                catch (RejectedExecutionException e)
                                {
                                    if (getConnector().isStarted())
                                        LOG.warn(e);
                                    else
                                        LOG.ignore(e);
                                    getEndPoint().close();
                                }
                            }
                            else
                            {
                                getEndPoint().close();
                            }
                        }

                        if (_parser.isClosed() && !getEndPoint().isOutputShutdown())
                        {
                            // TODO This is a catch all indicating some protocol handling failure
                            // Currently needed for requests saying they are HTTP/2.0.
                            // This should be removed once better error handling is in place
                            LOG.warn("Endpoint output not shutdown when seeking EOF");
                            getEndPoint().shutdownOutput();
                        }
                    }

                    // make sure that an oshut connection is driven towards close
                    // TODO this is a little ugly
                    if (getEndPoint().isOpen() && getEndPoint().isOutputShutdown())
                    {
                        fillInterested();
                    }

                    // return if the connection has been changed
                    if (getEndPoint().getConnection() != this)
                        return;
                }
                else if (_headerBytes >= _configuration.getRequestHeaderSize())
                {
                    _parser.reset();
                    _parser.close();
                    _channel.getResponse().sendError(Response.SC_REQUEST_ENTITY_TOO_LARGE, null, null);
                    // TODO: close the connection !
                }
            }
        }
        catch (IOException e)
        {
            if (_parser.isIdle())
                LOG.debug(e);
            else
                LOG.warn(this.toString(), e);
            getEndPoint().close();
        }
        catch (Exception e)
        {
            LOG.warn(this.toString(), e);
            getEndPoint().close();
        }
        finally
        {
            setCurrentConnection(null);
        }
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
    }
}
