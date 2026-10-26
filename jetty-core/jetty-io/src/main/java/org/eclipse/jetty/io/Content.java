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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.ByteChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.Flow;

import org.eclipse.jetty.io.content.BufferedContentSink;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.io.content.ContentSinkOutputStream;
import org.eclipse.jetty.io.content.ContentSinkSubscriber;
import org.eclipse.jetty.io.content.ContentSourceInputStream;
import org.eclipse.jetty.io.content.ContentSourcePublisher;
import org.eclipse.jetty.io.content.InputStreamContentSource;
import org.eclipse.jetty.io.content.RetainableByteBufferContentSource;
import org.eclipse.jetty.io.internal.BufferChunk;
import org.eclipse.jetty.io.internal.ByteChannelContentSource;
import org.eclipse.jetty.io.internal.ContentCopier;
import org.eclipse.jetty.io.internal.ContentSourceConsumer;
import org.eclipse.jetty.io.internal.ContentSourceRange;
import org.eclipse.jetty.io.internal.ContentSourceRetainableByteBuffer;
import org.eclipse.jetty.io.internal.ContentSourceString;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.thread.Invocable;

/// Namespace class that contains the definitions of a [`content source`][Source],
/// a [`content sink`][Sink] and a [`content chunk`][Chunk].
public class Content
{
    private Content()
    {
    }

    /// Copies the given content source to the given content sink, notifying
    /// the given callback when the copy is complete (either succeeded or failed).
    ///
    /// In case of [`failure chunks`][Chunk#getFailure()],
    /// the content source is [`failed`][Source#fail(Throwable)].
    ///
    /// @param source the source to copy from
    /// @param sink the sink to copy to
    /// @param callback the callback to notify when the copy is complete
    /// @see #copy(Source, Sink, Chunk.Processor, Callback) to allow processing of individual `Content.Chunk`s, including
    ///      the ability to ignore transient failures.
    public static void copy(Source source, Sink sink, Callback callback)
    {
        copy(source, sink, null, callback);
    }

    /// Copies the given content source to the given content sink, notifying
    /// the given callback when the copy is complete.
    ///
    /// The optional `chunkHandler` parameter is a predicate whose code
    /// may inspect the chunk and handle it differently from how the implementation
    /// would handle it.
    ///
    /// If the predicate returns `true`, it means that the chunk is handled
    /// externally and its callback completed, or eventually completed.
    ///
    /// If the predicate returns `false`, it means that the chunk is not
    /// handled, its callback will not be completed, and the implementation will
    /// handle the chunk and its callback.
    ///
    /// In case of [`failure chunks`][Chunk#getFailure()] not handled by any `chunkHandler`,
    /// the content source is [`failed`][Source#fail(Throwable)] if the failure
    /// chunk is [`last`][Chunk#isLast()], else the failure is transient and is ignored.
    ///
    /// @param source the source to copy from
    /// @param sink the sink to copy to
    /// @param chunkProcessor a (possibly `null`) processor to handle the current [Chunk] and its callback
    /// @param callback the callback to notify when the copy is complete
    public static void copy(Source source, Sink sink, Chunk.Processor chunkProcessor, Callback callback)
    {
        new ContentCopier(source, sink, chunkProcessor, callback).iterate();
    }

