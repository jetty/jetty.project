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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;

import org.eclipse.jetty.io.content.ContentSinkOutputStream;
import org.eclipse.jetty.io.content.ContentSinkSubscriber;
import org.eclipse.jetty.io.content.ContentSourceInputStream;
import org.eclipse.jetty.io.content.ContentSourcePublisher;
import org.eclipse.jetty.io.content.ContentSourceTransformer;
import org.eclipse.jetty.io.internal.ByteBufferChunk;
import org.eclipse.jetty.io.internal.ContentCopier;
import org.eclipse.jetty.io.internal.ContentSourceByteBuffer;
import org.eclipse.jetty.io.internal.ContentSourceConsumer;
import org.eclipse.jetty.io.internal.ContentSourceString;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Promise;

/**
 * <p>Namespace class that contains the definitions of a {@link Source content source},
 * a {@link Sink content sink} and a {@link Chunk content chunk}.</p>
 */
public class Content
{
    private Content()
    {
    }

    /**
     * <p>Copies the given content source to the given content sink, notifying
     * the given callback when the copy is complete.</p>
     *
     * @param source the source to copy from
     * @param sink the sink to copy to
     * @param callback the callback to notify when the copy is complete
     */
    public static void copy(Source source, Sink sink, Callback callback)
    {
        new ContentCopier(source, sink, callback).iterate();
    }

    /**
     * <p>A source of content that can be read with a read/demand model.</p>
     * <a id="idiom"><h3>Idiomatic usage</h3></a>
     * <p>The read/demand model typical usage is the following:</p>
     * <pre>{@code
     * public void onContentAvailable() {
     *     while (true) {
     *         // Read a chunk
     *         Chunk chunk = source.read();
     *
     *         // There is no chunk, demand to be called back and exit.
     *         if (chunk == null) {
     *             source.demand(this::onContentAvailable);
     *             return;
     *         }
     *
     *         // The chunk is an error.
     *         if (chunk instanceof Chunk.Error error) {
     *             // Handle the error.
     *             Throwable cause = error.getCause();
     *             // ...
     *             return;
     *         }
     *
     *         // It's a valid chunk, consume the chunk's bytes.
     *         ByteBuffer buffer = chunk.getByteBuffer();
     *         // ...
     *
     *         // Release the chunk when it has been consumed.
     *         chunk.release();
     *     }
     * }
     * }</pre>
     */
    public interface Source
    {
        /**
         * <p>Reads, non-blocking, the whole content source into a {@link ByteBuffer}.</p>
         *
         * @param source the source to read
         * @param promise the promise to notify when the whole content has been read into a ByteBuffer.
         */
        public static void asByteBuffer(Source source, Promise<ByteBuffer> promise)
        {
            new ContentSourceByteBuffer(source, promise).run();
        }

        /**
         * <p>Reads, blocking if necessary, the whole content source into a {@link ByteBuffer}.</p>
         *
         * @param source the source to read
         * @return the ByteBuffer containing the content
         * @throws IOException if reading the content fails
         */
        public static ByteBuffer asByteBuffer(Source source) throws IOException
        {
            try
            {
                FuturePromise<ByteBuffer> promise = new FuturePromise<>();
                asByteBuffer(source, promise);
                return promise.get();
            }
            catch (Throwable x)
            {
                throw IO.rethrow(x);
            }
        }

        /**
         * <p>Reads, non-blocking, the whole content source into a {@link String}, converting the bytes
         * using the given {@link Charset}.</p>
         *
         * @param source the source to read
         * @param charset the charset to use to convert the bytes into characters
         * @param promise the promise to notify when the whole content has been converted into a String
         */
        public static void asString(Source source, Charset charset, Promise<String> promise)
        {
            new ContentSourceString(source, charset, promise).convert();
        }

        /**
         * <p>Reads, blocking if necessary, the whole content source into a {@link String}, converting
         * the bytes using the given {@link Charset}.</p>
         *
         * @param source the source to read
         * @return the String obtained from the content
         * @throws IOException if reading the content fails
         */
        public static String asString(Source source) throws IOException
        {
            try
            {
                FuturePromise<String> promise = new FuturePromise<>();
                asString(source, StandardCharsets.UTF_8, promise);
                return promise.get();
            }
            catch (Throwable x)
            {
                throw IO.rethrow(x);
            }
        }

