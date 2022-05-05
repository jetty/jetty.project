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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferAccumulator;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Blocking;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.CharsetStringBuilder;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.MathUtils;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * The Content abstract is based on what is already used in several places.
 * It allows EOF and Error flows to be unified with content data. This allows
 * the semantics of multiple methods like flush, close, onError, etc. to be
 * included in the read/write APIs.
 */
public interface ContentOLD
{
    ByteBuffer getByteBuffer();

    default boolean isSpecial()
    {
        return false;
    }

    default void checkError() throws IOException
    {
    }

    default void release()
    {
    }

    default boolean isLast()
    {
        return false;
    }

    default int remaining()
    {
        ByteBuffer b = getByteBuffer();
        return b == null ? 0 : b.remaining();
    }

    default boolean hasRemaining()
    {
        ByteBuffer b = getByteBuffer();
        return b != null && b.hasRemaining();
    }

    default boolean isEmpty()
    {
        return !hasRemaining();
    }

    default int fill(byte[] buffer, int offset, int length)
    {
        ByteBuffer b = getByteBuffer();
        if (b == null || !b.hasRemaining())
            return 0;
        length = Math.min(length, b.remaining());
        b.get(buffer, offset, length);
        return length;
    }

    static Throwable getError(ContentOLD content)
    {
        return (content instanceof Error error) ? error.getCause() : null;
    }

    static ContentOLD from(ByteBuffer buffer)
    {
        return from(buffer, false);
    }

    static ContentOLD from(ByteBuffer buffer, boolean last)
    {
        return new Buffer(buffer, last);
    }

    static ContentOLD last(ContentOLD content)
    {
        if (content == null)
            return EOF;
        if (content.isLast())
            return content;
        return new Abstract(content.isSpecial(), true)
        {
            @Override
            public ByteBuffer getByteBuffer()
            {
                return content.getByteBuffer();
            }

            @Override
            public void release()
            {
                content.release();
            }
        };
    }

    /**
     * Compute the next content from the current content.
     * @param content The current content
     * @return The next content if known, else null
     */
    static ContentOLD next(ContentOLD content)
    {
        if (content != null)
        {
            if (content instanceof Trailers)
                return EOF;
            if (content.isSpecial())
                return content;
            if (content.isLast())
                return EOF;
        }
        return null;
    }

    static int skip(ContentOLD content, int length)
    {
        ByteBuffer byteBuffer = content.getByteBuffer();
        length = Math.min(byteBuffer.remaining(), length);
        byteBuffer.position(byteBuffer.position() + length);
        return length;
    }

    abstract class Abstract implements ContentOLD
    {
        private final boolean _special;
        private final boolean _last;

        protected Abstract(boolean special, boolean last)
        {
            _special = special;
            _last = last;
        }

        @Override
        public boolean isSpecial()
        {
            return _special;
        }