    /// A source of content that can be read with a read/demand model.
    ///
    /// To avoid leaking its resources, a source **must** either:
    ///
    /// * be read until it returns a [`last chunk`][Chunk#isLast()], either EOF or a terminal failure
    /// * be [`failed`][#fail(Throwable)]
    ///
    /// ### <a id="idiom">Idiomatic usage</a>
    ///
    /// The read/demand model typical usage is the following:
    ///
    /// ```
    /// public void onContentAvailable() {
    ///     while (true) {
    ///         // Read a chunk.
    ///         try (Chunk chunk = source.read()) {
    ///             // There is no chunk, demand to be called back and return.
    ///             if (chunk == null) {
    ///                 source.demand(this::onContentAvailable);
    ///                 return;
    ///             }
    ///
    ///             // The chunk is a failure.
    ///             if (Content.Chunk.isFailure(chunk)) {
    ///                 boolean fatal = chunk.isLast();
    ///                 if (fatal) {
    ///                     handleFatalFailure(chunk.getFailure());
    ///                     return;
    ///                 } else {
    ///                     handleTransientFailure(chunk.getFailure());
    ///                     continue;
    ///                 }
    ///             }
    ///
    ///             // It's a valid chunk, consume the chunk's bytes synchronously.
    ///             try (RetainableByteBuffer buffer = chunk.acquire()) {
    ///                 consume(buffer);
    ///             }
    ///
    ///             // Exit if the Content.Source is fully consumed.
    ///             if (chunk.isLast()) {
    ///                 break;
    ///             }
    ///         } // Release the chunk.
    ///     }
    /// }
    /// ```
    ///
    /// If the chunk, or its bytes, must remain valid after the try-with-resources block
    /// (for example because they are passed to an asynchronous operation, stored, or returned),
    /// the chunk must be retained before leaving the block and released when its bytes
    /// are no longer needed.
    /// For asynchronous operations completed via a [Callback], release the chunk by
    /// wrapping the callback as shown below.
    ///
    /// ```
    /// public void copySourceToSink(Source source, Sink sink, Callback callback) {
    ///     try (Content.Chunk chunk = source.read()) {
    ///         ...
    ///         // The chunk will be used in an asynchronous operation, retain it.
    ///         chunk.retain();
    ///         try (RetainableByteBuffer buffer = chunk.acquire()) {
    ///             // Wrap the callback to release the chunk
    ///             // when the asynchronous operation completes.
    ///             sink.write(chunk.isLast(), buffer, Callback.from(chunk::release, callback));
    ///         }
    ///     }
    /// }
    /// ```
    ///
    /// Note that it is the chunk that must be retained.
    /// Retaining only the buffer obtained from [Chunk#acquire()] is not sufficient,
    /// because it is the release of the chunk that signals to the [Source] that the
    /// chunk bytes may be reused.
    public interface Source
    {
        /// Factory of [Content.Source].
        interface Factory
        {
            /// Creates a new [Content.Source].
            ///
            /// @param bufferPool the [ByteBufferPool.Sized] to get buffers from. `null` means allocate new buffers as needed.
            /// @param offset the offset byte of the content to start from.
            ///               Must be greater than or equal to 0 and less than the content length (if known).
            /// @param length the length of the content to make available, -1 for the full length.
            ///               If the size of the content is known, the length may be truncated to the content size minus the offset.
            /// @return a [Content.Source].
            /// @throws IndexOutOfBoundsException if the offset or length are out of range.
            /// @see TypeUtil#checkOffsetLengthSize(long, long, long)
            Content.Source newContentSource(ByteBufferPool.Sized bufferPool, long offset, long length);
        }

        /// Create a `Content.Source` from zero or more [ByteBuffer]s
        /// @param byteBuffers The [ByteBuffer]s to use as the source.
        /// @return A `Content.Source`
        static Content.Source from(ByteBuffer... byteBuffers)
        {
            return new ByteBufferContentSource(byteBuffers);
        }

        static Content.Source from(Collection<RetainableByteBuffer> buffers)
        {
            return new RetainableByteBufferContentSource(buffers);
        }

        /// Create a `Content.Source` from a [Path].
        /// @param path The [Path]s to use as the source.
        /// @return A `Content.Source`
        static Content.Source from(Path path)
        {
            return from(null, path, 0, -1);
        }

        /// Create a `Content.Source` from a [Path].
        ///
        /// @param path The [Path]s to use as the source.
        /// @param offset the offset byte of the content to start from.
        ///               Must be greater than or equal to 0 and less than the content length (if known).
        /// @param length the length of the content to make available, -1 for the full length.
        ///               If the size of the content is known, the length may be truncated to the content size minus the offset.
        /// @return a [Content.Source].
        /// @throws IndexOutOfBoundsException if the offset or length are out of range.
        /// @see TypeUtil#checkOffsetLengthSize(long, long, long)
        static Content.Source from(Path path, long offset, long length)
        {
            return from(null, path, offset, length);
        }

        /// Wrap a [Content.Source] to make it appear as a sub-range of the original.
        ///
        /// @param source The [Content.Source] to wrap.
        /// @param offset the offset byte of the content to start from.
        ///               Must be greater than or equal to 0 and less than the content length (if known).
        /// @param length the length of the content to make available, -1 for the full length.
        ///               If the size of the content is known, the length may be truncated to the content size minus the offset.
        /// @return a [Content.Source].
        /// @throws IndexOutOfBoundsException if the offset or length are out of range.
        /// @see TypeUtil#checkOffsetLengthSize(long, long, long)
        static Content.Source from(Content.Source source, long offset, long length)
        {
            // If the offset and length include the full content, then do not wrap.
            if (offset == 0 && (length == -1 || length == source.getLength()))
                return source;

            return new ContentSourceRange(source, offset, length);
        }

        /// Create a `Content.Source` from a [Path].
        /// @param byteBufferPool The [ByteBufferPool.Sized] to use for any internal buffers.
        /// @param path The [Path]s to use as the source.
        /// @return A `Content.Source`
        static Content.Source from(ByteBufferPool.Sized byteBufferPool, Path path)
        {
            return from(byteBufferPool, path, 0, -1);
        }