        /**
         * <p>Wraps the given content source with an {@link InputStream}.</p>
         *
         * @param source the source to read from
         * @return an InputStream that reads from the content source
         */
        public static InputStream asInputStream(Source source)
        {
            return new ContentSourceInputStream(source);
        }

        /**
         * <p>Wraps the given content source with a {@link Flow.Publisher}.</p>
         *
         * @param source the source to read from
         * @return a Publisher that publishes chunks read from the content source
         */
        public static Flow.Publisher<Chunk> asPublisher(Source source)
        {
            return new ContentSourcePublisher(source);
        }

        /**
         * <p>Reads, non-blocking, the given content source, until either an error or EOF,
         * and discards the content.</p>
         *
         * @param source the source to read from
         * @param callback the callback to notify when the whole content has been read
         * or an error occurred while reading the content
         */
        public static void consumeAll(Source source, Callback callback)
        {
            new ContentSourceConsumer(source, callback).run();
        }

        /**
         * <p>Reads, blocking if necessary, the given content source, until either an error
         * or EOF, and discards the content.</p>
         *
         * @param source the source to read from
         * @throws IOException if reading the content fails
         */
        public static void consumeAll(Source source) throws IOException
        {
            try
            {
                FutureCallback callback = new FutureCallback();
                consumeAll(source, callback);
                callback.get();
            }
            catch (Throwable x)
            {
                throw IO.rethrow(x);
            }
        }

        /**
         * @return the content length, if known, or -1 if the content length is unknown
         */
        public default long getLength()
        {
            return -1;
        }

        /**
         * <p>Reads a chunk of content.</p>
         * <p>See how to use this method <a href="#idiom">idiomatically</a>.</p>
         * <p>The returned chunk could be:</p>
         * <ul>
         * <li>{@code null}, to signal that there isn't a chunk of content available</li>
         * <li>an {@link Chunk.Error error} instance, to signal that there was an error
         * trying to produce a chunk of content, or that the content production has been
         * {@link #fail(Throwable) failed} externally</li>
         * <li>a {@link Chunk} instance, containing the chunk of content.</li>
         * </ul>
         * <p>Once a read returns an {@link Chunk.Error error} instance, further reads
         * will continue to return the same error instance.</p>
         * <p>Once a read returns a {@link Chunk#isLast() last chunk}, further reads will
         * continue to return a last chunk (although the instance may be different).</p>
         * <p>Chunks of content that have been consumed by the content reader code must
         * be {@link Chunk#release() released}.</p>
         * <p>Concurrent reads from different threads are not recommended, as they are
         * inherently in a race condition.</p>
         * <p>Reads performed outside the invocation context of a
         * {@link #demand(Runnable) demand callback} are allowed.
         * However, reads performed with a pending demand are inherently in a
         * race condition (the thread that reads with the thread that invokes the
         * demand callback).</p>
         *
         * @return a chunk of content, possibly an error instance, or {@code null}
         * @see #demand(Runnable)
         */
        public Chunk read();

        /**
         * <p>Demands to invoke the given demand callback parameter when a chunk of content is available.</p>
         * <p>See how to use this method <a href="#idiom">idiomatically</a>.</p>
         * <p>Implementations must guarantee that calls to this method are safely reentrant, to avoid
         * stack overflows in the case of mutual recursion between the execution of the {@code Runnable}
         * callback and a call to this method.</p>
         * <p>The demand callback may be invoked <em>spuriously</em>: a subsequent call to {@link #read()}
         * may return {@code null}.</p>
         * <p>Calling this method establishes a <em>pending demand</em>, which is fulfilled when the demand
         * callback is invoked.</p>
         * <p>Calling this method when there is already a pending demand results in an
         * {@link IllegalStateException} to be thrown.</p>
         * <p>If the invocation of the demand callback throws an exception, then {@link #fail(Throwable)}
         * is called.</p>
         *
         * @param demandCallback the demand callback to invoke where there is a content chunk available
         * @throws IllegalStateException when this method is called with an existing demand
         * @see #read()
         */
        public void demand(Runnable demandCallback);

        /**
         * <p>Fails this content source, possibly failing and discarding accumulated
         * content chunks that were not yet read.</p>
         * <p>The failure may be notified to the content reader at a later time, when
         * the content reader reads a content chunk, via an {@link Chunk.Error} instance.</p>
         * <p>If {@link #read()} has returned a last chunk, this is a no operation.</p>
         * <p>Typical failure: the content being aborted by user code, or idle timeouts.</p>
         *
         * @param failure the cause of the failure
         */
        public void fail(Throwable failure);

