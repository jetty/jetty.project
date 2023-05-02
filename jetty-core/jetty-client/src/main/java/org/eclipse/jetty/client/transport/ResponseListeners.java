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

package org.eclipse.jetty.client.transport;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A specialized container for response listeners.</p>
 */
public class ResponseListeners
{
    private static final Logger LOG = LoggerFactory.getLogger(ResponseListeners.class);

    private Response.BeginListener beginListener;
    private Response.HeaderListener headerListener;
    private Response.HeadersListener headersListener;
    private Response.ContentSourceListener contentSourceListener;
    private Response.SuccessListener successListener;
    private Response.FailureListener failureListener;
    private Response.CompleteListener completeListener;

    public ResponseListeners()
    {
    }

    public ResponseListeners(Response.Listener listener)
    {
        beginListener = listener;
        headerListener = listener;
        headersListener = listener;
        contentSourceListener = listener;
        successListener = listener;
        failureListener = listener;
        completeListener = listener;
    }

    public ResponseListeners(ResponseListeners that)
    {
        beginListener = that.beginListener;
        headerListener = that.headerListener;
        headersListener = that.headersListener;
        contentSourceListener = that.contentSourceListener;
        successListener = that.successListener;
        failureListener = that.failureListener;
        completeListener = that.completeListener;
    }

    public boolean addBeginListener(Response.BeginListener listener)
    {
        if (listener == null)
            return false;
        Response.BeginListener existing = beginListener;
        beginListener = existing == null ? listener : response ->
        {
            notifyBegin(existing, response);
            notifyBegin(listener, response);
        };
        return true;
    }

    public void notifyBegin(Response response)
    {
        notifyBegin(beginListener, response);
    }