        /// Create a `Content.Source` from a [Path].
        /// @param byteBufferPool The [ByteBufferPool.Sized] to use for any internal buffers.
        /// @param path The [Path]s to use as the source.
        /// @param offset the offset byte of the content to start from.
        ///               Must be greater than or equal to 0 and less than the content length (if known).
        /// @param length the length of the content to make available, -1 for the full length.
        ///               If the size of the content is known, the length may be truncated to the content size minus the offset.
        /// @return a [Content.Source].
        /// @throws IndexOutOfBoundsException if the offset or length are out of range.
        /// @see TypeUtil#checkOffsetLengthSize(long, long, long)
        static Content.Source from(ByteBufferPool.Sized byteBufferPool, Path path, long offset, long length)
        {
            return new ByteChannelContentSource.PathContentSource(byteBufferPool, path, offset, length);
        }

        /// Create a `Content.Source` from a [ByteChannel].
        /// @param byteBufferPool The [ByteBufferPool.Sized] to use for any internal buffers.
        /// @param byteChannel The [ByteChannel]s to use as the source.
        /// @return A `Content.Source`
        static Content.Source from(ByteBufferPool.Sized byteBufferPool, ByteChannel byteChannel)
        {
            return new ByteChannelContentSource(byteBufferPool, byteChannel);
        }

        /// Create a `Content.Source` from a [ByteChannel].
        /// @param byteBufferPool The [ByteBufferPool.Sized] to use for any internal buffers.
        /// @param seekableByteChannel The [ByteChannel]s to use as the source.
        /// @param offset the offset byte of the content to start from.
        ///               Must be greater than or equal to 0 and less than the content length (if known).
        /// @param length the length of the content to make available, -1 for the full length.
        ///               If the size of the content is known, the length may be truncated to the content size minus the offset.
        /// @return a [Content.Source].
        /// @throws IndexOutOfBoundsException if the offset or length are out of range.
        /// @see TypeUtil#checkOffsetLengthSize(long, long, long)
        static Content.Source from(ByteBufferPool.Sized byteBufferPool, SeekableByteChannel seekableByteChannel, long offset, long length)
        {
            return new ByteChannelContentSource(byteBufferPool, seekableByteChannel, offset, length);
        }

        static Content.Source from(InputStream inputStream)
        {
            return from(null, inputStream);
        }

        /// Create a `Content.Source` from an [InputStream].
        /// @param byteBufferPool The [ByteBufferPool.Sized] to use for any internal buffers.
        /// @param inputStream The [InputStream]s to use as the source.
        /// @return A `Content.Source`
        static Content.Source from(ByteBufferPool.Sized byteBufferPool, InputStream inputStream)
        {
            return new InputStreamContentSource(inputStream, byteBufferPool);
        }

        /// Create a `Content.Source` from an [InputStream].
        /// @param byteBufferPool The [ByteBufferPool.Sized] to use for any internal buffers.
        /// @param inputStream The [InputStream]s to use as the source.
        /// @param offset the offset byte of the resource to start from.
        ///               Must be greater than or equal to 0 and less than the resource size (if known).
        /// @param length the length of the content to make available, or -1 for the full length available.
        ///               The length may be truncated if the stream ends sooner.
        /// @return a [Content.Source].
        /// @throws IndexOutOfBoundsException if the offset or length are out of range.
        /// @see TypeUtil#checkOffsetLengthSize(long, long, long)
        static Content.Source from(ByteBufferPool.Sized byteBufferPool, InputStream inputStream, long offset, long length)
        {
            return new InputStreamContentSource(inputStream, byteBufferPool, offset, length);
        }

        /// Reads, non-blocking, the whole content source into a [ByteBuffer].
        ///
        /// @param source the source to read
        /// @param promise the promise to notify when the whole content has been read into a ByteBuffer.
        static void asByteBuffer(Source source, Promise<ByteBuffer> promise)
        {
            Promise.Invocable<RetainableByteBuffer> p = Promise.Invocable.from(
                Invocable.getInvocationType(promise),
                b -> promise.succeeded(b.getByteBuffer(false)),
                promise::failed
            );
            asRetainableByteBuffer(source, -1, p);
        }

        /// Reads, blocking if necessary, the whole content source into a [ByteBuffer].
        ///
        /// @param source the source to read
        /// @return the ByteBuffer containing the content
        /// @throws IOException if reading the content fails
        static ByteBuffer asByteBuffer(Source source) throws IOException
        {
            try
            {
                try (Blocker.Promise<ByteBuffer> promise = Blocker.promise())
                {
                    asByteBuffer(source, promise);
                    return promise.block();
                }
            }
            catch (Throwable x)
            {
                throw IO.rethrow(x);
            }
        }

        /// Reads, non-blocking, the whole content source into a `byte` array.
        ///
        /// @param source the source to read
        /// @param maxSize The maximum size to read, or -1 for no limit
        /// @param promise the [Promise.Invocable] to notify when the whole content has been read into a byte array.
        static void asByteArrayAsync(Source source, int maxSize, Promise.Invocable<byte[]> promise)
        {
            asRetainableByteBuffer(source, maxSize, Promise.Invocable.toPromise(promise, RetainableByteBuffer::getArray));
        }