        /**
         * <p>A wrapper of a nested source of content, that may transform the chunks obtained from
         * the nested source.</p>
         * <p>Typical implementations may split/coalesce the chunks read from the nested source,
         * or encode/decode (for example gzip) them.</p>
         * <p>Implementations should override {@link #transform(Chunk)} with the transformation
         * logic.</p>
         */
        public abstract static class Transformer extends ContentSourceTransformer
        {
            public Transformer(Content.Source rawSource)
            {
                super(rawSource);
            }

            /**
             * <p>Transforms the input chunk parameter into an output chunk.</p>
             * <p>The input chunk parameter may be {@code null}, a signal to implementations
             * to try to produce an output chunk, if possible, from previous input chunks.
             * For example, a single compressed input chunk may be transformed into multiple
             * uncompressed output chunks.</p>
             * <p>Implementations should return an {@link Chunk.Error error chunk} in case
             * of transformation errors.</p>
             * <p>Exceptions thrown by this method are equivalent to returning an error chunk.</p>
             * <p>Implementations of this method must arrange to {@link Chunk#release() release}
             * the input chunk, unless they return it as is.
             * The output chunk is released by the code that uses this Transformer.</p>
             *
             * @param rawChunk the input chunk to transform
             * @return the transformed output chunk
             */
            @Override
            protected abstract Chunk transform(Chunk rawChunk);
        }
    }

    /**
     * <p>A content sink that writes the content to its implementation (a socket, a file, etc.).</p>
     */
    public interface Sink
    {
        /**
         * <p>Wraps the given content sink with an {@link OutputStream}.</p>
         *
         * @param sink the sink to write to
         * @return an OutputStream that writes to the content sink
         */
        public static OutputStream asOutputStream(Sink sink)
        {
            return new ContentSinkOutputStream(sink);
        }

        public static Flow.Subscriber<Chunk> asSubscriber(Sink sink)
        {
            return new ContentSinkSubscriber(sink);
        }

        /**
         * <p>Writes the given {@link ByteBuffer}s, notifying the {@link Callback}
         * when the write is complete.</p>
         *
         * @param last whether the ByteBuffers are the last to write
         * @param callback the callback to notify when the write operation is complete
         * @param buffers the ByteBuffers to write
         */
        public void write(boolean last, Callback callback, ByteBuffer... buffers);

        /**
         * <p>Writes the given {@link Chunk}, notifying the {@link Callback}
         * when the write is complete.</p>
         *
         * @param chunk the chunk to write
         * @param callback the callback to notify when the write operation is complete
         */
        public default void write(Chunk chunk, Callback callback)
        {
            if (chunk instanceof Chunk.Error error)
                callback.failed(error.getCause());
            else
                write(chunk.isLast(), callback, chunk.getByteBuffer());
        }

        /**
         * <p>Writes the given {@link String}, converting it to UTF-8 bytes,
         * notifying the {@link Callback} when the write is complete.</p>
         *
         * @param last whether the String is the last to write
         * @param callback the callback to notify when the write operation is complete
         * @param utf8Content the String to write
         */
        public default void write(boolean last, Callback callback, String utf8Content)
        {
            write(last, callback, StandardCharsets.UTF_8.encode(utf8Content));
        }
    }

    /**
     * <p>A chunk of content indicating whether it is the last chunk.</p>
     * <p>Optionally, a release function may be specified (for example
     * to release the {@code ByteBuffer} back into a pool), or the
     * {@link #release()} method overridden.</p>
     */
    public interface Chunk
    {
        /**
         * <p>An empty, non-last, chunk.</p>
         */
        public static final Chunk EMPTY = ByteBufferChunk.EMPTY;
        /**
         * <p>An empty, last, chunk.</p>
         */
        public static final Content.Chunk EOF = ByteBufferChunk.EOF;

        /**
         * <p>Creates a last/non-last Chunk with the given ByteBuffer.</p>
         *
         * @param byteBuffer the ByteBuffer with the bytes of this Chunk
         * @param last whether the Chunk is the last one
         * @return a new Chunk
         */
        public static Chunk from(ByteBuffer byteBuffer, boolean last)
        {
            return from(byteBuffer, last, null);
        }

