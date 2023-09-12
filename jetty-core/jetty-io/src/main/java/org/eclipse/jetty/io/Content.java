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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

import org.eclipse.jetty.io.content.BufferedContentSink;
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
     * the given callback when the copy is complete (either succeeded or failed).</p>
     * <p>In case of {@link Chunk#getFailure() failure chunks},
     * the content source is {@link Source#fail(Throwable) failed} if the failure
     * chunk is {@link Chunk#isLast() last}, else the failing is transient and is ignored.</p>
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
     * <p>In case of {@link Chunk#getFailure() failure chunks} not handled by any {@code chunkHandler},
     * the content source is {@link Source#fail(Throwable) failed} if the failure
     * chunk is {@link Chunk#isLast() last}, else the failure is transient and is ignored.</p>
     *
     * @param source the source to copy from
     * @param sink the sink to copy to
     * @param chunkProcessor a (possibly {@code null}) predicate to handle the current chunk and its callback
     * @param callback the callback to notify when the copy is complete
     */
    public static void copy(Source source, Sink sink, Chunk.Processor chunkProcessor, Callback callback)
    {
        new ContentCopier(source, sink, chunkProcessor, callback).iterate();
    }

    /**
     * <p>A source of content that can be read with a read/demand model.</p>
     * <h2><a id="idiom">Idiomatic usage</a></h2>
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
     *         // The chunk is a failure.
     *         if (Content.Chunk.isFailure(chunk)) {
     *             // Handle the failure.
     *             Throwable cause = chunk.getFailure();
     *             boolean transient = !chunk.isLast();
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
         * the bytes using UTF-8.</p>
         *
         * @param source the source to read
         * @return the String obtained from the content
         * @throws IOException if reading the content fails
         */
        static String asString(Source source) throws IOException
        {
            return asString(source, StandardCharsets.UTF_8);
        }

        /**
         * <p>Reads, blocking if necessary, the whole content source into a {@link String}, converting
         * the bytes using the given {@link Charset}.</p>
         *
         * @param source the source to read
         * @param charset the charset to use to decode bytes
         * @return the String obtained from the content
         * @throws IOException if reading the content fails
         */
        static String asString(Source source, Charset charset) throws IOException
        {
            try
            {
                return asStringAsync(source, charset).get();
            }
            catch (Throwable x)
            {
                throw IO.rethrow(x);
            }
        }

        /**
         * <p>Read, non-blocking, the whole content source into a {@link String}, converting
         * the bytes using the given {@link Charset}.</p>
         *
         * @param source the source to read
         * @param charset the charset to use to decode bytes
         * @return the {@link CompletableFuture} to notify when the whole content has been read
         */
        static CompletableFuture<String> asStringAsync(Source source, Charset charset)
        {
            Promise.Completable<String> completable = new Promise.Completable<>();
            asString(source, charset, completable);
            return completable;
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
         * <p>Reads, non-blocking, the given content source, until a {@link Chunk#isFailure(Chunk) failure} or EOF
         * and discards the content.</p>
         *
         * @param source the source to read from
         * @param callback the callback to notify when the whole content has been read
         * or a failure occurred while reading the content
         */
        static void consumeAll(Source source, Callback callback)
        {
            new ContentSourceConsumer(source, callback).run();
        }

        /**
         * <p>Reads, blocking if necessary, the given content source, until a {@link Chunk#isFailure(Chunk) failure}
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
         * <li>an {@link Chunk} instance with non null {@link Chunk#getFailure()}, to signal that there was a failure
         * trying to produce a chunk of content, or that the content production has been
         * {@link #fail(Throwable) failed} externally</li>
         * <li>a {@link Chunk} instance, containing the chunk of content.</li>
         * </ul>
         * <p>Once a read returns an {@link Chunk} instance with non-null {@link Chunk#getFailure()}
         * then if the failure is {@link Chunk#isLast() last} further reads
         * will continue to return the same failure chunk instance, otherwise further
         * {@code read()} operations may return different non-failure chunks.</p>
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
         * @return a chunk of content, possibly a failure instance, or {@code null}
         * @see #demand(Runnable)
         * @see Retainable
         */
        Chunk read();

        /**
         * <p>Demands to invoke the given demand callback parameter when a chunk of content is available.</p>
         * <p>See how to use this method <a href="#idiom">idiomatically</a>.</p>
         * <p>Implementations guarantee that calls to this method are safely reentrant so that
         * stack overflows are avoided in the case of mutual recursion between the execution of
         * the {@code Runnable} callback and a call to this method.  Invocations of the passed
         * {@code Runnable} are serialized and a callback for {@code demand} call is
         * not invoked until any previous {@code demand} callback has returned.
         * Thus the {@code Runnable} should not block waiting for a callback of a future demand call.</p>
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
         * <p>Fails this content source with a {@link Chunk#isLast() last} {@link Chunk#getFailure() failure chunk},
         * failing and discarding accumulated content chunks that were not yet read.</p>
         * <p>The failure may be notified to the content reader at a later time, when
         * the content reader reads a content chunk, via a {@link Chunk} instance
         * with a non null {@link Chunk#getFailure()}.</p>
         * <p>If {@link #read()} has returned a last chunk, this is a no operation.</p>
         * <p>Typical failure: the content being aborted by user code, or idle timeouts.</p>
         * <p>If this method has already been called, then it is a no operation.</p>
         *
         * @param failure the cause of the failure
         * @see Chunk#getFailure()
         */
        void fail(Throwable failure);

        /**
         * <p>Fails this content source with a {@link Chunk#getFailure() failure chunk}
         * that may or not may be {@link Chunk#isLast() last}.
         * If {@code last} is {@code true}, then the failure is persistent and a call to this method acts
         * as {@link #fail(Throwable)}. Otherwise the failure is transient and a
         * {@link Chunk#getFailure() failure chunk} will be {@link #read() read} in order with content chunks,
         * and subsequent calls to {@link #read() read} may produce other content.</p>
         * <p>A {@code Content.Source} or its {@link #read() reader} may treat a transient failure as persistent.</p>
         *
         * @param failure A failure.
         * @param last true if the failure is persistent, false if the failure is transient.
         * @see Chunk#getFailure()
         */
        default void fail(Throwable failure, boolean last)
        {
            fail(failure);
        }

        /**
         * <p>Rewinds this content, if possible, so that subsequent reads return
         * chunks starting from the beginning of this content.</p>
         *
         * @return true if this content has been rewound, false if this content
         * cannot be rewound
         */
        default boolean rewind()
        {
            return false;
        }
    }

    /**
     * <p>A content sink that writes the content to its implementation (a socket, a file, etc.).</p>
     */
    public interface Sink
    {
        /**
         * <p>Wraps the given content sink with a buffering sink.</p>
         *
         * @param sink the sink to write to
         * @param bufferPool the {@link org.eclipse.jetty.io.ByteBufferPool} to use
         * @param direct true to use direct buffers, false to use heap buffers
         * @param maxBufferSize the maximum size of the buffer
         * @param maxAggregationSize the maximum size that can be buffered in a single write;
         * any size above this threshold triggers a buffer flush
         * @return a Sink that writes to the given content sink
         */
        static Sink asBuffered(Sink sink, ByteBufferPool bufferPool, boolean direct, int maxBufferSize, int maxAggregationSize)
        {
            return new BufferedContentSink(sink, bufferPool, direct, maxBufferSize, maxAggregationSize);
        }

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
         * @param callback the callback to notify when the write operation is complete.
         *                 Implementations have the same guarantees for invocation of this
         *                 callback as for {@link #write(boolean, ByteBuffer, Callback)}.
         */
        static void write(Sink sink, boolean last, String utf8Content, Callback callback)
        {
            sink.write(last, StandardCharsets.UTF_8.encode(utf8Content), callback);
        }

        /**
         * <p>Writes the given {@link ByteBuffer}, notifying the {@link Callback}
         * when the write is complete.</p>
         * <p>Implementations guarantee that calls to this method are safely reentrant so that
         * stack overflows are avoided in the case of mutual recursion between the execution of
         * the {@code Callback} and a call to this method.</p>
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
        Chunk EMPTY = new Chunk()
        {
            @Override
            public ByteBuffer getByteBuffer()
            {
                return BufferUtil.EMPTY_BUFFER;
            }

            @Override
            public boolean isLast()
            {
                return false;
            }

            @Override
            public String toString()
            {
                return "EMPTY";
            }
        };

        /**
         * <p>An empty, last, chunk.</p>
         */
        Content.Chunk EOF = new Chunk()
        {
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
            public String toString()
            {
                return "EOF";
            }
        };

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
            if (byteBuffer.hasRemaining())
               return new ByteBufferChunk.WithReferenceCount(byteBuffer, last);
            return last ? EOF : EMPTY;
        }

        /**
         * <p>Creates a Chunk with the given ByteBuffer.</p>
         * <p>The returned Chunk must be {@link #release() released}.</p>
         *
         * @param byteBuffer the ByteBuffer with the bytes of this Chunk
         * @param last whether the Chunk is the last one
         * @param releaser the code to run when this Chunk is released
         * @return a new Chunk
         */
        static Chunk from(ByteBuffer byteBuffer, boolean last, Runnable releaser)
        {
            if (byteBuffer.hasRemaining())
                return new ByteBufferChunk.ReleasedByRunnable(byteBuffer, last, Objects.requireNonNull(releaser));
            releaser.run();
            return last ? EOF : EMPTY;
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
            if (byteBuffer.hasRemaining())
                return new ByteBufferChunk.ReleasedByConsumer(byteBuffer, last, Objects.requireNonNull(releaser));
            releaser.accept(byteBuffer);
            return last ? EOF : EMPTY;
        }

        /**
         * <p>Returns the given {@code ByteBuffer} and {@code last} arguments
         * as a {@code Chunk}, linked to the given {@link Retainable}.</p>
         * <p>The {@link #retain()} and {@link #release()} methods of this
         * {@code Chunk} will delegate to the given {@code Retainable}.</p>
         *
         * @param byteBuffer the ByteBuffer with the bytes of this Chunk
         * @param last whether the Chunk is the last one
         * @param retainable the Retainable this Chunk links to
         * @return a new Chunk
         * @throws IllegalArgumentException if the {@code Retainable}
         * {@link Retainable#canRetain() cannot be retained}
         */
        static Chunk asChunk(ByteBuffer byteBuffer, boolean last, Retainable retainable)
        {
            if (!retainable.canRetain())
                throw new IllegalArgumentException("Cannot create chunk from non-retainable " + retainable);
            if (byteBuffer.hasRemaining())
                return new ByteBufferChunk.WithRetainable(byteBuffer, last, Objects.requireNonNull(retainable));
            retainable.release();
            return last ? EOF : EMPTY;
        }

        /**
         * <p>Creates an {@link Chunk#isFailure(Chunk) failure chunk} with the given failure
         * and {@link Chunk#isLast()} returning true.</p>
         *
         * @param failure the cause of the failure
         * @return a new {@link Chunk#isFailure(Chunk) failure chunk}
         */
        static Chunk from(Throwable failure)
        {
            return from(failure, true);
        }

        /**
         * <p>Creates an {@link Chunk#isFailure(Chunk) failure chunk} with the given failure
         * and given {@link Chunk#isLast() last} state.</p>
         *
         * @param failure the cause of the failure
         * @param last true if the failure is terminal, else false for transient failure
         * @return a new {@link Chunk#isFailure(Chunk) failure chunk}
         */
        static Chunk from(Throwable failure, boolean last)
        {
            return new Chunk()
            {
                public Throwable getFailure()
                {
                    return failure;
                }

                @Override
                public ByteBuffer getByteBuffer()
                {
                    return BufferUtil.EMPTY_BUFFER;
                }

                @Override
                public boolean isLast()
                {
                    return last;
                }

                @Override
                public String toString()
                {
                    return String.format("Chunk@%x{c=%s,l=%b}", hashCode(), failure, last);
                }
            };
        }

        /**
         * <p>Returns the chunk that follows the given chunk.</p>
         * <table>
         * <caption>Next Chunk</caption>
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
         *     <td>{@link Chunk#isFailure(Chunk) Failure} and {@link Chunk#isLast() last}</td>
         *     <td>{@link Error Error}</td>
         *   </tr>
         *   <tr>
         *     <td>{@link Chunk#isFailure(Chunk) Failure} and {@link Chunk#isLast() not last}</td>
         *     <td>{@code null}</td>
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
            if (chunk == null)
                return null;
            if (Content.Chunk.isFailure(chunk))
                return chunk.isLast() ? chunk : null;
            if (chunk.isLast())
                return EOF;
            return null;
        }

        /**
         * @param chunk The chunk to test for an {@link Chunk#getFailure() failure}.
         * @return True if the chunk is non-null and {@link Chunk#getFailure() chunk.getError()} returns non-null.
         */
        static boolean isFailure(Chunk chunk)
        {
            return chunk != null && chunk.getFailure() != null;
        }

        /**
         * @param chunk The chunk to test for an {@link Chunk#getFailure() failure}
         * @param last The {@link Chunk#isLast() last} status to test for.
         * @return True if the chunk is non-null and {@link Chunk#getFailure()} returns non-null
         *         and {@link Chunk#isLast()} matches the passed status.
         */
        static boolean isFailure(Chunk chunk, boolean last)
        {
            return chunk != null && chunk.getFailure() != null && chunk.isLast() == last;
        }

        /**
         * @return the ByteBuffer of this Chunk
         */
        ByteBuffer getByteBuffer();

        /**
         * Get a failure (which may be from a {@link Source#fail(Throwable) failure} or
         * a {@link Source#fail(Throwable, boolean) warning}), if any, associated with the chunk.
         * <ul>
         * <li>A {@code chunk} must not have a failure and a {@link #getByteBuffer()} with content.</li>
         * <li>A {@code chunk} with a failure may or may not be {@link #isLast() last}.</li>
         * <li>A {@code chunk} with a failure must not be {@link #canRetain() retainable}.</li>
         * </ul>
         * @return A {@link Throwable} indicating the failure or null if there is no failure or warning.
         * @see Source#fail(Throwable)
         * @see Source#fail(Throwable, boolean)
         */
        default Throwable getFailure()
        {
            return null;
        }

        /**
         * @return whether this is the last Chunk
         */
        boolean isLast();

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
            if (length == 0)
                return 0;
            ByteBuffer byteBuffer = getByteBuffer();
            length = Math.min(byteBuffer.remaining(), length);
            byteBuffer.position(byteBuffer.position() + length);
            return length;
        }

        /**
         * @return an immutable version of this Chunk
         */
        default Chunk asReadOnly()
        {
            if (!canRetain())
                return this;
            return asChunk(getByteBuffer().asReadOnlyBuffer(), isLast(), this);
        }

        /**
         * <p>Implementations of this interface may process {@link Chunk}s being copied by the
         * {@link Content#copy(Source, Sink, Processor, Callback)} method, so that
         * {@link Chunk}s of unknown types can be copied.
         * @see Content#copy(Source, Sink, Processor, Callback)
         */
        interface Processor
        {
            /**
             * @param chunk The chunk to be considered for processing.
             * @param callback The callback that will be called once the accepted chunk is processed.
             * @return True if the chunk will be process and the callback will be called (or may have already been called), false otherwise.
             */
            boolean process(Chunk chunk, Callback callback);
        }
    }
}