        /// Reads, non-blocking, the whole content source into a [RetainableByteBuffer].
        ///
        /// @param source the source to read
        /// @param maxSize The maximum size to read, or -1 for no limit
        /// @param promise the promise to notify when the whole content has been read into a RetainableByteBuffer.
        static void asRetainableByteBuffer(Source source, long maxSize, Promise<RetainableByteBuffer> promise)
        {
            new ContentSourceRetainableByteBuffer(source, maxSize, promise).run();
        }

        static void asRetainableByteBuffer(Source source, Promise<RetainableByteBuffer> promise)
        {
            asRetainableByteBuffer(source, -1, promise);
        }

        static RetainableByteBuffer asRetainableByteBuffer(Source source) throws IOException
        {
            try
            {
                try (Blocker.Promise<RetainableByteBuffer> promise = Blocker.promise(RetainableByteBuffer::retain))
                {
                    asRetainableByteBuffer(source, promise);
                    return promise.block();
                }
            }
            catch (Throwable x)
            {
                throw IO.rethrow(x);
            }
        }

        /// Reads, non-blocking, the whole content source into a [String], converting the bytes
        /// using the given [Charset].
        ///
        /// @param source the source to read
        /// @param charset the charset to use to convert the bytes into characters
        /// @param promise the promise to notify when the whole content has been converted into a String
        static void asString(Source source, Charset charset, Promise<String> promise)
        {
            new ContentSourceString(source, charset, promise).convert();
        }

        /// Reads, blocking if necessary, the whole content source into a [String], converting
        /// the bytes using UTF-8.
        ///
        /// @param source the source to read
        /// @return the String obtained from the content
        /// @throws IOException if reading the content fails
        static String asString(Source source) throws IOException
        {
            return asString(source, StandardCharsets.UTF_8);
        }

        /// Reads, blocking if necessary, the whole content source into a [String], converting
        /// the bytes using the given [Charset].
        ///
        /// @param source the source to read
        /// @param charset the charset to use to decode bytes
        /// @return the String obtained from the content
        /// @throws IOException if reading the content fails
        static String asString(Source source, Charset charset) throws IOException
        {
            try
            {
                try (Blocker.Promise<String> promise = Blocker.promise())
                {
                    asString(source, charset, promise);
                    return promise.block();
                }
            }
            catch (Throwable x)
            {
                throw IO.rethrow(x);
            }
        }

        /// Wraps the given content source with an [InputStream].
        ///
        /// @param source the source to read from
        /// @return an InputStream that reads from the content source
        static InputStream asInputStream(Source source)
        {
            return new ContentSourceInputStream(source);
        }

        /// Wraps the given content source with a [Flow.Publisher].
        ///
        /// @param source the source to read from
        /// @return a Publisher that publishes chunks read from the content source
        static Flow.Publisher<Chunk> asPublisher(Source source)
        {
            return new ContentSourcePublisher(source);
        }

        /// Reads, non-blocking, the given content source, until a [`failure`][Chunk#isFailure(Chunk)] or EOF
        /// and discards the content.
        ///
        /// @param source the source to read from
        /// @param callback the callback to notify when the whole content has been read
        /// or a failure occurred while reading the content
        static void consumeAll(Source source, Callback callback)
        {
            new ContentSourceConsumer(source, callback).run();
        }

        /// Reads, blocking if necessary, the given content source, until a [`failure`][Chunk#isFailure(Chunk)]
        /// or EOF, and discards the content.
        ///
        /// @param source the source to read from
        /// @throws IOException if reading the content fails
        static void consumeAll(Source source) throws IOException
        {
            try
            {
                try (Blocker.Callback callback = Blocker.callback())
                {
                    consumeAll(source, callback);
                    callback.block();
                }
            }
            catch (Throwable x)
            {
                throw IO.rethrow(x);
            }
        }

        /// @return the content length, if known, or -1 if the content length is unknown
        default long getLength()
        {
            return -1;
        }

