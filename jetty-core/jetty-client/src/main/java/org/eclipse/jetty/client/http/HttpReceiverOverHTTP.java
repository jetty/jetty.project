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

package org.eclipse.jetty.client.http;

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.HttpResponseException;
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
import org.eclipse.jetty.util.thread.SerializedInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpReceiverOverHTTP extends HttpReceiver implements HttpParser.ResponseHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpReceiverOverHTTP.class);

    private final AtomicReference<Runnable> actionRef = new AtomicReference<>();
    private final LongAdder inMessages = new LongAdder();
    private final HttpParser parser;
    private final RetainableByteBufferPool retainableByteBufferPool;
    private RetainableByteBuffer networkBuffer;
    private boolean shutdown;
    private boolean complete;
    private boolean unsolicited;
    private String method;
    private int status;
    private Content.Chunk contentGenerated;
    private ContentSource contentSource;

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

    @Override
    protected void reset()
    {
        super.reset();
        parser.reset();
        contentGenerated = null;
        contentSource = null;
    }

    @Override
    protected void dispose(Throwable x)
    {
        super.dispose(x);
        parser.close();
        contentGenerated = null;
        contentSource = null;
    }

    @Override
    protected Content.Source newContentSource()
    {
        if (contentSource != null)
            throw new IllegalStateException();
        contentSource = new ContentSource();
        return contentSource;
    }

    private class ContentSource implements Content.Source
    {
        private final SerializedInvoker invoker = new SerializedInvoker();
        private volatile Content.Chunk currentChunk;
        private volatile Runnable demandCallback;

        @Override
        public Content.Chunk read()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("read");
            Content.Chunk chunk = consumeCurrentChunk();
            if (chunk != null)
                return chunk;
            currentChunk = HttpReceiverOverHTTP.this.read(false);
            return consumeCurrentChunk();
        }

        public void onDataAvailable()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onDataAvailable");
            if (demandCallback != null)
                invoker.run(this::invokeDemandCallback);
        }

        private Content.Chunk consumeCurrentChunk()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("consumeCurrentChunk");
            if (currentChunk != null)
            {
                Content.Chunk rc = currentChunk;
                if (!(rc instanceof Content.Chunk.Error))
                    currentChunk = currentChunk.isLast() ? Content.Chunk.EOF : null;
                return rc;
            }
            return null;
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("demand");
            if (demandCallback == null)
                throw new IllegalArgumentException();
            if (this.demandCallback != null)
                throw new IllegalStateException();
            this.demandCallback = demandCallback;

            invoker.run(this::meetDemand);
        }

        private void meetDemand()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("meetDemand");
            while (true)
            {
                if (currentChunk != null)
                {
                    invoker.run(this::invokeDemandCallback);
                    break;
                }
                else
                {
                    currentChunk = HttpReceiverOverHTTP.this.read(true);
                    if (currentChunk == null)
                        return;
                }
            }
        }

        private void invokeDemandCallback()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("invokeDemandCallback");

            Runnable demandCallback = this.demandCallback;
            this.demandCallback = null;
            if (demandCallback != null)
            {
                try
                {
                    demandCallback.run();
                }
                catch (Throwable x)
                {
                    fail(x);
                }
            }
        }

        @Override
        public void fail(Throwable failure)
        {
            if (currentChunk != null)
            {
                currentChunk.release();
                failAndClose(failure);
            }
            currentChunk = Content.Chunk.from(failure);
        }
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

    public void receive()
    {
        // This method is the callback of fill interest.
        // As such, it is called repeatedly until the ContentSourceListener.onContentSource() loop gets started;
        // meaning firstContent is false and it must register for fill interest if no filling was done
        // until onContentSource() gets called.
        // Once onContentSource() gets called, firstContent is true and it must just notify that content may be generated.

        if (contentSource == null)
        {
            if (networkBuffer == null)
                acquireNetworkBuffer();
            parseAndFill();
            Runnable r = actionRef.getAndSet(null);
            if (r != null)
                r.run(); // This starts the onContentSource loop.
            if (contentSource == null && networkBuffer == null)
                fillInterestedIfNeeded();
        }
        else
        {
            // This calls the demand callback of the onContentSource loop.
            contentSource.onDataAvailable();
        }
    }

    private Content.Chunk read(boolean fillInterestIfNeeded)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("read f={} c={}", fillInterestIfNeeded, contentGenerated);

        Content.Chunk chunk = consumeContentGenerated();
        if (chunk != null)
            return chunk;
        if (networkBuffer == null)
            acquireNetworkBuffer();
        boolean contentGenerated = parseAndFill();
        if (!contentGenerated && fillInterestIfNeeded)
            fillInterestedIfNeeded();
        return consumeContentGenerated();
    }

    private void acquireNetworkBuffer()
    {
        networkBuffer = newNetworkBuffer();
        if (LOG.isDebugEnabled())
            LOG.debug("Acquired {}", networkBuffer);
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
            LOG.debug("Reacquired {} <- {}", currentBuffer, networkBuffer);
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
            LOG.debug("Released {}", networkBuffer);
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
     * @return true if some content was generated, false otherwise.
     */
    private boolean parseAndFill()
    {
        HttpConnectionOverHTTP connection = getHttpConnection();
        EndPoint endPoint = connection.getEndPoint();
        try
        {
            while (true)
            {
                // Always parse even empty buffers to advance the parser.
                if (parse())
                {
                    // Return immediately, as this thread may be in a race
                    // with e.g. another thread demanding more content.
                    return contentGenerated != null;
                }

                // Connection may be closed in a parser callback.
                if (connection.isClosed())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Closed {}", connection);
                    releaseNetworkBuffer();
                    return contentGenerated != null;
                }

                if (networkBuffer.isRetained())
                    reacquireNetworkBuffer();

                // The networkBuffer may have been reacquired.
                int read = endPoint.fill(networkBuffer.getBuffer());
                if (LOG.isDebugEnabled())
                    LOG.debug("Read {} bytes in {} from {}", read, networkBuffer, endPoint);

                if (read > 0)
                {
                    connection.addBytesIn(read);
                }
                else if (read == 0)
                {
                    assert networkBuffer.isEmpty();
                    releaseNetworkBuffer();
                    return contentGenerated != null;
                }
                else
                {
                    releaseNetworkBuffer();
                    shutdown();
                    return contentGenerated != null;
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Error processing {}", endPoint, x);
            releaseNetworkBuffer();
            failAndClose(x);
            return contentGenerated != null;
        }
    }

    private Content.Chunk consumeContentGenerated()
    {
        Content.Chunk chunk = this.contentGenerated;
        this.contentGenerated = null;
        return chunk;
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
            boolean failed = isFailed();
            if (LOG.isDebugEnabled())
                LOG.debug("Parse result={}, failed={}", handle, failed);
            if (handle)
            {
                Runnable action = actionRef.getAndSet(null);
                if (action != null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("executing action after parser returned: {}", action);
                    action.run();
                }
                return parser.isClose();
            }

            boolean complete = this.complete;
            this.complete = false;
            if (LOG.isDebugEnabled())
                LOG.debug("Parse complete={}, {} {}", complete, networkBuffer, parser);

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
                        LOG.debug("Discarding unexpected content after response {}: {}", status, networkBuffer);
                    networkBuffer.clear();
                }
                return false;
            }

            if (networkBuffer.isEmpty())
                return false;
        }
    }

    private void fillInterestedIfNeeded()
    {
        if (!getHttpConnection().isFillInterested())
            fillInterested();
    }

    protected void fillInterested()
    {
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
        actionRef.set(() -> responseHeaders(exchange));
        return true;
    }

    @Override
    public boolean content(ByteBuffer buffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Parser generated content {}", BufferUtil.toDetailString(buffer));
        HttpExchange exchange = getHttpExchange();
        unsolicited |= exchange == null;
        if (unsolicited)
            return false;

        RetainableByteBuffer networkBuffer = this.networkBuffer;
        networkBuffer.retain();

        if (contentGenerated != null)
            throw new IllegalStateException("Content generated with unconsumed content left");

        contentGenerated = Content.Chunk.from(buffer, false, networkBuffer);
        actionRef.set(contentSource::onDataAvailable);
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

        if (contentGenerated != null)
            throw new IllegalStateException();
        contentGenerated = Content.Chunk.EOF;
        responseSuccess(exchange);
        return status == HttpStatus.SWITCHING_PROTOCOLS_101;
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

    private void failAndClose(Throwable failure)
    {
        responseFailure(failure, (failed) ->
        {
            if (failed)
                getHttpConnection().close(failure);
        });
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
