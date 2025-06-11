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

package org.eclipse.jetty.client.transport.internal;

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpReceiver;
import org.eclipse.jetty.client.transport.HttpResponse;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpReceiverOverHTTP extends HttpReceiver implements HttpParser.ResponseHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpReceiverOverHTTP.class);

    private final Runnable receiveNext = this::receiveNext;
    private final LongAdder inMessages = new LongAdder();
    private final HttpParser parser;
    private final ByteBufferPool byteBufferPool;
    private RetainableByteBuffer networkBuffer;
    private State state = State.STATUS;
    private boolean unsolicited;
    private int status;
    private String method;
    private Content.Chunk chunk;
    private boolean shutdown;
    private boolean disposed;

    public HttpReceiverOverHTTP(HttpChannelOverHTTP channel)
    {
        super(channel);
        HttpClient httpClient = channel.getHttpDestination().getHttpClient();
        parser = new HttpParser(this, httpClient.getMaxResponseHeadersSize(), httpClient.getHttpCompliance());
        HttpClientTransport transport = httpClient.getTransport();
        if (transport instanceof HttpClientTransportOverHTTP httpTransport)
        {
            parser.setHeaderCacheSize(httpTransport.getHeaderCacheSize());
            parser.setHeaderCacheCaseSensitive(httpTransport.isHeaderCacheCaseSensitive());
        }
        byteBufferPool = httpClient.getByteBufferPool();
    }

    void receive()
    {
        if (!hasContent())
        {
            boolean setFillInterest = parseAndFill(true);
            if (!hasContent() && setFillInterest)
                fillInterested();
        }
        else
        {
            HttpExchange exchange = getHttpExchange();
            if (exchange != null)
                responseContentAvailable(exchange);
        }
    }

    @Override
    protected void onInterim()
    {
        receive();
    }

    @Override
    protected void reset()
    {
        super.reset();
        parser.reset();
        if (chunk != null)
            chunk.release();
        chunk = null;
    }

    @Override
    protected void dispose()
    {
        super.dispose();
        parser.close();
        if (chunk != null)
            chunk.release();
        chunk = null;
        disposed = true;
    }

    @Override
    public Content.Chunk read(boolean fillInterestIfNeeded)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Reading, fillInterestIfNeeded={} in {}", fillInterestIfNeeded, this);

        Content.Chunk chunk = consumeChunk();
        if (chunk != null)
            return chunk;
        boolean needFillInterest = parseAndFill(false);
        if (LOG.isDebugEnabled())
            LOG.debug("ParseAndFill needFillInterest {} in {}", needFillInterest, this);
        chunk = consumeChunk();
        if (state == State.COMPLETE)
            responseSuccess(getHttpExchange(), receiveNext);
        if (chunk != null)
            return chunk;
        if (needFillInterest && fillInterestIfNeeded)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Read null, filled 0, fill interest requested -> call fillInterested in {}", this);
            fillInterested();
        }
        return null;
    }

    private Content.Chunk consumeChunk()
    {
        Content.Chunk chunk = this.chunk;
        this.chunk = null;
        if (LOG.isDebugEnabled())
            LOG.debug("Receiver consuming chunk {} in {}", chunk, this);
        return chunk;
    }

    @Override
    public void failAndClose(Throwable failure)
    {
        responseFailure(failure, Promise.from((failed) ->
        {
            if (failed)
                getHttpConnection().close(failure);
        }, x -> getHttpConnection().close(failure)));
    }

    @Override
    public HttpChannelOverHTTP getHttpChannel()
    {
        return (HttpChannelOverHTTP)super.getHttpChannel();
    }

    private HttpConnectionOverHTTP getHttpConnection()
    {
        return getHttpChannel().getHttpConnection();
    }

    protected ByteBuffer getResponseBuffer()
    {
        return networkBuffer == null ? null : networkBuffer.getByteBuffer();
    }

    private void acquireNetworkBuffer()
    {
        networkBuffer = newNetworkBuffer();
        if (LOG.isDebugEnabled())
            LOG.debug("Acquired {} in {}", networkBuffer, this);
    }

    private void reacquireNetworkBuffer()
    {
        RetainableByteBuffer currentBuffer = networkBuffer;
        if (currentBuffer == null)
            throw new IllegalStateException();
        if (currentBuffer.hasRemaining())
            throw new IllegalStateException();

        currentBuffer.release();
        networkBuffer = newNetworkBuffer();
        if (LOG.isDebugEnabled())
            LOG.debug("Reacquired {} <- {} in {}", currentBuffer, networkBuffer, this);
    }

    private RetainableByteBuffer newNetworkBuffer()
    {
        HttpClient client = getHttpDestination().getHttpClient();
        boolean direct = client.isUseInputDirectByteBuffers();
        return byteBufferPool.acquire(client.getResponseBufferSize(), direct);
    }

    private void releaseNetworkBuffer()
    {
        if (networkBuffer == null)
            return;
        networkBuffer.release();
        if (LOG.isDebugEnabled())
            LOG.debug("Released {} in {}", networkBuffer, this);
        networkBuffer = null;
    }

    protected ByteBuffer onUpgradeFrom()
    {
        RetainableByteBuffer networkBuffer = this.networkBuffer;
        if (networkBuffer == null)
            return null;

        ByteBuffer upgradeBuffer = null;
        if (networkBuffer.hasRemaining())
        {
            HttpClient client = getHttpDestination().getHttpClient();
            upgradeBuffer = BufferUtil.allocate(networkBuffer.remaining(), client.isUseInputDirectByteBuffers());
            BufferUtil.clearToFill(upgradeBuffer);
            BufferUtil.put(networkBuffer.getByteBuffer(), upgradeBuffer);
            BufferUtil.flipToFlush(upgradeBuffer, 0);
        }
        releaseNetworkBuffer();
        return upgradeBuffer;
    }

    /**
     * Parses the networkBuffer until the next content is generated or until the buffer is depleted.
     * If this method depletes the buffer, it will always try to re-fill until fill generates 0 byte.
     * @return true if no bytes were filled.
     */
    private boolean parseAndFill(boolean notifyContentAvailable)
    {
        HttpConnectionOverHTTP connection = getHttpConnection();
        EndPoint endPoint = connection.getEndPoint();
        try
        {
            if (networkBuffer == null)
                acquireNetworkBuffer();
            while (true)
            {
                // Always parse even empty buffers to advance the parser.
                boolean stopParsing = parse(notifyContentAvailable);
                if (LOG.isDebugEnabled())
                    LOG.debug("Parsed stop={} in {}", stopParsing, this);
                if (stopParsing)
                {
                    // Return immediately, as this thread may be in a race
                    // with e.g. another thread demanding more content.
                    return false;
                }

                // Connection may be closed in a parser callback.
                if (connection.isClosed() || isShutdown())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Closed/Shutdown {} in {}", connection, this);
                    releaseNetworkBuffer();
                    return false;
                }

                if (networkBuffer.isRetained())
                    reacquireNetworkBuffer();

                // The networkBuffer may have been reacquired.
                assert !networkBuffer.hasRemaining();
                int read = endPoint.fill(networkBuffer.getByteBuffer());
                if (LOG.isDebugEnabled())
                    LOG.debug("Read {} bytes in {} from {} in {}", read, networkBuffer, endPoint, this);

                if (read > 0)
                {
                    connection.addBytesIn(read);
                }
                else if (read == 0)
                {
                    releaseNetworkBuffer();
                    return true;
                }
                else
                {
                    shutdown();
                    // Loop around to parse again to advance the parser,
                    // for example for HTTP/1.0 connection-delimited content.
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Error processing {} in {}", endPoint, this, x);
            releaseNetworkBuffer();
            failAndClose(x);
            return false;
        }
    }

    /**
     * Parses an HTTP response in the receivers buffer.
     *
     * @return true to indicate that parsing should be interrupted (and will be resumed by another thread).
     */
    private boolean parse(boolean notifyContentAvailable)
    {
        // HttpParser is not reentrant, so we cannot invoke the
        // application from the parser event callbacks.
        // However, the mechanism in general (and this method)
        // is reentrant: it notifies the application which may
        // read response content, which reenters here.

        ByteBuffer byteBuffer = networkBuffer.getByteBuffer();
        while (true)
        {
            boolean handle = parser.parseNext(byteBuffer);
            if (LOG.isDebugEnabled())
                LOG.debug("Parse state={} result={} {} {} on {}", state, handle, BufferUtil.toDetailString(byteBuffer), parser, this);
            if (!handle)
                return false;

            HttpExchange exchange = getHttpExchange();
            if (exchange == null)
                throw new IllegalStateException("No exchange");

            switch (state)
            {
                case HEADERS -> responseHeaders(exchange);
                case CONTENT ->
                {
                    if (notifyContentAvailable)
                        responseContentAvailable(exchange);
                }
                case COMPLETE ->
                {
                    boolean isUpgrade = status == HttpStatus.SWITCHING_PROTOCOLS_101;
                    boolean isTunnel = getHttpChannel().isTunnel(method, status);

                    // Connection upgrade, bail out.
                    if (isUpgrade || isTunnel)
                    {
                        responseSuccess(exchange, null);
                        return true;
                    }

                    if (byteBuffer.hasRemaining())
                    {
                        if (HttpStatus.isInterim(status))
                        {
                            // There may be multiple interim responses in
                            // the same network buffer, continue parsing.
                            continue;
                        }
                        else
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Discarding unexpected content after response {}: {} in {}", status, BufferUtil.toDetailString(byteBuffer), this);
                            BufferUtil.clear(byteBuffer);
                        }
                    }

                    // When notifyContentAvailable==false, this method is called from read(boolean),
                    // and the call to responseSuccess() is performed by read().
                    if (notifyContentAvailable)
                        responseSuccess(exchange, receiveNext);

                    // Continue to read from the network.
                    return false;
                }
                default -> throw new IllegalStateException("Invalid state " + state);
            }

            // The application may have aborted the request.
            if (disposed)
            {
                BufferUtil.clear(byteBuffer);
                return false;
            }

            // The application has been invoked,
            // and it is now driving the parsing.
            return true;
        }
    }

    protected void fillInterested()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Registering as fill interested in {}", this);
        getHttpConnection().fillInterested();
    }

    private void shutdown()
    {
        // Mark this receiver as shutdown, so that we can
        // close the connection when the exchange terminates.
        // We cannot close the connection from here because
        // the request may still be in process.
        shutdown = true;

        // Shutting down the parser may invoke messageComplete() or earlyEOF().
        // In case of content delimited by EOF, without a Connection: close
        // header, the connection will be closed at exchange termination
        // thanks to the flag we have set above.
        parser.atEOF();
    }

    protected boolean isShutdown()
    {
        return shutdown;
    }

    @Override
    public void startResponse(HttpVersion version, int status, String reason)
    {
        HttpExchange exchange = getHttpExchange();
        unsolicited = exchange == null;
        if (exchange == null)
            return;

        this.method = exchange.getRequest().getMethod();
        this.status = status;
        parser.setHeadResponse(HttpMethod.HEAD.is(method) || getHttpChannel().isTunnel(method, status));
        exchange.getResponse().version(version).status(status).reason(reason);
        state = State.STATUS;

        responseBegin(exchange);
    }

    @Override
    public void parsedHeader(HttpField field)
    {
        HttpExchange exchange = getHttpExchange();
        unsolicited |= exchange == null;
        if (unsolicited)
            return;

        responseHeader(exchange, field);
    }

    @Override
    public boolean headerComplete()
    {
        HttpExchange exchange = getHttpExchange();
        unsolicited |= exchange == null;
        if (unsolicited)
            return false;

        // Store the EndPoint is case of upgrades, tunnels, etc.
        exchange.getRequest().getConversation().setAttribute(EndPoint.class.getName(), getHttpConnection().getEndPoint());
        getHttpConnection().onResponseHeaders(exchange);
        state = State.HEADERS;
        return true;
    }

    @Override
    public boolean content(ByteBuffer buffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Parser generated content {} in {}", BufferUtil.toDetailString(buffer), this);
        HttpExchange exchange = getHttpExchange();
        unsolicited |= exchange == null;
        if (unsolicited)
            return false;

        if (chunk != null)
            throw new IllegalStateException("Content generated with unconsumed content left");
        if (getHttpConnection().isFillInterested())
            throw new IllegalStateException("Fill interested while parsing for content");

        // Retain the chunk because it is stored for later use.
        networkBuffer.retain();
        chunk = Content.Chunk.asChunk(buffer, false, networkBuffer);
        state = State.CONTENT;
        return true;
    }

    @Override
    public boolean contentComplete()
    {
        return false;
    }

    @Override
    public void parsedTrailer(HttpField trailer)
    {
        HttpExchange exchange = getHttpExchange();
        unsolicited |= exchange == null;
        if (unsolicited)
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("Appending trailer '{}' to response in {}", trailer, this);
        exchange.getResponse().trailer(trailer);
    }

    @Override
    public boolean messageComplete()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null || unsolicited)
        {
            // We received an unsolicited response from the server.
            networkBuffer.clear();
            getHttpConnection().close();
            return false;
        }

        int status = exchange.getResponse().getStatus();
        if (!HttpStatus.isInterim(status))
            inMessages.increment();

        if (chunk != null)
            throw new IllegalStateException();
        chunk = Content.Chunk.EOF;
        state = State.COMPLETE;
        return true;
    }

    private void receiveNext()
    {
        if (hasContent())
            throw new IllegalStateException();
        if (chunk != null)
            throw new IllegalStateException();

        if (LOG.isDebugEnabled())
            LOG.debug("Receiving next request in {}", this);
        boolean setFillInterest = parseAndFill(true);
        if (!hasContent() && setFillInterest)
            fillInterested();
    }

    @Override
    public void earlyEOF()
    {
        HttpExchange exchange = getHttpExchange();
        HttpConnectionOverHTTP connection = getHttpConnection();
        if (exchange == null || unsolicited)
            connection.close();
        else
            failAndClose(new EOFException(String.valueOf(connection)));
    }

    @Override
    public void badMessage(HttpException failure)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null || unsolicited)
        {
            getHttpConnection().close();
        }
        else
        {
            HttpResponse response = exchange.getResponse();
            response.status(failure.getCode()).reason(failure.getReason());
            failAndClose(new HttpResponseException("HTTP protocol violation: bad response on " + getHttpConnection(), response, (Throwable)failure));
        }
    }

    long getMessagesIn()
    {
        return inMessages.longValue();
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]", super.toString(), parser);
    }

    private enum State
    {
        STATUS, HEADERS, CONTENT, COMPLETE
    }
}