        /// Reads a chunk of content.
        ///
        /// See how to use this method [idiomatically](#idiom).
        ///
        /// The returned chunk could be:
        ///
        ///   - `null`, to signal that there isn't a chunk of content available
        ///   - an [Chunk] instance with non-null [Chunk#getFailure()], to signal that there was a failure
        ///     trying to produce a chunk of content, or that the content production has been
        ///     [`failed`][#fail(Throwable)] externally
        ///   - a [Chunk] instance, containing the chunk of content.
        ///
        /// Once a read returns an [Chunk] instance with non-null [Chunk#getFailure()]
        /// then if the failure is [`last`][Chunk#isLast()] further reads
        /// will continue to return the same failure chunk instance, otherwise further
        /// `read()` operations may return different non-failure chunks.
        ///
        /// Once a read returns a [`last chunk`][Chunk#isLast()], further reads will
        /// continue to return a last chunk (although the instance may be different).
        ///
        /// The content reader code must ultimately arrange for a call to
        /// [Chunk#release()] on the returned [Chunk].
        ///
        /// Additionally, prior to the ultimate call to [Chunk#release()], the reader
        /// code may make additional calls to [Chunk#retain()], that must ultimately
        /// be matched by a correspondent number of calls to [Chunk#release()].
        ///
        /// Concurrent reads from different threads are not recommended, as they are
        /// inherently in a race condition.
        ///
        /// Reads performed outside the invocation context of a
        /// [`demand callback`][#demand(Runnable)] are allowed.
        /// However, reads performed with a pending demand are inherently in a
        /// race condition (the thread that reads with the thread that invokes the
        /// demand callback).
        ///
        /// @return a chunk of content, possibly a failure instance, or `null`
        /// @see #demand(Runnable)
        /// @see Retainable
        Chunk read();

        /// Demands to invoke the given demand callback parameter when a chunk of content is available.
        ///
        /// See how to use this method [idiomatically](#idiom).
        ///
        /// Implementations guarantee that calls to this method are safely reentrant so that
        /// stack overflows are avoided in the case of mutual recursion between the execution of
        /// the `Runnable` callback and a call to this method.  Invocations of the passed
        /// `Runnable` are serialized and a callback for `demand` call is
        /// not invoked until any previous `demand` callback has returned.
        /// Thus, the `Runnable` should not block waiting for a callback of a future demand call.
        ///
        /// The demand callback may be invoked _spuriously_: a subsequent call to [#read()]
        /// may return `null`.
        ///
        /// Calling this method establishes a _pending demand_, which is fulfilled when the demand
        /// callback is invoked.
        ///
        /// Calling this method when there is already a pending demand results in an
        /// [IllegalStateException] to be thrown.
        ///
        /// If the invocation of the demand callback throws an exception, then [#fail(Throwable)]
        /// is called.
        ///
        /// @param demandCallback the demand callback to invoke where there is a content chunk available
        /// @throws IllegalStateException when this method is called with an existing demand
        /// @see #read()
        void demand(Runnable demandCallback);

        /// Fails this content source with a [`last`][Chunk#isLast()] [`failure chunk`][Chunk#getFailure()],
        /// failing and discarding accumulated content chunks that were not yet read.
        ///
        /// The failure may be notified to the content reader at a later time, when
        /// the content reader reads a content chunk, via a [Chunk] instance
        /// with a non null [Chunk#getFailure()].
        ///
        /// If [#read()] has returned a last chunk, this is a no operation.
        ///
        /// Typical failure: the content being aborted by user code, or idle timeouts.
        ///
        /// If this method has already been called, then it is a no operation.
        ///
        /// @param failure the cause of the failure
        /// @see Chunk#getFailure()
        void fail(Throwable failure);

        /// Fails this content source with a [`failure chunk`][Chunk#getFailure()]
        /// that may or not may be [`last`][Chunk#isLast()].
        /// If `last` is `true`, then the failure is persistent and a call to this method acts
        /// as [#fail(Throwable)]. Otherwise, the failure is transient and a
        /// [`failure chunk`][Chunk#getFailure()] will be [`read`][#read()] in order with content chunks,
        /// and subsequent calls to [`read`][#read()] may produce other content.
        ///
        /// A `Content.Source` or its [`reader`][#read()] may treat a transient failure as persistent.
        ///
        /// @param failure A failure.
        /// @param last true if the failure is persistent, false if the failure is transient.
        /// @see Chunk#getFailure()
        default void fail(Throwable failure, boolean last)
        {
            fail(failure);
        }

        /// Rewinds this content, if possible, so that subsequent reads return
        /// chunks starting from the beginning of this content.
        ///
        /// @return true if this content has been rewound, false if this content
        /// cannot be rewound
        default boolean rewind()
        {
            return false;
        }
    }

    /// A content sink that writes the content to its implementation (a socket, a file, etc.).
    public interface Sink
    {
        /// Wraps the given [OutputStream] as a [Sink].
        /// @param out The stream to wrap
        /// @return A sink wrapping the stream
        static Sink from(OutputStream out)
        {
            return new Sink()
            {
                boolean closed;

                @Override
                public void write(boolean last, RetainableByteBuffer buffer, Callback callback)
                {
                    if (closed)
                    {
                        callback.failed(new EOFException());
                        return;
                    }
                    try
                    {
                        buffer.putTo(out);
                        if (last)
                        {
                            closed = true;
                            out.close();
                        }
                        callback.succeeded();
                    }
                    catch (Throwable t)
                    {
                        callback.failed(t);
                    }
                }
            };
        }

