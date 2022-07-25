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
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import org.eclipse.jetty.io.content.ContentSinkOutputStream;
import org.eclipse.jetty.io.content.ContentSinkSubscriber;
import org.eclipse.jetty.io.content.ContentSourceInputStream;
import org.eclipse.jetty.io.content.ContentSourcePublisher;
import org.eclipse.jetty.io.internal.ByteBufferChunk;
import org.eclipse.jetty.io.internal.ContentCopier;
import org.eclipse.jetty.io.internal.ContentSourceByteBuffer;
import org.eclipse.jetty.io.internal.ContentSourceConsumer;
import org.eclipse.jetty.io.internal.ContentSourceString;
import org.eclipse.jetty.util.Blocker;
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
        copy(source, sink, null, callback);
    }

    /**
     * <p>Copies the given content source to the given content sink, notifying
     * the given callback when the copy is complete.</p>
     * <p>The optional {@code chunkHandler} parameter is a predicate whose code
     * may inspect the chunk and handle it differently from how the implementation
     * would handle it.</p>
     * <p>If the predicate returns {@code true}, it means that the chunk is handled
     * externally and its callback completed, or eventually completed.</p>
     * <p>If the predicate returns {@code false}, it means that the chunk is not
     * handled, its callback will not be completed, and the implementation will
     * handle the chunk and its callback.</p>
     *
     * @param source the source to copy from
     * @param sink the sink to copy to
     * @param chunkHandler a (possibly {@code null}) predicate to handle the current chunk and its callback
     * @param callback the callback to notify when the copy is complete
     */
    public static void copy(Source source, Sink sink, BiPredicate<Chunk, Callback> chunkHandler, Callback callback)
    {
        new ContentCopier(source, sink, chunkHandler, callback).iterate();
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
        static void asByteBuffer(Source source, Promise<ByteBuffer> promise)
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
        static ByteBuffer asByteBuffer(Source source) throws IOException
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
        static void asString(Source source, Charset charset, Promise<String> promise)
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
        static String asString(Source source) throws IOException
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
        static InputStream asInputStream(Source source)
        {
            return new ContentSourceInputStream(source);
        }

        /**
         * <p>Wraps the given content source with a {@link Flow.Publisher}.</p>
         *
         * @param source the source to read from
         * @return a Publisher that publishes chunks read from the content source
         */
        static Flow.Publisher<Chunk> asPublisher(Source source)
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
        static void consumeAll(Source source, Callback callback)
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
        static void consumeAll(Source source) throws IOException
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
        default long getLength()
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
         * <p>The content reader code must ultimately arrange for a call to
         * {@link Chunk#release()} on the returned {@link Chunk}.</p>
         * <p>Additionally, prior to the ultimate call to {@link Chunk#release()}, the reader
         * code may make additional calls to {@link Chunk#retain()}, that must ultimately
         * be matched by a correspondent number of calls to {@link Chunk#release()}.</p>
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
         * @see Retainable
         */
        Chunk read();

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
        void demand(Runnable demandCallback);

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
        void fail(Throwable failure);
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
        static OutputStream asOutputStream(Sink sink)
        {
            return new ContentSinkOutputStream(sink);
        }

        /**
         * <p>Wraps the given content sink with a {@link Flow.Subscriber}.</p>
         *
         * @param sink the sink to write to
         * @param callback the callback to notify when the Subscriber is complete
         * @return a Subscriber that writes to the content sink
         */
        static Flow.Subscriber<Chunk> asSubscriber(Sink sink, Callback callback)
        {
            return new ContentSinkSubscriber(sink, callback);
        }

        /**
         * <p>Blocking version of {@link #write(boolean, ByteBuffer, Callback)}.</p>
         *
         * @param sink the sink to write to
         * @param last whether the ByteBuffers are the last to write
         * @param byteBuffer the ByteBuffers to write
         * @throws IOException if the write operation fails
         */
        static void write(Sink sink, boolean last, ByteBuffer byteBuffer) throws IOException
        {
            try (Blocker.Callback callback = Blocker.callback())
            {
                sink.write(last, byteBuffer, callback);
                callback.block();
            }
        }

        /**
         * <p>Writes the given {@link String}, converting it to UTF-8 bytes,
         * notifying the {@link Callback} when the write is complete.</p>
         *
         * @param last whether the String is the last to write
         * @param utf8Content the String to write
         * @param callback the callback to notify when the write operation is complete
         */
        static void write(Sink sink, boolean last, String utf8Content, Callback callback)
        {
            sink.write(last, StandardCharsets.UTF_8.encode(utf8Content), callback);
        }

        /**
         * <p>Writes the given {@link ByteBuffer}, notifying the {@link Callback}
         * when the write is complete.</p>
         *
         * @param last whether the ByteBuffer is the last to write
         * @param byteBuffer the ByteBuffer to write
         * @param callback the callback to notify when the write operation is complete
         */
        void write(boolean last, ByteBuffer byteBuffer, Callback callback);
    }

    /**
     * <p>A chunk of content indicating whether it is the last chunk.</p>
     * <p>Optionally, a release function may be specified (for example
     * to release the {@code ByteBuffer} back into a pool), or the
     * {@link #release()} method overridden.</p>
     */
    public interface Chunk extends Retainable
    {
        /**
         * <p>An empty, non-last, chunk.</p>
         */
        Chunk EMPTY = ByteBufferChunk.EMPTY;
        /**
         * <p>An empty, last, chunk.</p>
         */
        Content.Chunk EOF = ByteBufferChunk.EOF;

        /**
         * <p>Creates a Chunk with the given ByteBuffer.</p>
         * <p>The returned Chunk must be {@link #release() released}.</p>
         *
         * @param byteBuffer the ByteBuffer with the bytes of this Chunk
         * @param last whether the Chunk is the last one
         * @return a new Chunk
         */
        static Chunk from(ByteBuffer byteBuffer, boolean last)
        {
            return new ByteBufferChunk.WithReferenceCount(byteBuffer, last);
        }

        /**
         * <p>Creates a Chunk with the given ByteBuffer.</p>
         * <p>The returned Chunk must be {@link #release() released}.</p>
         *
         * @param byteBuffer the ByteBuffer with the bytes of this Chunk
         * @param last whether the Chunk is the last one
         * @return a new Chunk
         */
        static Chunk from(ByteBuffer byteBuffer, boolean last, Runnable releaser)
        {
            return new ByteBufferChunk.ReleasedByRunnable(byteBuffer, last, Objects.requireNonNull(releaser));
        }

        /**
         * <p>Creates a last/non-last Chunk with the given ByteBuffer.</p>
         * <p>The returned Chunk must be {@link #release() released}.</p>
         *
         * @param byteBuffer the ByteBuffer with the bytes of this Chunk
         * @param last whether the Chunk is the last one
         * @param releaser the code to run when this Chunk is released
         * @return a new Chunk
         */
        static Chunk from(ByteBuffer byteBuffer, boolean last, Consumer<ByteBuffer> releaser)
        {
            return new ByteBufferChunk.ReleasedByConsumer(byteBuffer, last, Objects.requireNonNull(releaser));
        }

        /**
         * <p>Creates a last/non-last Chunk with the given ByteBuffer, linked to the given Retainable.</p>
         * <p>The {@link #retain()} and {@link #release()} methods of this Chunk will delegate to the
         * given Retainable.</p>
         *
         * @param byteBuffer the ByteBuffer with the bytes of this Chunk
         * @param last whether the Chunk is the last one
         * @param retainable the Retainable this Chunk links to
         * @return a new Chunk
         */
        static Chunk from(ByteBuffer byteBuffer, boolean last, Retainable retainable)
        {
            return new ByteBufferChunk.WithRetainable(byteBuffer, last, Objects.requireNonNull(retainable));
        }

        /**
         * <p>Creates an {@link Error error chunk} with the given failure.</p>
         *
         * @param failure the cause of the failure
         * @return a new Error.Chunk
         */
        static Error from(Throwable failure)
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
        static Chunk next(Chunk chunk)
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
        ByteBuffer getByteBuffer();

        /**
         * @return whether this is the last Chunk
         */
        boolean isLast();

        /**
         * <p>Returns a new {@code Chunk} whose {@code ByteBuffer} is a slice, with the given
         * position and limit, of the {@code ByteBuffer} of the source {@code Chunk}.</p>
         * <p>The returned {@code Chunk} retains the source {@code Chunk} and it is linked
         * to it via {@link #from(ByteBuffer, boolean, Retainable)}.</p>
         *
         * @param source the original chunk
         * @param position the position at which the slice begins
         * @param limit the limit at which the slice ends
         * @param last whether the new Chunk is last
         * @return a new {@code Chunk} retained from the source {@code Chunk} with a slice
         * of the source {@code Chunk}'s {@code ByteBuffer}
         */
        default Chunk slice(Chunk source, int position, int limit, boolean last)
        {
            ByteBuffer sourceBuffer = source.getByteBuffer();
            int sourceLimit = sourceBuffer.limit();
            sourceBuffer.limit(limit);
            int sourcePosition = sourceBuffer.position();
            sourceBuffer.position(position);
            ByteBuffer slice = sourceBuffer.slice();
            sourceBuffer.limit(sourceLimit);
            sourceBuffer.position(sourcePosition);
            source.retain();
            return from(slice, last, source);
        }

        /**
         * @return the number of bytes remaining in this Chunk
         */
        default int remaining()
        {
            return getByteBuffer().remaining();
        }

        /**
         * @return whether this Chunk has remaining bytes
         */
        default boolean hasRemaining()
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
        default int get(byte[] bytes, int offset, int length)
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
        default int skip(int length)
        {
            ByteBuffer byteBuffer = getByteBuffer();
            length = Math.min(byteBuffer.remaining(), length);
            byteBuffer.position(byteBuffer.position() + length);
            return length;
        }

        /**
         * <p>Returns whether this Chunk is a <em>terminal</em> chunk.</p>
         * <p>A terminal chunk is either an {@link Error error chunk},
         * or a Chunk that {@link #isLast()} is true and has no remaining
         * bytes.</p>
         *
         * @return whether this Chunk is a terminal chunk
         */
        default boolean isTerminal()
        {
            return this instanceof Error || isLast() && !hasRemaining();
        }

        /**
         * <p>A chunk that wraps a failure.</p>
         * <p>Error Chunks are always last and have no bytes to read.</p>
         *
         * @see #from(Throwable)
         */
        final class Error implements Chunk
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
            public void retain()
            {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean release()
            {
                return true;
            }
        }
    }
}
