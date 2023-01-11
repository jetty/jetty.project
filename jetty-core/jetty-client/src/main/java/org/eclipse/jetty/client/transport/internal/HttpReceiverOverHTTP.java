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

package org.eclipse.jetty.client.transport.internal;

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.internal.HttpExchange;
import org.eclipse.jetty.client.internal.HttpReceiver;
import org.eclipse.jetty.client.internal.HttpResponse;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpReceiverOverHTTP extends HttpReceiver implements HttpParser.ResponseHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpReceiverOverHTTP.class);

    private final LongAdder inMessages = new LongAdder();
    private final HttpParser parser;
    private final RetainableByteBufferPool retainableByteBufferPool;
    private RetainableByteBuffer networkBuffer;
    private boolean shutdown;
    private boolean complete;
    private boolean unsolicited;
    private String method;
    private int status;
    private Content.Chunk chunk;
    private Runnable action;

    public HttpReceiverOverHTTP(HttpChannelOverHTTP channel)
    {
        super(channel);
        HttpClient httpClient = channel.getHttpDestination().getHttpClient();
        parser = new HttpParser(this, -1, httpClient.getHttpCompliance());
        HttpClientTransport transport = httpClient.getTransport();
        if (transport instanceof HttpClientTransportOverHTTP httpTransport)
        {
            parser.setHeaderCacheSize(httpTransport.getHeaderCacheSize());
            parser.setHeaderCacheCaseSensitive(httpTransport.isHeaderCacheCaseSensitive());
        }
        retainableByteBufferPool = httpClient.getByteBufferPool().asRetainableByteBufferPool();
    }

    void receive()
    {
        if (!hasContent())
        {
            boolean setFillInterest = parseAndFill();
            if (!hasContent() && setFillInterest)
                fillInterested();
        }
        else
        {
            responseContentAvailable();
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
        {
            chunk.release();
            chunk = null;
        }
    }

    @Override
    protected void dispose()
    {
        super.dispose();
        parser.close();
        if (chunk != null)
        {
            chunk.release();
            chunk = null;
        }
    }

    @Override
    public Content.Chunk read(boolean fillInterestIfNeeded)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Reading, fillInterestIfNeeded={} in {}", fillInterestIfNeeded, this);

        Content.Chunk chunk = consumeChunk();
        if (chunk != null)
            return chunk;
        boolean needFillInterest = parseAndFill();
        if (LOG.isDebugEnabled())
            LOG.debug("ParseAndFill needFillInterest {} in {}", needFillInterest, this);
        chunk = consumeChunk();
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
        return networkBuffer == null ? null : networkBuffer.getBuffer();
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
        return retainableByteBufferPool.acquire(client.getResponseBufferSize(), direct);
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
            BufferUtil.put(networkBuffer.getBuffer(), upgradeBuffer);
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
    private boolean parseAndFill()
    {
        HttpConnectionOverHTTP connection = getHttpConnection();
        EndPoint endPoint = connection.getEndPoint();
        try
        {
            if (networkBuffer == null)
                acquireNetworkBuffer();
            while (true)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Parsing {} in {}", BufferUtil.toDetailString(networkBuffer.getBuffer()), this);
                // Always parse even empty buffers to advance the parser.
                if (parse())
                {
                    // Return immediately, as this thread may be in a race
                    // with e.g. another thread demanding more content.
                    return false;
                }
                if (LOG.isDebugEnabled())
                    LOG.debug("Parser willing to advance in {}", this);

                // Connection may be closed in a parser callback.
                if (connection.isClosed())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Closed {} in {}", connection, this);
                    releaseNetworkBuffer();
                    return false;
                }

                if (networkBuffer.isRetained())
                    reacquireNetworkBuffer();

                // The networkBuffer may have been reacquired.
                int read = endPoint.fill(networkBuffer.getBuffer());
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
                    releaseNetworkBuffer();
                    shutdown();
                    return false;
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
    private boolean parse()
    {
        while (true)
        {
            boolean handle = parser.parseNext(networkBuffer.getBuffer());
            if (LOG.isDebugEnabled())
                LOG.debug("Parse result={} on {}", handle, this);
            Runnable action = getAndSetAction(null);
            if (action != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Executing action after parser returned: {} on {}", action, this);
                action.run();
                if (LOG.isDebugEnabled())
                    LOG.debug("Action executed after Parse result={} on {}", handle, this);
            }
            if (handle)
            {
                // When the receiver is aborted, the parser is closed in dispose() which changes
                // its state to State.CLOSE; so checking parser.isClose() is just a way to check
                // if the receiver was aborted or not.
                return !parser.isClose();
            }

            boolean complete = this.complete;
            this.complete = false;
            if (LOG.isDebugEnabled())
                LOG.debug("Parse complete={}, {} {} in {}", complete, networkBuffer, parser, this);

            if (complete)
            {
                int status = this.status;
                this.status = 0;
                // Connection upgrade due to 101, bail out.
                if (status == HttpStatus.SWITCHING_PROTOCOLS_101)
                    return true;
                // Connection upgrade due to CONNECT + 200, bail out.
                String method = this.method;
                this.method = null;
                if (getHttpChannel().isTunnel(method, status))
                    return true;

                if (networkBuffer.isEmpty())
                    return false;

                if (!HttpStatus.isInformational(status))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Discarding unexpected content after response {}: {} in {}", status, networkBuffer, this);
                    networkBuffer.clear();
                }
                return false;
            }

            if (networkBuffer.isEmpty())
                return false;
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
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
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
        if (LOG.isDebugEnabled())
            LOG.debug("Setting action to responseHeaders(exchange, boolean) on {}", this);
        if (getAndSetAction(() -> responseHeaders(exchange)) != null)
            throw new IllegalStateException();
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

        RetainableByteBuffer networkBuffer = this.networkBuffer;
        networkBuffer.retain();
        chunk = Content.Chunk.from(buffer, false, networkBuffer);
        if (LOG.isDebugEnabled())
            LOG.debug("Setting action to responseContentAvailable on {}", this);
        if (getAndSetAction(this::responseContentAvailable) != null)
            throw new IllegalStateException();
        if (getHttpConnection().isFillInterested())
            throw new IllegalStateException();
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
            getHttpConnection().close();
            return false;
        }

        int status = exchange.getResponse().getStatus();
        if (!HttpStatus.isInterim(status))
        {
            inMessages.increment();
            complete = true;
        }

        if (chunk != null)
            throw new IllegalStateException();
        chunk = Content.Chunk.EOF;

        boolean isUpgrade = status == HttpStatus.SWITCHING_PROTOCOLS_101;
        boolean isTunnel = getHttpChannel().isTunnel(method, status);
        Runnable task = isUpgrade || isTunnel ? null : this::receiveNext;
        if (LOG.isDebugEnabled())
            LOG.debug("Message complete, calling response success with task {} in {}", task, this);
        responseSuccess(exchange, task);
        return false;
    }

    private void receiveNext()
    {
        if (hasContent())
            throw new IllegalStateException();
        if (chunk != null)
            throw new IllegalStateException();

        if (LOG.isDebugEnabled())
            LOG.debug("Receiving next request in {}", this);
        boolean setFillInterest = parseAndFill();
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
    public void badMessage(BadMessageException failure)
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
            failAndClose(new HttpResponseException("HTTP protocol violation: bad response on " + getHttpConnection(), response, failure));
        }
    }

    private Runnable getAndSetAction(Runnable action)
    {
        Runnable r = this.action;
        this.action = action;
        return r;
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
}