        /// Wraps the given [ByteChannel] as a [Sink].
        /// @param channel The [ByteChannel] to wrap
        /// @return A sink wrapping the stream
        static Sink from(ByteChannel channel)
        {
            return new Sink()
            {
                boolean closed;

                @Override
                public void write(boolean last, RetainableByteBuffer buffer, Callback callback)
                {
                    if (closed)
                    {
                        callback.failed(new EOFException());
                        return;
                    }
                    try
                    {
                        long remaining = buffer.remaining();
                        int tries = 0;
                        while (remaining > 0L)
                        {
                            long written = buffer.read(channel::write);
                            if (written > 0L)
                                remaining -= written;
                            else if (tries++ > 2)
                                throw new IllegalStateException("ByteChannel in async mode");
                        }

                        if (last)
                        {
                            closed = true;
                            channel.close();
                        }
                        callback.succeeded();
                    }
                    catch (Throwable t)
                    {
                        callback.failed(t);
                    }
                }
            };
        }

        /// Wraps the given [AsynchronousByteChannel] as a [Sink].
        /// @param channel The [AsynchronousByteChannel] to wrap
        /// @return A sink wrapping the stream
        static Sink from(AsynchronousByteChannel channel)
        {
            return new Sink()
            {
                boolean closed;

                @Override
                public void write(boolean last, RetainableByteBuffer buffer, Callback callback)
                {
                    if (closed)
                    {
                        callback.failed(new EOFException());
                        return;
                    }
                    try
                    {
                        buffer.retain();
                        buffer.read(new RetainableByteBuffer.Reader()
                        {
                            @Override
                            public long readFrom(ByteBuffer input)
                            {
                                return write(channel, input, this);
                            }

                            private long write(AsynchronousByteChannel channel, ByteBuffer input, RetainableByteBuffer.Reader reader)
                            {
                                channel.write(input, input, new CompletionHandler<>()
                                {
                                    @Override
                                    public void completed(Integer written, ByteBuffer byteBuffer)
                                    {
                                        if (byteBuffer.hasRemaining())
                                        {
                                            channel.write(byteBuffer, byteBuffer, this);
                                        }
                                        else
                                        {
                                            if (buffer.hasRemaining())
                                            {
                                                try
                                                {
                                                    buffer.read(reader);
                                                }
                                                catch (IOException e)
                                                {
                                                    callback.failed(e);
                                                }
                                            }
                                            else
                                            {
                                                buffer.release();
                                                if (last)
                                                {
                                                    closed = true;
                                                    IO.close(channel);
                                                }
                                                callback.succeeded();
                                            }
                                        }
                                    }

                                    @Override
                                    public void failed(Throwable x, ByteBuffer buffer1)
                                    {
                                        buffer.release();
                                        callback.failed(x);
                                    }
                                });
                                return 0;
                            }
                        });
                    }
                    catch (Throwable t)
                    {
                        callback.failed(t);
                    }
                }
            };
        }

        /// Wraps the given content sink with a buffering sink.
        ///
        /// @param sink the sink to write to
        /// @param bufferPool the [ByteBufferPool] to use
        /// @param direct true to use direct buffers, false to use heap buffers
        /// @param maxAggregationSize the maximum size that can be buffered in a single write;
        /// any size above this threshold triggers a buffer flush
        /// @param maxBufferSize the maximum size of the buffer
        /// @return a Sink that writes to the given content sink
        static Sink asBuffered(Sink sink, ByteBufferPool bufferPool, boolean direct, int maxAggregationSize, int maxBufferSize)
        {
            return new BufferedContentSink(sink, bufferPool, direct, maxAggregationSize, maxBufferSize);
        }

        /// Wraps the given content sink with an [OutputStream].
        ///
        /// @param sink the sink to write to
        /// @return an OutputStream that writes to the content sink
        static OutputStream asOutputStream(Sink sink)
        {
            return new ContentSinkOutputStream(sink);
        }

        /// Wraps the given content sink with a [Flow.Subscriber].
        ///
        /// @param sink the sink to write to
        /// @param callback the callback to notify when the Subscriber is complete
        /// @return a Subscriber that writes to the content sink
        static Flow.Subscriber<Chunk> asSubscriber(Sink sink, Callback callback)
        {
            return new ContentSinkSubscriber(sink, callback);
        }

        /// Blocking version of [#write(boolean, RetainableByteBuffer, Callback)].
        ///
        /// @param sink the sink to write to
        /// @param last whether the ByteBuffers are the last to write
        /// @param byteBuffer the ByteBuffers to write
        /// @throws IOException if the write operation fails
        static void write(Sink sink, boolean last, ByteBuffer byteBuffer) throws IOException
        {
            try (Blocker.Callback callback = Blocker.callback())
            {
                sink.write(last, RetainableByteBuffer.wrap(byteBuffer), callback);
                callback.block();
            }
        }

