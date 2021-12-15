//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferAccumulator;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.Utf8StringBuilder;

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

    /**
     * Get the next content if known from the current content
     * @return The next content, which may be null if not known, EOF or the current content if persistent
     */
    default Content next()
    {
        return isSpecial() ? this : isLast() ? Content.EOF : null;
    }

    static Content from(Content content, Content next)
    {
        if (Objects.equals(content.next(), next))
            return content;
        return new Abstract(content.isSpecial(), content.isLast())
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

            @Override
            public Content next()
            {
                if (content.next() == null)
                    return next;
                return from(content.next(), next);
            }
        };
    }

    static Content from(ByteBuffer buffer)
    {
        return () -> buffer;
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

            @Override
            public String toString()
            {
                return String.format("[%s, l=%b]", BufferUtil.toDetailString(getByteBuffer()), isLast());
            }
        };
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
            super(true, true);
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
        public Content next()
        {
            return EOF;
        }

        @Override
        public String toString()
        {
            return "TRAILERS";
        }
    }

    interface Provider
    {
        Content readContent();

        void demandContent(Runnable onContentAvailable);
    }

    // TODO should these static methods be instance methods?   They are not very buffer efficient

    static void readBytes(Provider provider, Promise<ByteBuffer> content)
    {
        ByteBufferAccumulator out = new ByteBufferAccumulator();
        Runnable onDataAvailable = new Runnable()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    Content c = provider.readContent();
                    if (c == null)
                    {
                        provider.demandContent(this);
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

    static ByteBuffer readBytes(Provider provider) throws InterruptedException, IOException
    {
        Promise.Completable<ByteBuffer> result = new Promise.Completable<>();
        readBytes(provider, result);
        try
        {
            return result.get();
        }
        catch (ExecutionException e)
        {
            throw IO.rethrow(e.getCause());
        }
    }

    static void readUtf8String(Provider provider, Promise<String> content)
    {
        Utf8StringBuilder builder = new Utf8StringBuilder();
        Runnable onDataAvailable = new Runnable()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    Content c = provider.readContent();
                    if (c == null)
                    {
                        provider.demandContent(this);
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

    static String readUtf8String(Provider provider) throws InterruptedException, IOException
    {
        Promise.Completable<String> result = new Promise.Completable<>();
        readUtf8String(provider, result);
        try
        {
            return result.get();
        }
        catch (ExecutionException e)
        {
            throw IO.rethrow(e.getCause());
        }
    }
}
