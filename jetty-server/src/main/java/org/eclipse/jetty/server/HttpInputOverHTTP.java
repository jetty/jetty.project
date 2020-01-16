//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.util.Objects;
import javax.servlet.ReadListener;

import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

// tests used: RequestTest, PartialRFC2616Test, AsyncRequestReadTest, AsyncIOServletTest, GzipHandlerTest
public class HttpInputOverHTTP extends HttpInput
{
    private static final Logger LOG = Log.getLogger(HttpInputOverHTTP.class);

    private final byte[] _oneByteBuffer = new byte[1];
    private final HttpChannelState _channelState;

    private final NotifyingSemaphore _semaphore = new NotifyingSemaphore();

    // TODO: think about thread visibility of the below variables
    private final ContentProducer _contentProducer;
    private Eof _eof = Eof.NOT_YET;
    private Throwable _error;
    private ReadListener _readListener;

    public HttpInputOverHTTP(HttpChannelState state)
    {
        _channelState = state;
        _contentProducer = new ContentProducer(() -> ((HttpConnection)state.getHttpChannel().getEndPoint().getConnection()).parseAndFillForContent());
    }

    /* HttpInput */

    @Override
    public void recycle()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("recycle");
        _contentProducer.recycle();
        _eof = Eof.NOT_YET;
        _error = null;
        _readListener = null;
    }

    @Override
    public Interceptor getInterceptor()
    {
        return _contentProducer.getInterceptor();
    }

    @Override
    public void setInterceptor(Interceptor interceptor)
    {
        _contentProducer.setInterceptor(interceptor);
    }

    @Override
    public void addInterceptor(Interceptor interceptor)
    {
        _contentProducer.addInterceptor(interceptor);
    }

    @Override
    public void asyncReadProduce()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("asyncReadProduce {}", _contentProducer);
        _contentProducer.produceRawContent();
    }

    @Override
    public boolean addContent(Content content)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("addContent {} {}", content, _contentProducer);
        long contentArrived = _contentProducer.addContent(content);
        long requestContentLength = _channelState.getHttpChannel().getRequest().getContentLengthLong();
        // return false to make the parser go on, true to make it stop
        // -> tell the parser to stop adding content, unless we have read everything
        boolean stopParsing = requestContentLength == -1 || contentArrived < requestContentLength;
        if (isAsync())
            _channelState.onContentAdded();
        return stopParsing;
    }

    @Override
    public boolean hasContent()
    {
        return _contentProducer.hasRawContent();
    }

    @Override
    public void unblock()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("signalling blocked thread to wake up");
        _semaphore.release();
    }

    @Override
    public long getContentLength()
    {
        return _contentProducer.getRawContentArrived();
    }

    @Override
    public boolean earlyEOF()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("received early EOF");
        _eof = Eof.EARLY_EOF;
        if (isAsync())
            return _channelState.onContentAdded();
        unblock();
        return false;
    }

    @Override
    public boolean eof()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("received EOF");
        _eof = Eof.EOF;
        if (isAsync())
            return _channelState.onContentAdded();
        unblock();
        return false;
    }

    @Override
    public boolean consumeAll()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("consume all");
        _contentProducer.consumeAll();
        if (_eof.isEof())
            _eof = Eof.CONSUMED_EOF;

        if (isFinished())
            return !isError();

        _eof = Eof.EARLY_EOF;
        return false;
    }

    @Override
    public boolean isError()
    {
        return _error != null;
    }

    @Override
    public boolean isAsync()
    {
        return _readListener != null;
    }

    @Override
    public boolean onIdleTimeout(Throwable x)
    {
        //TODO implement me!
        return false;
    }

    @Override
    public boolean failed(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failed " + x);
        if (_error != null)
            _error.addSuppressed(x);
        else
            _error = x;

        if (isAsync())
            return _channelState.onContentAdded();
        unblock();
        return false;
    }

    /* ServletInputStream */

    @Override
    public boolean isFinished()
    {
        boolean finished = !_contentProducer.hasRawContent() && _eof.isConsumed();
        if (LOG.isDebugEnabled())
            LOG.debug("isFinished? {}", finished);
        return finished;
    }

    @Override
    public boolean isReady()
    {
        // calling _contentProducer.hasTransformedContent() might change the _eof state, so the following test order matters
        if (_contentProducer.hasTransformedContent() || _eof.isEof())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("isReady? true");
            return true;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("isReady? false");
        _channelState.onReadUnready();
        return false;
    }

    @Override
    public void setReadListener(ReadListener readListener)
    {
        if (_readListener != null)
            throw new IllegalStateException("ReadListener already set");
        _readListener = Objects.requireNonNull(readListener);

        if (LOG.isDebugEnabled())
            LOG.debug("setReadListener error=" + _error + " eof=" + _eof + " " + _contentProducer);
        boolean woken;
        if (isError())
        {
            woken = _channelState.onReadReady();
        }
        else
        {
            if (_contentProducer.hasTransformedContent())
            {
                woken = _channelState.onReadReady();
            }
            else if (_eof.isEof())
            {
                woken = _channelState.onReadEof();
            }
            else
            {
                _channelState.onReadUnready();
                woken = false;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("setReadListener woken=" + woken);
        if (woken)
            scheduleReadListenerNotification();
    }

    private void scheduleReadListenerNotification()
    {
        HttpChannel channel = _channelState.getHttpChannel();
        channel.execute(channel);
    }

    @Override
    public int read() throws IOException
    {
        int read = read(_oneByteBuffer, 0, 1);
        if (read == 0)
            throw new IOException("unready read=0");
        return read < 0 ? -1 : _oneByteBuffer[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        while (true)
        {
            int read = _contentProducer.read(b, off, len);
            if (LOG.isDebugEnabled())
                LOG.debug("read produced {} byte(s)", read);
            if (read > 0)
                return read;

            if (LOG.isDebugEnabled())
                LOG.debug("read error = " + _error);
            if (_error != null)
                throw new IOException(_error);

            if (LOG.isDebugEnabled())
                LOG.debug("read EOF = {}", _eof);
            if (_eof.isEarly())
                throw new EofException("Early EOF");

            if (LOG.isDebugEnabled())
                LOG.debug("read async = {}", isAsync());
            if (!isAsync())
            {
                if (_eof.isEof())
                {
                    _eof = Eof.CONSUMED_EOF;
                    if (LOG.isDebugEnabled())
                        LOG.debug("read on EOF, switching to CONSUMED_EOF and returning");
                    return -1;
                }
                if (LOG.isDebugEnabled())
                    LOG.debug("read blocked");
                blockForContent();
                if (LOG.isDebugEnabled())
                    LOG.debug("read unblocked");
            }
            else
            {
                if (_eof.isEof())
                {
                    boolean wasInAsyncWait = _channelState.onReadEof();
                    if (wasInAsyncWait)
                        scheduleReadListenerNotification();
                    if (LOG.isDebugEnabled())
                        LOG.debug("async read on EOF (was in async wait? {}), switching to CONSUMED_EOF and returning", wasInAsyncWait);
                    _eof = Eof.CONSUMED_EOF;
                    return -1;
                }
                else
                {
                    //TODO returning 0 breaks the InputStream contract. Shouldn't IOException be thrown instead?
                    _channelState.getHttpChannel().onAsyncWaitForContent(); // switches on fill interested
                    return 0;
                }
            }
        }
    }

    @Override
    public int available()
    {
        int available = _contentProducer.available();
        if (LOG.isDebugEnabled())
            LOG.debug("available = {}", available);
        return available;
    }

    private void blockForContent()
    {
        try
        {
            _semaphore.acquire(() ->
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("waiting for signal to wake up");
                _channelState.getHttpChannel().onBlockWaitForContent(); // switches on fill interested if it blocks
            });
        }
        catch (Throwable x)
        {
            _channelState.getHttpChannel().onBlockWaitForContentFailure(x);
        }
    }

    /* Runnable */

    /*
     * <p> While this class is-a Runnable, it should never be dispatched in it's own thread. It is a runnable only so that the calling thread can use {@link
     * ContextHandler#handle(Runnable)} to setup classloaders etc. </p>
     */
    @Override
    public void run()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("running");
        if (!_contentProducer.hasRawContent())
        {
            if (_error != null || _eof.isEarly())
            {
                // TODO is this necessary to add here?
                _channelState.getHttpChannel().getResponse().getHttpFields().add(HttpConnection.CONNECTION_CLOSE);
                if (_error != null)
                    _readListener.onError(_error);
                else
                    _readListener.onError(new EofException("Early EOF"));
            }
            else if (_eof.isEof())
            {
                try
                {
                    _readListener.onAllDataRead();
                }
                catch (Throwable x)
                {
                    _readListener.onError(x);
                }
            }
            // else: !hasContent() && !error && !EOF -> no-op
        }
        else
        {
            try
            {
                _readListener.onDataAvailable();
            }
            catch (Throwable x)
            {
                _readListener.onError(x);
            }
        }
    }

    /**
     * An {@link Interceptor} that chains two other {@link Interceptor}s together.
     * The {@link Interceptor#readFrom(Content)} calls the previous {@link Interceptor}'s
     * {@link Interceptor#readFrom(Content)} and then passes any {@link Content} returned
     * to the next {@link Interceptor}.
     */
    static class ChainedInterceptor implements Interceptor, Destroyable
    {
        private final Interceptor _prev;
        private final Interceptor _next;

        public ChainedInterceptor(Interceptor prev, Interceptor next)
        {
            _prev = prev;
            _next = next;
        }

        public Interceptor getPrev()
        {
            return _prev;
        }

        public Interceptor getNext()
        {
            return _next;
        }

        @Override
        public Content readFrom(Content content)
        {
            return getNext().readFrom(getPrev().readFrom(content));
        }

        @Override
        public void destroy()
        {
            if (_prev instanceof Destroyable)
                ((Destroyable)_prev).destroy();
            if (_next instanceof Destroyable)
                ((Destroyable)_next).destroy();
        }
    }

    enum Eof
    {
        NOT_YET(false, false, false),
        EOF(true, false, false),
        CONSUMED_EOF(true, true, false),
        EARLY_EOF(true, false, true),
        ;

        private final boolean _eof;
        private final boolean _consumed;
        private final boolean _early;

        Eof(boolean eof, boolean consumed, boolean early)
        {
            _eof = eof;
            _consumed = consumed;
            _early = early;
        }

        public boolean isEof()
        {
            return _eof;
        }

        public boolean isConsumed()
        {
            return _consumed;
        }

        public boolean isEarly()
        {
            return _early;
        }
    }

    private static class ContentProducer
    {
        private final Runnable _rawContentProducer;
        // TODO: think about thread visibility of the below variables
        // Note: _rawContent can never be null for as long as _transformedContent is not null.
        private HttpInput.Content _rawContent;
        private HttpInput.Content _transformedContent;
        private long _rawContentArrived;
        private HttpInput.Interceptor _interceptor;

        public ContentProducer(Runnable rawContentProducer)
        {
            _rawContentProducer = rawContentProducer;
        }

        void recycle()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("recycle {}", this);
            if (_transformedContent == _rawContent)
                _transformedContent = null;
            if (_transformedContent != null && !_transformedContent.isEmpty())
                _transformedContent.failed(null);
            _transformedContent = null;
            if (_rawContent != null && !_rawContent.isEmpty())
                _rawContent.failed(null);
            _rawContent = null;
            _rawContentArrived = 0L;
            if (_interceptor instanceof Destroyable)
                ((Destroyable)_interceptor).destroy();
            _interceptor = null;
        }

        int available()
        {
            if (_rawContent == null)
                produceRawContent();
            return _rawContent == null ? 0 : _rawContent.remaining();
        }

        long getRawContentArrived()
        {
            return _rawContentArrived;
        }

        boolean hasRawContent()
        {
            return _rawContent != null;
        }

        boolean hasTransformedContent()
        {
            if (_transformedContent != null)
                return true;
            if (_rawContent == null)
                produceRawContent();
            produceTransformedContent();
            return _transformedContent != null;
        }

        HttpInput.Interceptor getInterceptor()
        {
            return _interceptor;
        }

        void setInterceptor(HttpInput.Interceptor interceptor)
        {
            this._interceptor = interceptor;
        }

        void addInterceptor(HttpInput.Interceptor interceptor)
        {
            if (_interceptor == null)
                _interceptor = interceptor;
            else
                _interceptor = new HttpInputOverHTTP.ChainedInterceptor(_interceptor, interceptor);
        }

        long addContent(HttpInput.Content content)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} addContent {}", this, content);
            if (content == null)
                throw new AssertionError("Cannot add null content");
            if (_rawContent != null)
                throw new AssertionError("Cannot add new content while current one hasn't been processed");

            _rawContent = content;
            _rawContentArrived += content.remaining();

            return _rawContentArrived;
        }

        void consumeAll()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} consumeAll", this);
            // start by depleting the current _transformedContent
            if (_transformedContent != null)
            {
                _transformedContent.skip(_transformedContent.remaining());
                if (_transformedContent != _rawContent)
                    _transformedContent.succeeded();
                _transformedContent = null;
            }

            // don't bother transforming content, directly deplete the raw one
            while (true)
            {
                if (_rawContent == null)
                    produceRawContent();
                if (_rawContent == null)
                    break;

                _rawContent.skip(_rawContent.remaining());
                _rawContent.succeeded();
                _rawContent = null;
            }
        }

        int read(byte[] b, int off, int len)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} read", this);
            while (_transformedContent == null)
            {
                if (_rawContent == null)
                {
                    produceRawContent();
                    if (_rawContent == null)
                        return 0;
                }
                produceTransformedContent();
            }

            int read = _transformedContent.get(b, off, len);
            if (_transformedContent.isEmpty())
                produceTransformedContent();

            return read;
        }

        /**
         * Call the parser so that it's going to continue parsing of the request buffer, filling it with the socket's buffer
         * if needed until either the request buffer is empty with no bytes left in the socket's buffer or {@link #addContent(Content)}
         * is called.
         */
        void produceRawContent()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} produceRawContent", this);
            _rawContentProducer.run();
        }

        /**
         * Read {@code _rawContent} and {@code _transformedContent} to produce the next non-empty content to work with and store it in {@code _transformedContent},
         * or store null in {@code _transformedContent} if there is no content to work with.
         * Depleted content gets succeeded and its field nullified, which can happen for both {@code _rawContent} and {@code _transformedContent}.
         */
        private void produceTransformedContent()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} produceTransformedContent", this);
            if (_interceptor == null)
            {
                // no interceptor set
                if (_rawContent != null && _rawContent.isEmpty())
                {
                    _rawContent.succeeded();
                    _rawContent = null;
                    _transformedContent = null;
                }
                else
                {
                    _transformedContent = _rawContent;
                }
            }
            else
            {
                // interceptor set
                transformContent();
                if (_transformedContent == null)
                {
                    if (_rawContent != null && _rawContent.isEmpty())
                    {
                        _rawContent.succeeded();
                        _rawContent = null;
                    }
                    else
                    {
                        _transformedContent = _rawContent;
                    }
                }
            }
        }

        /**
         * Read {@code _rawContent} and write {@code _transformedContent} to produce content using the interceptor.
         * The produced content is guaranteed to either be null or not empty.
         */
        private void transformContent()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} transformContent", this);
            if (_rawContent == null)
                return;

            _transformedContent = _interceptor.readFrom(_rawContent);

            if (_transformedContent != null && _transformedContent.isEmpty())
            {
                if (_transformedContent != _rawContent)
                    _transformedContent.succeeded();
                _transformedContent = null;
            }
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + "[i=" + _interceptor + ",b=" + _rawContentArrived +
                ",r=" + _rawContent + ",t=" + _transformedContent + "]";
        }
    }

    private static class NotifyingSemaphore
    {
        private int permits;

        public synchronized void acquire(Runnable onBlocking) throws InterruptedException
        {
            if (permits == 0)
                onBlocking.run();
            while (permits == 0)
                wait();
            permits--;
        }

        public synchronized void release()
        {
            permits++;
            if (permits == 1)
                notify();
        }
    }
}