        /// Writes the given [String], converting it to UTF-8 bytes,
        /// notifying the [Callback] when the write operation is complete.
        ///
        /// @param last whether the String is the last to write
        /// @param utf8Content the String to write
        /// @param callback the callback to notify when the write operation is complete.
        ///                 Implementations have the same guarantees for invocation of this
        ///                 callback as for [#write(boolean, RetainableByteBuffer, Callback)].
        static void write(Sink sink, boolean last, String utf8Content, Callback callback)
        {
            sink.write(last, RetainableByteBuffer.wrap(utf8Content, StandardCharsets.UTF_8), callback);
        }

        /// Writes the given [RetainableByteBuffer], possibly asynchronously,
        /// notifying the [Callback] when the write operation is complete.
        ///
        /// The caller must not modify the buffer's bytes until the callback is completed.
        /// Implementations must retain the buffer if they need it after this method returns,
        /// and must not access its bytes after the callback is completed.
        ///
        /// Implementations guarantee that calls to this method are safely reentrant so that
        /// stack overflows are avoided in the case of mutual recursion between the execution
        /// of the [Callback] and a call to this method.
        ///
        /// Implementations report failures by failing the callback, not by throwing.
        ///
        /// @param last whether the buffer is the last to write
        /// @param buffer the [RetainableByteBuffer] to write
        /// @param callback the callback to notify when the write operation is complete
        void write(boolean last, RetainableByteBuffer buffer, Callback callback);
    }

    /// A chunk of content indicating whether it is the last chunk.
    ///
    /// Optionally, a release function may be specified (for example
    /// to release the `ByteBuffer` back into a pool), or the
    /// [#release()] method overridden.
    public interface Chunk extends Retainable
    {
        /// An empty chunk implementation.
        abstract class Empty implements Chunk
        {
            protected Empty()
            {
            }

            @Override
            public RetainableByteBuffer acquire()
            {
                return RetainableByteBuffer.empty();
            }

            @Override
            public long remaining()
            {
                return 0;
            }
        }

