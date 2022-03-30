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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferAccumulator;
import org.eclipse.jetty.util.Blocking;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
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
public interface Content
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

    static Content from(ByteBuffer buffer)
    {
        return from(buffer, false);
    }

    static Content from(ByteBuffer buffer, boolean last)
    {
        return new Abstract(false, last)
        {
            @Override
            public ByteBuffer getByteBuffer()
            {
                return buffer;
            }
        };
    }

    static Content last(Content content)
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
    static Content next(Content content)
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

    static int skip(Content content, int length)
    {
        ByteBuffer byteBuffer = content.getByteBuffer();
        length = Math.min(byteBuffer.remaining(), length);
        byteBuffer.position(byteBuffer.position() + length);
        return length;
    }

    abstract class Abstract implements Content
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

    Content EOF = new Abstract(true, true)
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

    interface Reader
    {
        Content readContent();

        void demandContent(Runnable onContentAvailable);
    }

    // TODO should these static methods be instance methods?   They are not very buffer efficient

    static void readBytes(Reader reader, Promise<ByteBuffer> content)
    {
        ByteBufferAccumulator out = new ByteBufferAccumulator();
        Runnable onDataAvailable = new Runnable()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    Content c = reader.readContent();
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
                        if (c instanceof Content.Error)
                            content.failed(((Content.Error)c).getCause());
                        else
                            content.succeeded(out.takeByteBuffer());
                        return;
                    }
                }
            }
        };
        onDataAvailable.run();
    }

    static ByteBuffer readBytes(Reader reader) throws InterruptedException, IOException
    {
        Promise.Completable<ByteBuffer> result = new Promise.Completable<>();
        readBytes(reader, result);
        try
        {
            return result.get();
        }
        catch (ExecutionException e)
        {
            throw IO.rethrow(e.getCause());
        }
    }

    static void readUtf8String(Reader reader, Promise<String> content)
    {
        Utf8StringBuilder builder = new Utf8StringBuilder();
        Runnable onDataAvailable = new Runnable()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    Content c = reader.readContent();
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
                        if (c instanceof Content.Error)
                            content.failed(((Content.Error)c).getCause());
                        else
                            content.succeeded(builder.toString());
                        return;
                    }
                }
            }
        };
        onDataAvailable.run();
    }

    static String readUtf8String(Reader reader) throws InterruptedException, IOException
    {
        Promise.Completable<String> result = new Promise.Completable<>();
        readUtf8String(reader, result);
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

    // TODO test this
    static void consumeAll(Reader reader, Callback callback)
    {
        new Invocable.Task()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    Content content = reader.readContent();
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
                return InvocationType.NON_BLOCKING; // TODO should be easier to make a non blocking runnable
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

    class ContentPublisher implements Flow.Publisher<Content>
    {
        private final Reader _reader;
        private final AtomicReference<TheSubscription> _theSubscription = new AtomicReference<>();

        public ContentPublisher(Reader reader)
        {
            _reader = reader;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super Content> subscriber)
        {
            TheSubscription theSubscription = new TheSubscription(subscriber);
            if (_theSubscription.compareAndSet(null, theSubscription))
                subscriber.onSubscribe(theSubscription);
            else
                subscriber.onError(new IllegalStateException("Already subscribed"));
        }

        private class TheSubscription implements Flow.Subscription, Runnable
        {
            private final Flow.Subscriber<? super Content> _subscriber;
            private final AtomicLong _demand = new AtomicLong();

            private TheSubscription(Flow.Subscriber<? super Content> subscriber)
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
                    Content content = _reader.readContent();
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

                    if (content instanceof Content.Error)
                    {
                        _subscriber.onError(((Content.Error)content).getCause());
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
            private Content _content;
            private Utf8StringBuilder _builder = new Utf8StringBuilder(); // TODO only UTF8???
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
                                error = ((Content.Error)_content).getCause();
                                _content.release();
                                _iterating = false;
                                break;
                            }
                        }

                        field = parse(_content.getByteBuffer(), _last);
                        if (field == null)
                        {
                            if (_content.isEmpty())
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

    class FieldsFuture extends CompletableFuture<Fields> implements Runnable
    {
        private final Reader _reader;
        private final Fields _fields = new Fields();
        private final Utf8StringBuilder _builder = new Utf8StringBuilder(); // TODO only UTF8???
        private final int _maxFields;
        private final int _maxSize;
        private String _name;

        public FieldsFuture(Reader reader)
        {
            this(reader, -1, -1);
        }

        public FieldsFuture(Reader reader, int maxFields, int maxSize)
        {
            _reader = reader;
            _maxFields = maxFields;
            _maxSize = maxSize; // TODO implement
            run();
        }

        @Override
        public void run()
        {
            while (true)
            {
                Content content = _reader.readContent();
                if (content == null)
                {
                    _reader.demandContent(this);
                    return;
                }

                if (content instanceof Error error)
                {
                    completeExceptionally(error.getCause());
                    content.release();
                    return;
                }

                Fields.Field field = parse(content.getByteBuffer(), content.isLast());
                while (field != null)
                {
                    if (_maxFields >= 0 && _fields.getSize() >= _maxFields)
                    {
                        completeExceptionally(new IllegalStateException("Too many fields"));
                        return;
                    }
                    _fields.put(field);
                    field = parse(content.getByteBuffer(), content.isLast());
                }

                content.release();
                if (content.isLast())
                {
                    complete(_fields);
                    return;
                }
            }
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

    // TODO test and document
    static InputStream asInputStream(Reader reader)
    {
        return new InputStream()
        {
            private final Blocking.Shared _blocking = new Blocking.Shared();
            private final byte[] _oneByte = new byte[1];
            private Content _content;

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
                if (_content.isLast() && _content.isEmpty())
                    return -1;
                _content.fill(_oneByte, 0, 1);
                if (_content.isEmpty())
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
                if (_content.isLast() && _content.isEmpty())
                    return -1;
                int l = _content.fill(b, off, len);
                if (_content.isEmpty())
                {
                    _content.release();
                    _content = null;
                }
                return l;
            }

            @Override
            public int available() throws IOException
            {
                if (_content != null)
                {
                    if (_content.isLast() && _content.isEmpty())
                        return -1;
                    return _content.remaining();
                }
                return 0;
            }

            @Override
            public void close() throws IOException
            {
                if (_content != null && _content.hasRemaining())
                    _content.release();
                _content = Content.EOF;
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
        private static final Content ITERATING = new Content.Abstract(true, false){};
        private final Reader _reader;
        private final Writer _writer;
        private final Consumer<HttpFields> _trailers;
        private final Callback _callback;
        private final AtomicReference<Content> _content = new AtomicReference<>();

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
                Content content = _reader.readContent();
                if (content == null)
                {
                    _reader.demandContent(this);
                    return;
                }

                if (content instanceof Error error)
                {
                    _callback.failed(error.getCause());
                    return;
                }

                if (content instanceof Content.Trailers trailers && _trailers != null)
                    _trailers.accept(trailers.getTrailers());

                if (!content.hasRemaining() && content.isLast())
                {
                    content.release();
                    _callback.succeeded();
                    return;
                }

                _content.set(ITERATING);
                _writer.write(content.isLast(), this, content.getByteBuffer());
                if (_content.compareAndSet(ITERATING, content))
                    return;
                content.release();
            }
        }

        @Override
        public void succeeded()
        {
            Content content = _content.getAndSet(null);
            if (content == ITERATING)
                return;
            content.release();
            run();
        }

        @Override
        public void failed(Throwable x)
        {
            _callback.failed(x);
        }
    }
}