        /**
         * <p>Creates a last/non-last Chunk with the given ByteBuffer.</p>
         *
         * @param byteBuffer the ByteBuffer with the bytes of this Chunk
         * @param last whether the Chunk is the last one
         * @param releaser the code to run when this Chunk is released
         * @return a new Chunk
         */
        public static Chunk from(ByteBuffer byteBuffer, boolean last, Runnable releaser)
        {
            return new ByteBufferChunk(byteBuffer, last, releaser);
        }

        /**
         * <p>Creates an {@link Error error chunk} with the given failure.</p>
         *
         * @param failure the cause of the failure
         * @return a new Error.Chunk
         */
        public static Error from(Throwable failure)
        {
            return new Error(failure);
        }

        /**
         * <p>Returns the chunk that follows a chunk that has been consumed.</p>
         * <table>
         * <thead>
         *   <tr>
         *     <th>Input Chunk</th>
         *     <th>Output Chunk</th>
         *   </tr>
         * </thead>
         * <tbody>
         *   <tr>
         *     <td>{@code null}</td>
         *     <td>{@code null}</td>
         *   </tr>
         *   <tr>
         *     <td>{@link Error}</td>
         *     <td>{@link Error}</td>
         *   </tr>
         *   <tr>
         *     <td>{@link #isLast()}</td>
         *     <td>{@link #EOF}</td>
         *   </tr>
         *   <tr>
         *     <td>any other Chunk</td>
         *     <td>{@code null}</td>
         *   </tr>
         * </tbody>
         * </table>
         */
        public static Chunk next(Chunk chunk)
        {
            if (chunk == null || chunk instanceof Error)
                return chunk;
            if (chunk.isLast())
                return EOF;
            return null;
        }

        /**
         * @return the ByteBuffer of this Chunk
         */
        public ByteBuffer getByteBuffer();

        /**
         * @return whether this is the last Chunk
         */
        public boolean isLast();

        /**
         * <p>Releases the resources associated to this Chunk.</p>
         */
        public void release();

        /**
         * @return the number of bytes remaining in this Chunk
         */
        public default int remaining()
        {
            return getByteBuffer().remaining();
        }

        /**
         * @return whether this Chunk has remaining bytes
         */
        public default boolean hasRemaining()
        {
            return getByteBuffer().hasRemaining();
        }

        /**
         * <p>Copies the bytes from this Chunk to the given byte array.</p>
         *
         * @param bytes the byte array to copy the bytes into
         * @param offset the offset within the byte array
         * @param length the maximum number of bytes to copy
         * @return the number of bytes actually copied
         */
        public default int get(byte[] bytes, int offset, int length)
        {
            ByteBuffer b = getByteBuffer();
            if (b == null || !b.hasRemaining())
                return 0;
            length = Math.min(length, b.remaining());
            b.get(bytes, offset, length);
            return length;
        }

        /**
         * <p>Skips, advancing the ByteBuffer position, the given number of bytes.</p>
         *
         * @param length the maximum number of bytes to skip
         * @return the number of bytes actually skipped
         */
        public default int skip(int length)
        {
            ByteBuffer byteBuffer = getByteBuffer();
            length = Math.min(byteBuffer.remaining(), length);
            byteBuffer.position(byteBuffer.position() + length);
            return length;
        }

        /**
         * <p>Returns whether this Chunk is a <em>terminal</em> chunk.</p>
         * <p>A terminal chunk is either an {@link Error error chunk},
         * or a Chunk that {@link #isLast() is last} and has no remaining
         * bytes.</p>
         *
         * @return whether this Chunk is a terminal chunk
         */
        public default boolean isTerminal()
        {
            return this instanceof Error || isLast() && !hasRemaining();
        }

        /**
         * <p>A chunk that wraps a failure.</p>
         * <p>Error Chunks are always last and have no bytes to read.</p>
         *
         * @see #from(Throwable)
         */
        public static final class Error implements Chunk
        {
            private final Throwable cause;

            private Error(Throwable cause)
            {
                this.cause = cause;
            }

            public Throwable getCause()
            {
                return cause;
            }

            @Override
            public ByteBuffer getByteBuffer()
            {
                return BufferUtil.EMPTY_BUFFER;
            }

            @Override
            public boolean isLast()
            {
                return true;
            }

            @Override
            public void release()
            {
            }
        }
    }
}