        /// An empty, non-last, chunk instance.
        Chunk EMPTY = new Empty()
        {
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

        /// An empty, last, chunk.
        Content.Chunk EOF = new Empty()
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

        /// Creates a chunk from the given [ByteBuffer].
        ///
        /// The returned chunk must be either [released][#release()] or [closed][#close()].
        ///
        /// @param byteBuffer the [ByteBuffer] with the data bytes
        /// @param last whether the chunk is the last
        /// @return a new chunk
        static Chunk from(ByteBuffer byteBuffer, boolean last)
        {
            if (byteBuffer.hasRemaining())
            {
                try (RetainableByteBuffer buffer = RetainableByteBuffer.wrap(byteBuffer))
                {
                    return from(buffer, last);
                }
            }
            return last ? EOF : EMPTY;
        }

        /// Creates a chunk from the given [RetainableByteBuffer].
        ///
        /// The returned chunk must be either [released][#release()] or [closed][#close()].
        ///
        /// @param buffer the [RetainableByteBuffer] with the data bytes
        /// @param last whether the chunk is the last
        /// @return a new chunk
        static Chunk from(RetainableByteBuffer buffer, boolean last)
        {
            if (buffer.hasRemaining())
                return new BufferChunk(buffer, last);
            return last ? EOF : EMPTY;
        }

        /// Creates an [`failure chunk`][Chunk#isFailure(Chunk)] with the given failure
        /// and [Chunk#isLast()] returning true.
        ///
        /// @param failure the cause of the failure
        /// @return a new [`failure chunk`][Chunk#isFailure(Chunk)]
        static Chunk from(Throwable failure)
        {
            return from(failure, true);
        }

        /// Creates an [`failure chunk`][Chunk#isFailure(Chunk)] with the given failure
        /// and given [`last`][Chunk#isLast()] state.
        ///
        /// @param failure the cause of the failure
        /// @param last true if the failure is terminal, else false for transient failure
        /// @return a new [`failure chunk`][Chunk#isFailure(Chunk)]
        static Chunk from(Throwable failure, boolean last)
        {
            return new Empty()
            {
                public Throwable getFailure()
                {
                    return failure;
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

        /// Returns the chunk that follows the given chunk.
        ///
        /// <table>
        /// <caption>Next Chunk</caption>
        /// <thead>
        ///   <tr>
        ///     <th>Input Chunk</th>
        ///     <th>Output Chunk</th>
        ///   </tr>
        /// </thead>
        /// <tbody>
        ///   <tr>
        ///     <td><code>null</code></td>
        ///     <td><code>null</code></td>
        ///   </tr>
        ///   <tr>
        ///     <td>[<code>Failure</code>][Chunk#isFailure(Chunk)] and [<code>last</code>][Chunk#isLast()]</td>
        ///     <td>[<code>Error</code>][Error]</td>
        ///   </tr>
        ///   <tr>
        ///     <td>[<code>Failure</code>][Chunk#isFailure(Chunk)] and [<code>not last</code>][Chunk#isLast()]</td>
        ///     <td><code>null</code></td>
        ///   </tr>
        ///   <tr>
        ///     <td>[#isLast()]</td>
        ///     <td>[#EOF]</td>
        ///   </tr>
        ///   <tr>
        ///     <td>any other Chunk</td>
        ///     <td><code>null</code></td>
        ///   </tr>
        /// </tbody>
        /// </table>
        static Chunk next(Chunk chunk)
        {
            if (chunk == null)
                return null;
            if (Content.Chunk.isFailure(chunk))
            {
                // Handle transient failure.
                if (!chunk.isLast())
                    return null;

                // Handle persistent failure returning a new exception to
                // avoid problems with try-with-resources and addSuppressed().
                return new Empty()
                {
                    private Throwable failure;

                    @Override
                    public Throwable getFailure()
                    {
                        if (failure == null)
                            failure = new IOException(chunk.getFailure());
                        return failure;
                    }

                    @Override
                    public boolean isLast()
                    {
                        return true;
                    }

                    @Override
                    public String toString()
                    {
                        return String.format("%s@%x{failure=%s}", TypeUtil.toShortName(getClass()), hashCode(), chunk.getFailure());
                    }
                };
            }
            if (chunk.isLast())
                return EOF;
            return null;
        }

        /// Convenience method to release a chunk and return [#next(Chunk)].
        /// Equivalent to:
        /// ```
        /// if (chunk != null)
        /// {
        ///     chunk.release();
        ///     chunk = Chunk.next(chunk);
        /// }
        /// ```
        /// @param chunk The chunk to release or `null`
        /// @return The [#next(Chunk)] chunk;
        static Chunk releaseAndNext(Chunk chunk)
        {
            if (chunk == null)
                return null;
            chunk.release();
            return next(chunk);
        }

        /// @param chunk The chunk to test for an [`failure`][Chunk#getFailure()].
        /// @return True if the chunk is non-null and [`chunk.getError()`][Chunk#getFailure()] returns non-null.
        static boolean isFailure(Chunk chunk)
        {
            return chunk != null && chunk.getFailure() != null;
        }

        /// @param chunk The chunk to test for an [`failure`][Chunk#getFailure()]
        /// @param last The [`last`][Chunk#isLast()] status to test for.
        /// @return True if the chunk is non-null and [Chunk#getFailure()] returns non-null
        ///         and [Chunk#isLast()] matches the passed status.
        static boolean isFailure(Chunk chunk, boolean last)
        {
            return chunk != null && chunk.getFailure() != null && chunk.isLast() == last;
        }

        /// Returns the failure (which may be [fatal][Source#fail(Throwable)] or
        /// [transient][Source#fail(Throwable, boolean)]), if any, associated with the chunk.
        ///
        /// - A chunk must not have a failure and a buffer with content.
        /// - A chunk with a failure may or may not be [fatal][#isLast()].
        ///
        /// @return the failure associated with this chunk or `null` if there is no failure
        /// @see Source#fail(Throwable)
        /// @see Source#fail(Throwable, boolean)
        default Throwable getFailure()
        {
            return null;
        }

        /// @return whether this is the last chunk
        boolean isLast();

        /// Acquires the [RetainableByteBuffer] with the data bytes of this chunk.
        /// The buffer is retained and must be released or closed.
        ///
        /// @return the [RetainableByteBuffer] with the data bytes of this chunk
        RetainableByteBuffer acquire();

        /// @return the number of bytes remaining in this chunk.
        long remaining();

        /// @return whether this chunk has bytes remaining
        default boolean hasRemaining()
        {
            return remaining() > 0;
        }

        /// Implementations of this interface may process [Chunk]s being copied by the
        /// [Content#copy(Source, Sink, Processor, Callback)] method, so that
        /// [Chunk]s of unknown types can be copied.
        ///
        /// @see Content#copy(Source, Sink, Processor, Callback)
        interface Processor
        {
            /// Processes the given [Chunk], possibly asynchronously.
            ///
            /// Implementations that return `true` process the [Chunk]
            /// and eventually complete the [Callback].
            ///
            /// If the processing is asynchronous, implementations must [retain][Chunk#retain()]
            /// the chunk, and arrange to [release][Chunk#release()] the chunk when the processing
            /// is complete, typically by wrapping the [Callback].
            ///
            /// Succeeding the [Callback] resumes the processing of subsequent chunks.
            /// Failing the [Callback] fails the [Source] from which the [Chunk] was read.
            /// Exceptions thrown by this method are treated as failing the callback.
            ///
            /// Implementations that return `false` do not process the [Chunk]
            /// and do not complete the [Callback].
            ///
            /// @param chunk the [Chunk] to be considered for processing, including persistent and transient failures.
            /// @param callback the callback to complete when the chunk has been processed
            /// @return whether the chunk is accepted for processing
            boolean process(Chunk chunk, Callback callback);
        }
    }
}
