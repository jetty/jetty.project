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
import java.nio.ByteBuffer;
import javax.servlet.ServletInputStream;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

/**
 * <p> This would be an interface if ServletInputStream was an interface too.</p>
 * <p> While this class is-a Runnable, it should never be dispatched in it's own thread. It is a runnable only so that the calling thread can use {@link
 * ContextHandler#handle(Runnable)} to setup classloaders etc. </p>
 */
public abstract class HttpInput extends ServletInputStream implements Runnable
{

    public abstract void recycle();

    /**
     * @return The current Interceptor, or null if none set
     */
    public abstract Interceptor getInterceptor();

    /**
     * Set the interceptor.
     *
     * @param interceptor The interceptor to use.
     */
    public abstract void setInterceptor(Interceptor interceptor);

    /**
     * Set the {@link org.eclipse.jetty.server.HttpInput.Interceptor}, chaining it to the existing one if
     * an {@link org.eclipse.jetty.server.HttpInput.Interceptor} is already set.
     *
     * @param interceptor the next {@link org.eclipse.jetty.server.HttpInput.Interceptor} in a chain
     */
    public abstract void addInterceptor(Interceptor interceptor);

    /**
     * Called by channel when asynchronous IO needs to produce more content
     *
     * @throws IOException if unable to produce content
     */
    public abstract void asyncReadProduce() throws IOException;

    /**
     * Adds some content to this input stream.
     *
     * @param content the content to add
     */
    public abstract void addContent(Content content);

    public abstract boolean hasContent();

    public abstract void unblock();

    public abstract long getContentLength();

    public long getContentReceived()
    {
        return getContentLength();
    }

    /**
     * This method should be called to signal that an EOF has been detected before all the expected content arrived.
     * <p>
     * Typically this will result in an EOFException being thrown from a subsequent read rather than a -1 return.
     *
     * @return true if content channel woken for read
     */
    public abstract boolean earlyEOF();

    /**
     * This method should be called to signal that all the expected content arrived.
     *
     * @return true if content channel woken for read
     */
    public abstract boolean eof();

    public abstract boolean consumeAll();

    public abstract boolean isError();

    public abstract boolean isAsync();

    public abstract boolean onIdleTimeout(Throwable x);

    public abstract boolean failed(Throwable x);

    @Override
    public abstract int read(byte[] b, int off, int len) throws IOException;

    @Override
    public abstract int available() throws IOException;

    public interface Interceptor
    {
        /**
         * @param content The content to be intercepted.
         * The content will be modified with any data the interceptor consumes, but there is no requirement
         * that all the data is consumed by the interceptor.
         * @return The intercepted content or null if interception is completed for that content.
         */
        Content readFrom(Content content);
    }

    public static class Content implements Callback
    {
        protected final ByteBuffer _content;

        public Content(ByteBuffer content)
        {
            _content = content;
        }

        public ByteBuffer getByteBuffer()
        {
            return _content;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        public int get(byte[] buffer, int offset, int length)
        {
            length = Math.min(_content.remaining(), length);
            _content.get(buffer, offset, length);
            return length;
        }

        public int skip(int length)
        {
            length = Math.min(_content.remaining(), length);
            _content.position(_content.position() + length);
            return length;
        }

        public boolean hasContent()
        {
            return _content.hasRemaining();
        }

        public int remaining()
        {
            return _content.remaining();
        }

        public boolean isEmpty()
        {
            return !_content.hasRemaining();
        }

        @Override
        public String toString()
        {
            return String.format("Content@%x{%s}", hashCode(), BufferUtil.toDetailString(_content));
        }
    }

}
