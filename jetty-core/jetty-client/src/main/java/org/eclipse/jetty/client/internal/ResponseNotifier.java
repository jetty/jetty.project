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

package org.eclipse.jetty.client.internal;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.util.AtomicBiInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResponseNotifier
{
    private static final Logger LOG = LoggerFactory.getLogger(ResponseNotifier.class);

    public void notifyBegin(List<Response.ResponseListener> listeners, Response response)
    {
        for (Response.ResponseListener listener : listeners)
        {
            if (listener instanceof Response.BeginListener)
                notifyBegin((Response.BeginListener)listener, response);
        }
    }

    private void notifyBegin(Response.BeginListener listener, Response response)
    {
        try
        {
            listener.onBegin(response);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public boolean notifyHeader(List<Response.ResponseListener> listeners, Response response, HttpField field)
    {
        boolean result = true;
        for (Response.ResponseListener listener : listeners)
        {
            if (listener instanceof Response.HeaderListener)
                result &= notifyHeader((Response.HeaderListener)listener, response, field);
        }
        return result;
    }

    private boolean notifyHeader(Response.HeaderListener listener, Response response, HttpField field)
    {
        try
        {
            return listener.onHeader(response, field);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
            return false;
        }
    }

    public void notifyHeaders(List<Response.ResponseListener> listeners, Response response)
    {
        for (Response.ResponseListener listener : listeners)
        {
            if (listener instanceof Response.HeadersListener)
                notifyHeaders((Response.HeadersListener)listener, response);
        }
    }

    private void notifyHeaders(Response.HeadersListener listener, Response response)
    {
        try
        {
            listener.onHeaders(response);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void notifyContent(Response response, Content.Source contentSource, List<Response.ContentSourceListener> contentListeners)
    {
        int count = contentListeners.size();
        if (count == 0)
        {
            // Exactly 0 ContentSourceListener -> drive the read/demand loop from here
            // with a loop that does not use while (true).
            consumeAll(contentSource);
        }
        else if (count == 1)
        {
            // Exactly 1 ContentSourceListener -> notify it so that it drives the read/demand loop.
            Response.ContentSourceListener listener = contentListeners.get(0);
            notifyContent(listener, response, contentSource);
        }
        else
        {
            // 2+ ContentSourceListeners -> create a multiplexed content source and notify all listeners so that
            // they drive each a read/demand loop.
            ContentSourceDemultiplexer demultiplexer = new ContentSourceDemultiplexer(contentSource, contentListeners.size());
            for (int i = 0; i < contentListeners.size(); i++)
            {
                Response.ContentSourceListener listener = contentListeners.get(i);
                notifyContent(listener, response, demultiplexer.contentSource(i));
            }
        }
    }

    private static void consumeAll(Content.Source contentSource)
    {
        // This method must drive the read/demand loop by alternating read and demand calls
        // otherwise if reads are always satisfied with content, and a large amount of data
        // is being sent, it won't be possible to abort this loop as the demand callback needs
        // to return before abort() can have any effect.
        Content.Chunk chunk = contentSource.read();
        if (chunk != null)
            chunk.release();
        if (chunk == null || !chunk.isLast())
            contentSource.demand(() -> consumeAll(contentSource));
    }

    private void notifyContent(Response.ContentSourceListener listener, Response response, Content.Source contentSource)
    {
        try
        {
            listener.onContentSource(response, contentSource);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void notifySuccess(List<Response.ResponseListener> listeners, Response response)
    {
        for (Response.ResponseListener listener : listeners)
        {
            if (listener instanceof Response.SuccessListener)
                notifySuccess((Response.SuccessListener)listener, response);
        }
    }

    private void notifySuccess(Response.SuccessListener listener, Response response)
    {
        try
        {
            listener.onSuccess(response);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void notifyFailure(List<Response.ResponseListener> listeners, Response response, Throwable failure)
    {
        for (Response.ResponseListener listener : listeners)
        {
            if (listener instanceof Response.FailureListener)
                notifyFailure((Response.FailureListener)listener, response, failure);
        }
    }

    private void notifyFailure(Response.FailureListener listener, Response response, Throwable failure)
    {
        try
        {
            listener.onFailure(response, failure);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void notifyComplete(List<Response.ResponseListener> listeners, Result result)
    {
        for (Response.ResponseListener listener : listeners)
        {
            if (listener instanceof Response.CompleteListener)
                notifyComplete((Response.CompleteListener)listener, result);
        }
    }

    private void notifyComplete(Response.CompleteListener listener, Result result)
    {
        try
        {
            listener.onComplete(result);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void forwardSuccess(List<Response.ResponseListener> listeners, Response response)
    {
        forwardEvents(listeners, response);
        notifySuccess(listeners, response);
    }

    public void forwardSuccessComplete(List<Response.ResponseListener> listeners, Request request, Response response)
    {
        forwardSuccess(listeners, response);
        notifyComplete(listeners, new Result(request, response));
    }

    public void forwardFailure(List<Response.ResponseListener> listeners, Response response, Throwable failure)
    {
        forwardEvents(listeners, response);
        notifyFailure(listeners, response, failure);
    }

    private void forwardEvents(List<Response.ResponseListener> listeners, Response response)
    {
        notifyBegin(listeners, response);
        Iterator<HttpField> iterator = response.getHeaders().iterator();
        while (iterator.hasNext())
        {
            HttpField field = iterator.next();
            if (!notifyHeader(listeners, response, field))
                iterator.remove();
        }
        notifyHeaders(listeners, response);
        if (response instanceof ContentResponse)
        {
            byte[] content = ((ContentResponse)response).getContent();
            if (content != null && content.length > 0)
            {
                List<Response.ContentSourceListener> contentListeners = listeners.stream()
                    .filter(Response.ContentSourceListener.class::isInstance)
                    .map(Response.ContentSourceListener.class::cast)
                    .toList();
                ByteBufferContentSource byteBufferContentSource = new ByteBufferContentSource(ByteBuffer.wrap(content));
                notifyContent(response, byteBufferContentSource, contentListeners);
            }
        }
    }

    public void forwardFailureComplete(List<Response.ResponseListener> listeners, Request request, Throwable requestFailure, Response response, Throwable responseFailure)
    {
        forwardFailure(listeners, response, responseFailure);
        notifyComplete(listeners, new Result(request, requestFailure, response, responseFailure));
    }

    private static class ContentSourceDemultiplexer
    {
        private static final Logger LOG = LoggerFactory.getLogger(ContentSourceDemultiplexer.class);

        private final Content.Source originalContentSource;
        private final ContentSource[] demultiplexerContentSources;
        private final AtomicBiInteger counters = new AtomicBiInteger(); // HI = failures; LO = demands

        private ContentSourceDemultiplexer(Content.Source originalContentSource, int size)
        {
            if (size < 2)
                throw new IllegalArgumentException("Demultiplexer can only be used with a size >= 2");

            this.originalContentSource = originalContentSource;
            demultiplexerContentSources = new ContentSource[size];
            for (int i = 0; i < size; i++)
            {
                demultiplexerContentSources[i] = new ContentSource(i);
            }
            if (LOG.isDebugEnabled())
                LOG.debug("Using demultiplexer with a size of {}", size);
        }

        public Content.Source contentSource(int index)
        {
            return demultiplexerContentSources[index];
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
            for (ContentSource demultiplexerContentSource : demultiplexerContentSources)
            {
                demultiplexerContentSource.onChunk(chunk);
            }
        }

        private void registerFailure(Throwable failure)
        {
            while (true)
            {
                long encoded = counters.get();
                int failures = AtomicBiInteger.getHi(encoded) + 1;
                int demands = AtomicBiInteger.getLo(encoded);
                if (demands == demultiplexerContentSources.length - failures)
                    demands = 0;
                if (counters.compareAndSet(encoded, failures, demands))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Registered failure; failures={} demands={}", failures, demands);
                    if (failures == demultiplexerContentSources.length)
                        originalContentSource.fail(failure);
                    else if (demands == 0)
                        originalContentSource.demand(this::onDemandCallback);
                    break;
                }
            }
        }

        private void registerDemand()
        {
            while (true)
            {
                long encoded = counters.get();
                int failures = AtomicBiInteger.getHi(encoded);
                int demands = AtomicBiInteger.getLo(encoded) + 1;
                if (demands == demultiplexerContentSources.length - failures)
                    demands = 0;
                if (counters.compareAndSet(encoded, failures, demands))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Registered demand; failures={} demands={}", failures, demands);
                    if (demands == 0)
                        originalContentSource.demand(this::onDemandCallback);
                    break;
                }
            }
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
                    return "ALREADY_READ_CHUNK";
                }
            };
            private final int index;
            private final AtomicReference<Runnable> demandCallbackRef = new AtomicReference<>();
            private volatile Content.Chunk chunk;

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
                    this.chunk = chunk.slice();
                else if (!currentChunk.isLast())
                    throw new IllegalStateException("Cannot overwrite chunk");
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
                if (result != null && !result.isTerminal())
                    chunk = ALREADY_READ_CHUNK;
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
                    registerDemand();
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
                registerFailure(failure);
            }
        }
    }
}