        @Override
        public boolean isLast()
        {
            return _last;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return null;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{%s,s=%b,l=%b}",
                getClass().getName(),
                hashCode(),
                BufferUtil.toDetailString(getByteBuffer()),
                isSpecial(),
                isLast());
        }
    }

    class Buffer extends Abstract
    {
        private final ByteBuffer _buffer;

        public Buffer(ByteBuffer buffer)
        {
            this(buffer, false);
        }

        public Buffer(ByteBuffer buffer, boolean last)
        {
            super(false, last);
            _buffer = buffer;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return _buffer;
        }
    }

    ContentOLD EOF = new Abstract(true, true)
    {
        @Override
        public boolean isLast()
        {
            return true;
        }

        @Override
        public String toString()
        {
            return "EOF";
        }
    };

    class Error extends Abstract
    {
        private final Throwable _cause;

        public Error(Throwable cause)
        {
            this (cause, true);
        }

        public Error(Throwable cause, boolean last)
        {
            super(true, last);
            _cause = cause == null ? new IOException("unknown") : cause;
        }

        @Override
        public void checkError() throws IOException
        {
            throw IO.rethrow(_cause);
        }

        public Throwable getCause()
        {
            return _cause;
        }

        @Override
        public String toString()
        {
            return _cause.toString();
        }
    }

    class Trailers extends Abstract
    {
        private final HttpFields _trailers;

        public Trailers(HttpFields trailers)
        {
            super(true, true);
            _trailers = trailers;
        }

        public HttpFields getTrailers()
        {
            return _trailers;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{t=%d,s=%b,l=%b}",
                getClass().getName(),
                hashCode(),
                _trailers.size(),
                isSpecial(),
                isLast());
        }
    }

    /**
     * Content Reader contract.
     */
    interface Reader
    {
        Content.Chunk readContent();

        void demandContent(Runnable onContentAvailable);
    }

    static void readAllBytes(Reader reader, Promise<ByteBuffer> content)
    {
        ByteBufferAccumulator out = new ByteBufferAccumulator();
        Runnable onDataAvailable = new Runnable()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    Content.Chunk c = reader.readContent();
                    if (c == null)
                    {
                        reader.demandContent(this);
                        return;
                    }
                    if (c.hasRemaining())
                    {
                        out.copyBuffer(c.getByteBuffer());
                        c.release();
                    }
                    if (c.isLast())
                    {
                        if (c instanceof ContentOLD.Error)
                            content.failed(((ContentOLD.Error)c).getCause());
                        else
                            content.succeeded(out.takeByteBuffer());
                        return;
                    }
                }
            }
        };
        onDataAvailable.run();
    }

    /**
     * Blocking read of all content
     * @param reader The read to read from
     * @return The byte buffer containing the read bytes
     * @throws InterruptedException If interrupted
     * @throws IOException If there was a problem reading.
     */
    static ByteBuffer readAllBytes(Reader reader) throws InterruptedException, IOException
    {
        Promise.Completable<ByteBuffer> result = new Promise.Completable<>();
        readAllBytes(reader, result);
        try
        {
            return result.get();
        }
        catch (ExecutionException e)
        {
            throw IO.rethrow(e.getCause());
        }
    }

    static void readAll(Reader reader, Promise<String> content)
    {
        readAll(reader, content, StandardCharsets.UTF_8);
    }

    static void readAll(Reader reader, Promise<String> content, Charset charset)
    {
        CharsetStringBuilder builder = CharsetStringBuilder.forCharset(charset);
        Runnable onDataAvailable = new Runnable()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    Content.Chunk c = reader.readContent();
                    if (c == null)
                    {
                        reader.demandContent(this);
                        return;
                    }
                    if (c.hasRemaining())
                    {
                        builder.append(c.getByteBuffer());
                        c.release();
                    }
                    if (c.isLast())
                    {
                        if (c instanceof ContentOLD.Error)
                            content.failed(((ContentOLD.Error)c).getCause());
                        else
                            content.succeeded(builder.toString());
                        return;
                    }
                }
            }
        };
        onDataAvailable.run();
    }

    static String readAll(Reader reader) throws InterruptedException, IOException
    {
        return readAll(reader, StandardCharsets.UTF_8);
    }

    static String readAll(Reader reader, Charset charset) throws InterruptedException, IOException
    {
        Promise.Completable<String> result = new Promise.Completable<>();
        readAll(reader, result, charset);
        try
        {
            return result.get();
        }
        catch (ExecutionException e)
        {
            throw IO.rethrow(e.getCause());
        }
    }

    static void consumeAll(Reader reader) throws Exception
    {
        try (Blocking.Callback callback = Blocking.callback())
        {
            consumeAll(reader, callback);
            callback.block();
        }
    }

    static void consumeAll(Reader reader, Callback callback)
    {
        new Invocable.Task()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    Content.Chunk content = reader.readContent();
                    if (content == null)
                    {
                        reader.demandContent(this);
                        return;
                    }

                    if (content instanceof Error error)
                    {
                        callback.failed(error.getCause());
                        return;
                    }

                    content.release();
                    if (content.isLast())
                    {
                        callback.succeeded();
                        return;
                    }
                }
            }

            @Override
            public InvocationType getInvocationType()
            {
                return Invocable.getInvocationType(callback);
            }
        }.run();
    }

    abstract class Processor implements Reader
    {
        private final Reader _reader;

        protected Processor(Reader reader)
        {
            _reader = reader;
        }

        public Reader getReader()
        {
            return _reader;
        }
    }

    class ContentPublisher implements Flow.Publisher<Content.Chunk>
    {
        private final Reader _reader;
        private final AtomicReference<TheSubscription> _theSubscription = new AtomicReference<>();

        public ContentPublisher(Reader reader)
        {
            _reader = reader;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super Content.Chunk> subscriber)
        {
            TheSubscription theSubscription = new TheSubscription(subscriber);
            if (_theSubscription.compareAndSet(null, theSubscription))
                subscriber.onSubscribe(theSubscription);
            else
                subscriber.onError(new IllegalStateException("Already subscribed"));
        }

        private class TheSubscription implements Flow.Subscription, Runnable
        {
            private final Flow.Subscriber<? super Content.Chunk> _subscriber;
            private final AtomicLong _demand = new AtomicLong();

            private TheSubscription(Flow.Subscriber<? super Content.Chunk> subscriber)
            {
                _subscriber = subscriber;
            }

            @Override
            public void request(long n)
            {
                if (_demand.getAndUpdate(d -> MathUtils.cappedAdd(d, n)) == 0)
                    _reader.demandContent(this);
            }

            @Override
            public void cancel()
            {
                // TODO
            }

            @Override
            public void run()
            {
                while (true)
                {
                    Content.Chunk content = _reader.readContent();
                    if (content == null)
                    {
                        _reader.demandContent(this);
                        return;
                    }

                    if (content.hasRemaining())
                    {
                        long demand = _demand.decrementAndGet();
                        _subscriber.onNext(content);
                        if (demand == 0 && !content.isLast())
                            return;
                    }

                    if (content instanceof ContentOLD.Error)
                    {
                        _subscriber.onError(((ContentOLD.Error)content).getCause());
                        return;
                    }

                    if (content.isLast())
                    {
                        _subscriber.onComplete();
                        return;
                    }
                }
            }
        }
    }

    class FieldPublisher implements Flow.Publisher<Fields.Field>
    {
        private final Reader _reader;
        private final AtomicReference<TheSubscription> _theSubscription = new AtomicReference<>();

        public FieldPublisher(Reader reader)
        {
            _reader = reader;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super Fields.Field> subscriber)
        {
            TheSubscription theSubscription = new TheSubscription(subscriber);
            if (_theSubscription.compareAndSet(null, theSubscription))
                subscriber.onSubscribe(theSubscription);
            else
                subscriber.onError(new IllegalStateException("Already subscribed"));
        }

        private class TheSubscription implements Flow.Subscription, Runnable
        {
            private final Flow.Subscriber<? super Fields.Field> _subscriber;
            private final AutoLock _lock = new AutoLock();
            private boolean _iterating;
            private long _demand;
            private Content.Chunk _content;
            private Utf8StringBuilder _builder = new Utf8StringBuilder(); // TODO use CharsetStringBuilder
            private String _name;
            private boolean _last;

            private TheSubscription(Flow.Subscriber<? super Fields.Field> subscriber)
            {
                _subscriber = subscriber;
            }

            @Override
            public void request(long n)
            {
                if (n == 0)
                    return;
                if (n < 0)
                    throw new IllegalArgumentException("must be positive");

                boolean run;
                try (AutoLock ignored = _lock.lock())
                {
                    long demand = _demand;
                    _demand = MathUtils.cappedAdd(demand, n);
                    if (demand > 0 || _iterating)
                        return;

                    run = _content != null;
                    if (run)
                        _iterating = true;
                }
                if (run)
                    run();
                else
                    _reader.demandContent(this);
            }

            @Override
            public void cancel()
            {
                // TODO
            }

            @Override
            public void run()
            {
                boolean complete = false;
                boolean demandContent = false;
                Throwable error = null;

                while (true)
                {
                    Fields.Field field;
                    try (AutoLock ignored = _lock.lock())
                    {
                        if (_last)
                        {
                            complete = true;
                            _iterating = false;
                            break;
                        }

                        if (_demand == 0)
                        {
                            _iterating = false;
                            break;
                        }

                        if (_content == null)
                        {
                            _content = _reader.readContent();
                            if (_content == null)
                            {
                                demandContent = !_last;
                                _iterating = false;
                                break;
                            }

                            _last |= _content.isLast();

                            if (_content instanceof Error)
                            {
                                error = ((ContentOLD.Error)_content).getCause();
                                _content.release();
                                _iterating = false;
                                break;
                            }
                        }

                        field = parse(_content.getByteBuffer(), _last);
                        if (field == null)
                        {
                            if (!_content.hasRemaining())
                            {
                                _content.release();
                                _content = null;
                            }
                            continue;
                        }

                        _demand--;
                        _iterating = true;
                    }
                    _subscriber.onNext(field);
                }

                if (error != null)
                    _subscriber.onError(error);
                else if (complete)
                    _subscriber.onComplete();
                else if (demandContent)
                    _reader.demandContent(this);
            }

            protected Fields.Field parse(ByteBuffer buffer, boolean last)
            {
                String value = null;
                while (BufferUtil.hasContent(buffer))
                {
                    byte b = buffer.get();
                    if (_name == null)
                    {
                        if (b == '=')
                        {
                            _name = _builder.toString();
                            _builder.reset();
                        }
                        else
                            _builder.append(b);
                    }
                    else
                    {
                        if (b == '&')
                        {
                            value = _builder.toString();
                            _builder.reset();
                            break;
                        }
                        else
                            _builder.append(b);
                    }
                }

                if (_name != null)
                {
                    if (value == null && last)
                    {
                        value = _builder.toString();
                        _builder.reset();
                    }

                    if (value != null)
                    {
                        Fields.Field field = new Fields.Field(_name, value);
                        _name = null;
                        return field;
                    }
                }

                return null;
            }
        }
    }

    // TODO test and document
    static InputStream asInputStream(Reader reader)
    {
        return new InputStream()
        {
            private final Blocking.Shared _blocking = new Blocking.Shared();
            private final byte[] _oneByte = new byte[1];
            private Content.Chunk _content;

            void blockForContent() throws IOException
            {
                while (true)
                {
                    if (_content != null)
                    {
                        if (_content instanceof Error error)
                            throw IO.rethrow(error.getCause());
                        if (_content.hasRemaining() || _content.isLast())
                            return;
                        _content.release();
                    }

                    _content = reader.readContent();

                    if (_content == null)
                    {
                        try (Blocking.Runnable blocking = _blocking.runnable())
                        {
                            reader.demandContent(blocking);
                            blocking.block();
                        }
                        catch (IOException e)
                        {
                            throw e;
                        }
                        catch (Exception e)
                        {
                            throw new IOException(e);
                        }
                    }
                }
            }

            @Override
            public int read() throws IOException
            {
                blockForContent();
                if (_content.isLast() && !_content.hasRemaining())
                    return -1;
                _content.get(_oneByte, 0, 1);
                if (!_content.hasRemaining())
                {
                    _content.release();
                    _content = null;
                }
                return _oneByte[0];
            }

            @Override
            public int read(byte[] b) throws IOException
            {
                return read(b, 0, b.length);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException
            {
                blockForContent();
                if (_content.isLast() && !_content.hasRemaining())
                    return -1;
                int l = _content.get(b, off, len);
                if (!_content.hasRemaining())
                {
                    _content.release();
                    _content = null;
                }
                return l;
            }

            @Override
            public int available()
            {
                if (_content != null)
                {
                    if (_content.isLast() && !_content.hasRemaining())
                        return -1;
                    return _content.getByteBuffer().remaining();
                }
                return 0;
            }

            @Override
            public void close()
            {
                if (_content != null && _content.hasRemaining())
                    _content.release();
                _content = Content.Chunk.EOF;
            }
        };
    }

    interface Writer
    {
        void write(boolean last, Callback callback, ByteBuffer... content);
    }

    static OutputStream asOutputStream(Writer writer)
    {
        return new OutputStream()
        {
            private final Blocking.Shared _blocking = new Blocking.Shared();

            @Override
            public void write(int b) throws IOException
            {
                write(new byte[]{(byte)b}, 0, 1);
            }

            @Override
            public void write(byte[] b) throws IOException
            {
                write(b, 0, b.length);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException
            {
                try (Blocking.Callback callback = _blocking.callback())
                {
                    writer.write(false, callback, ByteBuffer.wrap(b, off, len));
                    callback.block();
                }
                catch (IOException e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    throw new IOException(e);
                }
            }

            @Override
            public void flush() throws IOException
            {
                try (Blocking.Callback callback = _blocking.callback())
                {
                    writer.write(false, callback);
                    callback.block();
                }
                catch (IOException e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    throw new IOException(e);
                }
            }

            @Override
            public void close() throws IOException
            {
                try (Blocking.Callback callback = _blocking.callback())
                {
                    writer.write(true, callback);
                    callback.block();
                }
                catch (IOException e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    throw new IOException(e);
                }
            }
        };
    }

    static void copy(Reader reader, Writer writer, Callback callback)
    {
        new Copy(reader, writer, null, callback).run();
    }

    static void copy(Reader reader, Writer writer, Consumer<HttpFields> trailers, Callback callback)
    {
        new Copy(reader, writer, trailers, callback).run();
    }

    class Copy implements Runnable, Invocable, Callback
    {
        private static final Content.Chunk ITERATING = Content.Chunk.from(BufferUtil.EMPTY_BUFFER, false);
        private final Reader _reader;
        private final Writer _writer;
        private final Consumer<HttpFields> _trailers;
        private final Callback _callback;
        private final AtomicReference<Content.Chunk> _chunk = new AtomicReference<>();

        Copy(Reader provider, Writer writer, Consumer<HttpFields> trailers, Callback callback)
        {
            _reader = provider;
            _writer = writer;
            _trailers = trailers;
            _callback = callback;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        @Override
        public void run()
        {
            while (true)
            {
                Content.Chunk chunk = _reader.readContent();
                if (chunk == null)
                {
                    _reader.demandContent(this);
                    return;
                }

                if (chunk instanceof Error error)
                {
                    _callback.failed(error.getCause());
                    return;
                }

                if (chunk instanceof ContentOLD.Trailers trailers && _trailers != null)
                    _trailers.accept(trailers.getTrailers());

                if (!chunk.hasRemaining() && chunk.isLast())
                {
                    chunk.release();
                    _callback.succeeded();
                    return;
                }

                _chunk.set(ITERATING);
                _writer.write(chunk.isLast(), this, chunk.getByteBuffer());
                if (_chunk.compareAndSet(ITERATING, chunk))
                    return;
                chunk.release();
            }
        }

        @Override
        public void succeeded()
        {
            Content.Chunk chunk = _chunk.getAndSet(null);
            if (chunk == ITERATING)
                return;
            chunk.release();
            run();
        }

        @Override
        public void failed(Throwable x)
        {
            _callback.failed(x);
        }
    }
}