    private static void notifyBegin(Response.BeginListener listener, Response response)
    {
        try
        {
            if (listener != null)
                listener.onBegin(response);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public boolean addHeaderListener(Response.HeaderListener listener)
    {
        if (listener == null)
            return false;
        Response.HeaderListener existing = headerListener;
        headerListener = existing == null ? listener : (response, field) ->
        {
            boolean r1 = notifyHeader(existing, response, field);
            boolean r2 = notifyHeader(listener, response, field);
            return r1 && r2;
        };
        return true;
    }

    public boolean notifyHeader(Response response, HttpField field)
    {
        return notifyHeader(headerListener, response, field);
    }

    private static boolean notifyHeader(Response.HeaderListener listener, Response response, HttpField field)
    {
        try
        {
            if (listener != null)
                return listener.onHeader(response, field);
            return true;
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
            return false;
        }
    }

    public boolean addHeadersListener(Response.HeadersListener listener)
    {
        if (listener == null)
            return false;
        Response.HeadersListener existing = headersListener;
        headersListener = existing == null ? listener : response ->
        {
            notifyHeaders(existing, response);
            notifyHeaders(listener, response);
        };
        return true;
    }

    public void notifyHeaders(Response response)
    {
        notifyHeaders(headersListener, response);
    }

    private static void notifyHeaders(Response.HeadersListener listener, Response response)
    {
        try
        {
            if (listener != null)
                listener.onHeaders(response);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public boolean addContentSourceListener(Response.ContentSourceListener listener)
    {
        if (listener == null)
            return false;
        Response.ContentSourceListener existing = contentSourceListener;
        if (existing == null)
        {
            contentSourceListener = listener;
        }
        else
        {
            if (existing instanceof ContentSourceDemultiplexer demultiplexer)
            {
                demultiplexer.addContentSourceListener(listener);
            }
            else
            {
                ContentSourceDemultiplexer demultiplexer = new ContentSourceDemultiplexer();
                demultiplexer.addContentSourceListener(existing);
                demultiplexer.addContentSourceListener(listener);
                contentSourceListener = demultiplexer;
            }
        }
        return true;
    }

    public boolean hasContentSourceListeners()
    {
        return contentSourceListener != null;
    }

    public void notifyContentSource(Response response, Content.Source contentSource)
    {
        if (hasContentSourceListeners())
        {
            if (contentSourceListener instanceof ContentSourceDemultiplexer demultiplexer)
            {
                // More than 1 ContentSourceListeners -> notify the demultiplexer.
                notifyContentSource(demultiplexer, response, contentSource);
            }
            else
            {
                // Exactly 1 ContentSourceListener -> notify it directly.
                notifyContentSource(contentSourceListener, response, contentSource);
            }
        }
        else
        {
            // No ContentSourceListener -> consume the content.
            notifyContentSource((r, c) -> consume(c), response, contentSource);
        }
    }

    private static void consume(Content.Source contentSource)
    {
        // This method must drive the read/demand loop by alternating read and demand calls
        // otherwise if reads are always satisfied with content, and a large amount of data
        // is being sent, it won't be possible to abort this loop as the demand callback needs
        // to return before abort() can have any effect.
        Content.Chunk chunk = contentSource.read();
        if (chunk != null)
            chunk.release();
        if (chunk == null || !chunk.isLast())
            contentSource.demand(() -> consume(contentSource));
    }

    private static void notifyContentSource(Response.ContentSourceListener listener, Response response, Content.Source contentSource)
    {
        try
        {
            if (listener != null)
                listener.onContentSource(response, contentSource);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public boolean addSuccessListener(Response.SuccessListener listener)
    {
        if (listener == null)
            return false;
        Response.SuccessListener existing = successListener;
        successListener = existing == null ? listener : response ->
        {
            notifySuccess(existing, response);
            notifySuccess(listener, response);
        };
        return true;
    }

    public void notifySuccess(Response response)
    {
        notifySuccess(successListener, response);
    }

    private static void notifySuccess(Response.SuccessListener listener, Response response)
    {
        try
        {
            if (listener != null)
                listener.onSuccess(response);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public boolean addFailureListener(Response.FailureListener listener)
    {
        if (listener == null)
            return false;
        Response.FailureListener existing = failureListener;
        failureListener = existing == null ? listener : (response, failure) ->
        {
            notifyFailure(existing, response, failure);
            notifyFailure(listener, response, failure);
        };
        return true;
    }

    public void notifyFailure(Response response, Throwable failure)
    {
        notifyFailure(failureListener, response, failure);
    }

    private static void notifyFailure(Response.FailureListener listener, Response response, Throwable failure)
    {
        try
        {
            if (listener != null)
                listener.onFailure(response, failure);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public boolean addCompleteListener(Response.CompleteListener listener)
    {
        return addCompleteListener(listener, true);
    }

    private boolean addCompleteListener(Response.CompleteListener listener, boolean includeOtherEvents)
    {
        if (listener == null)
            return false;
        if (includeOtherEvents)
        {
            if (listener instanceof Response.BeginListener l)
                addBeginListener(l);
            if (listener instanceof Response.HeaderListener l)
                addHeaderListener(l);
            if (listener instanceof Response.HeadersListener l)
                addHeadersListener(l);
            if (listener instanceof Response.ContentSourceListener l)
                addContentSourceListener(l);
            if (listener instanceof Response.SuccessListener l)
                addSuccessListener(l);
            if (listener instanceof Response.FailureListener l)
                addFailureListener(l);
        }
        Response.CompleteListener existing = completeListener;
        completeListener = existing == null ? listener : result ->
        {
            notifyComplete(existing, result);
            notifyComplete(listener, result);
        };
        return true;
    }

    public void notifyComplete(Result result)
    {
        notifyComplete(completeListener, result);
    }

    private static void notifyComplete(Response.CompleteListener listener, Result result)
    {
        try
        {
            if (listener != null)
                listener.onComplete(result);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public boolean addListener(Response.Listener listener)
    {
        // Use binary OR to avoid short-circuit.
        return addBeginListener(listener) |
               addHeaderListener(listener) |
               addHeadersListener(listener) |
               addContentSourceListener(listener) |
               addSuccessListener(listener) |
               addFailureListener(listener) |
               addCompleteListener(listener, false);
    }

    public boolean addResponseListeners(ResponseListeners listeners)
    {
        // Use binary OR to avoid short-circuit.
        return addBeginListener(listeners.beginListener) |
               addHeaderListener(listeners.headerListener) |
               addHeadersListener(listeners.headersListener) |
               addContentSourceListener(listeners.contentSourceListener) |
               addSuccessListener(listeners.successListener) |
               addFailureListener(listeners.failureListener) |
               addCompleteListener(listeners.completeListener, false);
    }

    private void emitEvents(Response response)
    {
        notifyBegin(beginListener, response);
        Iterator<HttpField> iterator = response.getHeaders().iterator();
        while (iterator.hasNext())
        {
            HttpField field = iterator.next();
            if (!notifyHeader(headerListener, response, field))
                iterator.remove();
        }
        notifyHeaders(headersListener, response);
        if (response instanceof ContentResponse contentResponse)
        {
            byte[] content = contentResponse.getContent();
            if (content != null && content.length > 0)
            {
                ByteBufferContentSource byteBufferContentSource = new ByteBufferContentSource(ByteBuffer.wrap(content));
                notifyContentSource(contentSourceListener, response, byteBufferContentSource);
            }
        }
    }

    public void emitSuccess(Response response)
    {
        emitEvents(response);
        notifySuccess(successListener, response);
    }

    public void emitFailure(Response response, Throwable failure)
    {
        emitEvents(response);
        notifyFailure(failureListener, response, failure);
    }

    public void emitSuccessComplete(Result result)
    {
        emitSuccess(result.getResponse());
        notifyComplete(completeListener, result);
    }

    public void emitFailureComplete(Result result)
    {
        emitFailure(result.getResponse(), result.getFailure());
        notifyComplete(completeListener, result);
    }

    private static class ContentSourceDemultiplexer implements Response.ContentSourceListener
    {
        private static final Logger LOG = LoggerFactory.getLogger(ContentSourceDemultiplexer.class);

        private final AutoLock lock = new AutoLock();
        private final List<Response.ContentSourceListener> listeners = new ArrayList<>(2);
        private final List<ContentSource> contentSources = new ArrayList<>(2);
        private Content.Source originalContentSource;

        private void addContentSourceListener(Response.ContentSourceListener listener)
        {
            listeners.add(listener);
        }

        @Override
        public void onContentSource(Response response, Content.Source contentSource)
        {
            originalContentSource = contentSource;
            for (int i = 0; i < listeners.size(); ++i)
            {
                Response.ContentSourceListener listener = listeners.get(i);
                ContentSource cs = new ContentSource(i);
                contentSources.add(cs);
                notifyContentSource(listener, response, cs);
            }
        }

        private Counters countStates()
        {
            assert lock.isHeldByCurrentThread();
            int demands = 0;
            int failures = 0;
            for (ContentSource contentSource : contentSources)
            {
                switch (contentSource.state)
                {
                    case DEMANDED -> demands++;
                    case FAILED -> failures++;
                }
            }
            return new Counters(demands, failures);
        }

        private void resetDemands()
        {
            assert lock.isHeldByCurrentThread();
            for (ContentSource contentSource : contentSources)
            {
                if (contentSource.state == State.DEMANDED)
                    contentSource.state = State.IDLE;
            }
        }

        private void onDemandCallback()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Original content source's demand calling back");

            Content.Chunk chunk = originalContentSource.read();
            // Demultiplexer content sources are invoked sequentially to be consistent with other listeners,
            // applications can parallelize from the listeners they register if needed.
            if (LOG.isDebugEnabled())
                LOG.debug("Read from original content source {}", chunk);
            for (ContentSource demultiplexerContentSource : contentSources)
            {
                demultiplexerContentSource.onChunk(chunk);
            }
            chunk.release();
        }

        private void registerFailure(ContentSource contentSource, Throwable failure)
        {
            boolean processFail = false;
            boolean processDemand = false;
            Counters counters;
            try (AutoLock ignored = lock.lock())
            {
                contentSource.state = State.FAILED;
                counters = countStates();
                if (counters.failures() == listeners.size())
                {
                    processFail = true;
                }
                else if (counters.total() == listeners.size())
                {
                    resetDemands();
                    processDemand = true;
                }
            }
            if (processFail)
                originalContentSource.fail(failure);
            else if (processDemand)
                originalContentSource.demand(this::onDemandCallback);

            if (LOG.isDebugEnabled())
                LOG.debug("Registered failure on {}; {}", contentSource, counters);
        }

        private void registerDemand(ContentSource contentSource)
        {
            boolean processDemand = false;
            Counters counters;
            try (AutoLock ignored = lock.lock())
            {
                if (contentSource.state != State.IDLE)
                    return;
                contentSource.state = State.DEMANDED;
                counters = countStates();
                if (counters.total() == listeners.size())
                {
                    resetDemands();
                    processDemand = true;
                }
            }
            if (processDemand)
                originalContentSource.demand(this::onDemandCallback);

            if (LOG.isDebugEnabled())
                LOG.debug("Registered demand on {}; {}", contentSource, counters);
        }

        private class ContentSource implements Content.Source
        {
            private static final Content.Chunk ALREADY_READ_CHUNK = new Content.Chunk()
            {
                @Override
                public ByteBuffer getByteBuffer()
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean isLast()
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean canRetain()
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void retain()
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean release()
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public String toString()
                {
                    return "AlreadyReadChunk";
                }
            };
            private final int index;
            private final AtomicReference<Runnable> demandCallbackRef = new AtomicReference<>();
            private volatile Content.Chunk chunk;
            private volatile State state = State.IDLE;

            private ContentSource(int index)
            {
                this.index = index;
            }

            private void onChunk(Content.Chunk chunk)
            {
                Content.Chunk currentChunk = this.chunk;
                if (LOG.isDebugEnabled())
                    LOG.debug("Registering content in multiplexed content source #{} that contains {}", index, currentChunk);
                if (currentChunk == null || currentChunk == ALREADY_READ_CHUNK)
                {
                    if (chunk.hasRemaining())
                        chunk = Content.Chunk.asChunk(chunk.getByteBuffer().slice(), chunk.isLast(), chunk);
                    // Retain the slice because it is stored for later reads.
                    chunk.retain();
                    this.chunk = chunk;
                }
                else if (!currentChunk.isLast())
                {
                    throw new IllegalStateException("Cannot overwrite chunk");
                }
                onDemandCallback();
            }

            private void onDemandCallback()
            {
                Runnable callback = demandCallbackRef.getAndSet(null);
                if (LOG.isDebugEnabled())
                    LOG.debug("Content source #{} invoking demand callback {}", index, callback);
                if (callback != null)
                {
                    try
                    {
                        callback.run();
                    }
                    catch (Throwable x)
                    {
                        fail(x);
                    }
                }
            }

            @Override
            public Content.Chunk read()
            {
                if (chunk == ALREADY_READ_CHUNK)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Content source #{} already read current chunk", index);
                    return null;
                }

                Content.Chunk result = chunk;
                if (result != null)
                    chunk = result.isLast() ? Content.Chunk.next(result) : ALREADY_READ_CHUNK;
                if (LOG.isDebugEnabled())
                    LOG.debug("Content source #{} reading current chunk {}", index, result);
                return result;
            }

            @Override
            public void demand(Runnable demandCallback)
            {
                if (!demandCallbackRef.compareAndSet(null, Objects.requireNonNull(demandCallback)))
                    throw new IllegalStateException();
                Content.Chunk currentChunk = this.chunk;
                if (LOG.isDebugEnabled())
                    LOG.debug("Content source #{} demand while current chunk is {}", index, currentChunk);
                if (currentChunk == null || currentChunk == ALREADY_READ_CHUNK)
                    registerDemand(this);
                else
                    onDemandCallback();
            }

            @Override
            public void fail(Throwable failure)
            {
                Content.Chunk currentChunk = chunk;
                if (LOG.isDebugEnabled())
                    LOG.debug("Content source #{} fail while current chunk is {}", index, currentChunk);
                if (currentChunk instanceof Content.Chunk.Error)
                    return;
                if (currentChunk != null)
                    currentChunk.release();
                this.chunk = Content.Chunk.from(failure);
                onDemandCallback();
                registerFailure(this, failure);
            }

            @Override
            public String toString()
            {
                return "%s@%x[i=%d,d=%s,c=%s,s=%s]".formatted(getClass().getSimpleName(), hashCode(), index, demandCallbackRef, chunk, state);
            }
        }

        enum State
        {
            IDLE, DEMANDED, FAILED
        }

        private record Counters(int demands, int failures)
        {
            public int total()
            {
                return demands + failures;
            }
        }
    }
}
