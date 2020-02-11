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
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import javax.servlet.ReadListener;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.AbstractHttpInput.Eof;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.AutoLock;

public class HttpInputOverFCGI extends HttpInput
{
    private static final Logger LOG = Log.getLogger(HttpInputOverFCGI.class);

    private final byte[] _oneByteBuffer = new byte[1];
    private final HttpChannelState _channelState;

    private final AutoLock _contentLock = new AutoLock();
    private final Condition _contentLockCondition = _contentLock.newCondition();

    private final ContentProducer _contentProducer;
    private Eof _eof = Eof.NOT_YET;
    private Throwable _error;
    private ReadListener _readListener;
    private long _firstByteTimeStamp = Long.MIN_VALUE;

    public HttpInputOverFCGI(HttpChannelState state)
    {
        _channelState = state;
        _contentProducer = new ContentProducer();
    }

    /* HttpInput */

    @Override
    public void recycle()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("recycle");
            _contentProducer.recycle();
            _eof = Eof.NOT_YET;
            _error = null;
            _readListener = null;
            _firstByteTimeStamp = Long.MIN_VALUE;
        }
    }

    @Override
    public Interceptor getInterceptor()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return _contentProducer.getInterceptor();
        }
    }

    @Override
    public void setInterceptor(Interceptor interceptor)
    {
        try (AutoLock lock = _contentLock.lock())
        {
            _contentProducer.setInterceptor(interceptor);
        }
    }

    @Override
    public void addInterceptor(Interceptor interceptor)
    {
        try (AutoLock lock = _contentLock.lock())
        {
            _contentProducer.addInterceptor(interceptor);
        }
    }

    @Override
    public void asyncReadProduce()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("asyncReadProduce {}", _contentProducer);
            _contentProducer.produceRawContent();
        }
    }

    @Override
    public boolean addContent(Content content)
    {
        try (AutoLock lock = _contentLock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("addContent {} {}", content, _contentProducer);
            if (_firstByteTimeStamp == Long.MIN_VALUE)
            {
                _firstByteTimeStamp = System.nanoTime();
                if (_firstByteTimeStamp == Long.MIN_VALUE)
                    _firstByteTimeStamp++;
            }
            long contentArrived = _contentProducer.addContent(content);
            long requestContentLength = _channelState.getHttpChannel().getRequest().getContentLengthLong();
            // return false to make the parser go on, true to make it stop
            // -> tell the parser to stop adding content, unless we have read everything
            boolean stopParsing = requestContentLength == -1 || contentArrived < requestContentLength;
            if (isAsync())
                _channelState.onContentAdded();
            return stopParsing;
        }
    }

    @Override
    public boolean hasContent()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return _contentProducer.hasRawContent();
        }
    }

    @Override
    public void unblock()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("signalling blocked thread to wake up");
            _contentLockCondition.signal();
        }
    }

    @Override
    public long getContentLength()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return _contentProducer.getRawContentArrived();
        }
    }

    @Override
    public boolean earlyEOF()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("received early EOF");
            _eof = Eof.EARLY_EOF;
            if (isAsync())
                return _channelState.onContentAdded();
            unblock();
            return false;
        }
    }

    @Override
    public boolean eof()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("received EOF");
            _eof = Eof.EOF;
            if (isAsync())
                return _channelState.onContentAdded();
            unblock();
            return false;
        }
    }

    @Override
    public boolean consumeAll()
    {
        try (AutoLock lock = _contentLock.lock())
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
    }

    @Override
    public boolean isError()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return _error != null;
        }
    }

    @Override
    public boolean isAsync()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return _readListener != null;
        }
    }

    @Override
    public boolean onIdleTimeout(Throwable x)
    {
        try (AutoLock lock = _contentLock.lock())
        {
            boolean neverDispatched = _channelState.isIdle();
            boolean waitingForContent = !_contentProducer.hasTransformedContent() && !_eof.isEof();
            if ((waitingForContent || neverDispatched) && !isError())
            {
                x.addSuppressed(new Throwable("HttpInput idle timeout"));
                _error = x;
                if (isAsync())
                    return _channelState.onContentAdded();
                unblock();
            }
            return false;
        }
    }

    @Override
    public boolean failed(Throwable x)
    {
        try (AutoLock lock = _contentLock.lock())
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
    }

    /* ServletInputStream */

    @Override
    public boolean isFinished()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            boolean finished = !_contentProducer.hasRawContent() && _eof.isConsumed();
            if (LOG.isDebugEnabled())
                LOG.debug("isFinished? {}", finished);
            return finished;
        }
    }

    @Override
    public boolean isReady()
    {
        try (AutoLock lock = _contentLock.lock())
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
    }

    @Override
    public void setReadListener(ReadListener readListener)
    {
        try (AutoLock lock = _contentLock.lock())
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
        try (AutoLock lock = _contentLock.lock())
        {
            // Calculate minimum request rate for DOS protection
            long minRequestDataRate = _channelState.getHttpChannel().getHttpConfiguration().getMinRequestDataRate();
            if (minRequestDataRate > 0 && _firstByteTimeStamp != Long.MIN_VALUE)
            {
                long period = System.nanoTime() - _firstByteTimeStamp;
                if (period > 0)
                {
                    long minimumData = minRequestDataRate * TimeUnit.NANOSECONDS.toMillis(period) / TimeUnit.SECONDS.toMillis(1);
                    if (_contentProducer.getRawContentArrived() < minimumData)
                    {
                        BadMessageException bad = new BadMessageException(HttpStatus.REQUEST_TIMEOUT_408,
                            String.format("Request content data rate < %d B/s", minRequestDataRate));
                        if (_channelState.isResponseCommitted())
                            _channelState.getHttpChannel().abort(bad);
                        throw bad;
                    }
                }
            }

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
    }

    @Override
    public int available()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            int available = _contentProducer.available();
            if (LOG.isDebugEnabled())
                LOG.debug("available = {}", available);
            return available;
        }
    }

    private void blockForContent()
    {
        try
        {
            _channelState.getHttpChannel().onBlockWaitForContent(); // switches on fill interested
            if (LOG.isDebugEnabled())
                LOG.debug("waiting for signal to wake up");
            _contentLockCondition.await();
            if (LOG.isDebugEnabled())
                LOG.debug("signalled to wake up");
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
        try (AutoLock lock = _contentLock.lock())
        {
            if (!_contentProducer.hasRawContent())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("running has no raw content; error: {}, EOF = {}", _error, _eof);
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
                        if (LOG.isDebugEnabled())
                            LOG.debug("running failed onAllDataRead", x);
                        _readListener.onError(x);
                    }
                }
                // else: !hasContent() && !error && !EOF -> no-op
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("running has raw content");
                try
                {
                    _readListener.onDataAvailable();
                }
                catch (Throwable x)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("running failed onDataAvailable", x);
                    _readListener.onError(x);
                }
            }
        }
    }

    private static class ContentProducer
    {
        // Note: _rawContent can never be null for as long as _transformedContent is not null.
        private final Queue<Content> _rawContentQueue = new LinkedBlockingQueue<>();
        private HttpInput.Content _currentRawContent;
        private HttpInput.Content _transformedContent;
        private long _rawContentArrived;
        private HttpInput.Interceptor _interceptor;

        void recycle()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("recycle {}", this);
            if (_transformedContent == _currentRawContent)
                _transformedContent = null;
            if (_transformedContent != null && !_transformedContent.isEmpty())
                _transformedContent.failed(null);
            _transformedContent = null;
            if (_currentRawContent != null && !_currentRawContent.isEmpty())
                _currentRawContent.failed(null);
            _currentRawContent = null;
            _rawContentQueue.forEach(c -> c.failed(null));
            _rawContentQueue.clear();
            _rawContentArrived = 0L;
            if (_interceptor instanceof Destroyable)
                ((Destroyable)_interceptor).destroy();
            _interceptor = null;
        }

        //TODO: factor out similarities with read and hasTransformedContent
        int available()
        {
            if (_transformedContent != null)
                return _transformedContent.remaining();
            if (_currentRawContent == null)
                produceRawContent();
            produceTransformedContent();
            return _transformedContent == null ? 0 : _transformedContent.remaining();
        }

        long getRawContentArrived()
        {
            return _rawContentArrived;
        }

        boolean hasRawContent()
        {
            if (_currentRawContent == null)
                produceRawContent();
            return _currentRawContent != null;
        }

        boolean hasTransformedContent()
        {
            if (_transformedContent != null)
                return true;
            if (_currentRawContent == null)
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

            _rawContentQueue.offer(content);
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
                if (_transformedContent != _currentRawContent)
                    _transformedContent.succeeded();
                _transformedContent = null;
            }

            // don't bother transforming content, directly deplete the raw one
            while (true)
            {
                if (_currentRawContent == null)
                    produceRawContent();
                if (_currentRawContent == null)
                    break;

                _currentRawContent.skip(_currentRawContent.remaining());
                _currentRawContent.succeeded();
                _currentRawContent = null;
            }
        }

        int read(byte[] b, int off, int len)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} read", this);
            while (_transformedContent == null)
            {
                if (_currentRawContent == null)
                {
                    produceRawContent();
                    if (_currentRawContent == null)
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
            _currentRawContent = _rawContentQueue.poll();
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
                if (_currentRawContent != null && _currentRawContent.isEmpty())
                {
                    _currentRawContent.succeeded();
                    _currentRawContent = null;
                    _transformedContent = null;
                }
                else
                {
                    _transformedContent = _currentRawContent;
                }
            }
            else
            {
                // interceptor set
                transformContent();
                if (_transformedContent == null)
                {
                    if (_currentRawContent != null && _currentRawContent.isEmpty())
                    {
                        _currentRawContent.succeeded();
                        _currentRawContent = null;
                    }
                    else
                    {
                        _transformedContent = _currentRawContent;
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
            if (_currentRawContent == null)
                return;

            _transformedContent = _interceptor.readFrom(_currentRawContent);

            if (_transformedContent != null && _transformedContent.isEmpty())
            {
                if (_transformedContent != _currentRawContent)
                    _transformedContent.succeeded();
                _transformedContent = null;
            }
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + "[i=" + _interceptor + ",b=" + _rawContentArrived +
                ",r=" + _currentRawContent + ",t=" + _transformedContent + "]";
        }
    }
}
