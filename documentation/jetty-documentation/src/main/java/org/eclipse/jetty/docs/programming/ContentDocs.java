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

package org.eclipse.jetty.docs.programming;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.CompletableTask;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.System.Logger.Level.INFO;

@SuppressWarnings("unused")
public class ContentDocs
{
    private static final Logger LOG = LoggerFactory.getLogger(ContentDocs.class);

    @SuppressWarnings("unused")
    class Idiomatic
    {
        // tag::idiomatic[]
        public void read(Content.Source source)
        {
            // Read from the source in a loop.
            while (true)
            {
                // Read a chunk, must be eventually released.
                Content.Chunk chunk = source.read(); // <1>

                // If no chunk, demand to be called back when there are more chunks.
                if (chunk == null)
                {
                    source.demand(() -> read(source));
                    return;
                }

                // If there is a failure reading, handle it.
                if (Content.Chunk.isFailure(chunk))
                {
                    boolean fatal = chunk.isLast();
                    if (fatal)
                    {
                        // A fatal failure, such as a network failure.
                        handleFatalFailure(chunk.getFailure());
                        // No recovery is possible, stop reading
                        // by returning without demanding.
                        return;
                    }
                    else
                    {
                        // A transient failure such as a read timeout.
                        handleTransientFailure(chunk.getFailure());
                        // Recovery is possible, try to read again.
                        continue;
                    }
                }

                // A normal chunk of content, consume it.
                consume(chunk);

                // Release the chunk.
                chunk.release(); // <2>

                // Stop reading if EOF was reached.
                if (chunk.isLast())
                    return;

                // Loop around to read another chunk.
            }
        }
        // end::idiomatic[]
    }

    @SuppressWarnings("unused")
    static class Async
    {
        // tag::async[]
        public void read(Content.Source source)
        {
            // Read a chunk, must be eventually released.
            Content.Chunk chunk = source.read(); // <1>

            // If no chunk, demand to be called back when there are more chunks.
            if (chunk == null)
            {
                source.demand(() -> read(source));
                return;
            }

            // If there is a failure reading, always treat it as fatal.
            if (Content.Chunk.isFailure(chunk))
            {
                // If the failure is transient, fail the source
                // to indicate that there will be no more reads.
                if (!chunk.isLast())
                    source.fail(chunk.getFailure());

                // Handle the failure and stop reading by not demanding.
                handleFatalFailure(chunk.getFailure());
                return;
            }

            // Consume the chunk asynchronously, and do not
            // read more chunks until this has been consumed.
            CompletableFuture<Void> consumed = consumeAsync(chunk);

            // Release the chunk.
            chunk.release(); // <2>

            // Only when the chunk has been consumed try to read more.
            consumed.whenComplete((result, failure) ->
            {
                if (failure == null)
                {
                    // Continue reading if EOF was not reached.
                    if (!chunk.isLast())
                        source.demand(() -> read(source));
                }
                else
                {
                    // If there is a failure reading, handle it,
                    // and stop reading by not demanding.
                    handleFatalFailure(failure);
                }
            });
        }
        // end::async[]

        private CompletableFuture<Void> consumeAsync(Content.Chunk chunk)
        {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static void handleFatalFailure(Throwable failure)
    {
    }

    private static void handleTransientFailure(Throwable failure)
    {
    }

    private void consume(Content.Chunk chunk)
    {
    }

    @SuppressWarnings("unused")
    static class ChunkSync
    {
        private FileChannel fileChannel;

        // tag::chunkSync[]
        public void consume(Content.Chunk chunk) throws IOException
        {
            // Consume the chunk synchronously within this method.

            // For example, parse the bytes into other objects,
            // or copy the bytes elsewhere (e.g. the file system).
            fileChannel.write(chunk.getByteBuffer());

            if (chunk.isLast())
                fileChannel.close();
        }
        // end::chunkSync[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::chunkAsync[]
    // CompletableTask is-a CompletableFuture.
    public class ChunksToString extends CompletableTask<String>
    {
        private final List<Content.Chunk> chunks = new ArrayList<>();
        private final Content.Source source;

        public ChunksToString(Content.Source source)
        {
            this.source = source;
        }

        @Override
        public void run()
        {
            while (true)
            {
                // Read a chunk, must be eventually released.
                Content.Chunk chunk = source.read(); // <1>

                if (chunk == null)
                {
                    source.demand(this);
                    return;
                }

                if (Content.Chunk.isFailure(chunk))
                {
                    handleFatalFailure(chunk.getFailure());
                    return;
                }

                // A normal chunk of content, consume it.
                consume(chunk);

                // Release the chunk.
                // This pairs the call to read() above.
                chunk.release(); // <2>

                if (chunk.isLast())
                {
                    // Produce the result.
                    String result = getResult();

                    // Complete this CompletableFuture with the result.
                    complete(result);

                    // The reading is complete.
                    return;
                }
            }
        }

        public void consume(Content.Chunk chunk)
        {
            // The chunk is not consumed within this method, but
            // stored away for later use, so it must be retained.
            chunk.retain(); // <3>
            chunks.add(chunk);
        }

        public String getResult()
        {
            Utf8StringBuilder builder = new Utf8StringBuilder();
            // Iterate over the chunks, copying and releasing.
            for (Content.Chunk chunk : chunks)
            {
                // Copy the chunk bytes into the builder.
                builder.append(chunk.getByteBuffer());

                // The chunk has been consumed, release it.
                // This pairs the retain() in consume().
                chunk.release(); // <4>
            }
            return builder.toCompleteString();
        }
    }
    // end::chunkAsync[]

    static class SinkWrong
    {
        // tag::sinkWrong[]
        public void wrongWrite(Content.Sink sink, ByteBuffer content1, ByteBuffer content2)
        {
            // Initiate a first write.
            sink.write(false, content1, Callback.NOOP);

            // WRONG! Cannot initiate a second write before the first is complete.
            sink.write(true, content2, Callback.NOOP);
        }
        // end::sinkWrong[]
    }

    static class SinkMany
    {
        // tag::sinkMany[]
        public void manyWrites(Content.Sink sink, ByteBuffer content1, ByteBuffer content2)
        {
            // Initiate a first write.
            Callback.Completable resultOfWrites = Callback.Completable.with(callback1 -> sink.write(false, content1, callback1))
                // Chain a second write only when the first is complete.
                .compose(callback2 -> sink.write(true, content2, callback2));

            // Use the resulting Callback.Completable as you would use a CompletableFuture.
            // For example:
            resultOfWrites.whenComplete((ignored, failure) ->
            {
                if (failure == null)
                    System.getLogger("sink").log(INFO, "writes completed successfully");
                else
                    System.getLogger("sink").log(INFO, "writes failed", failure);
            });
        }
        // end::sinkMany[]
    }

    // tag::copy[]
    @SuppressWarnings("InnerClassMayBeStatic")
    class Copy extends IteratingCallback
    {
        private final Content.Source source;
        private final Content.Sink sink;
        private final Callback callback;
        private Content.Chunk chunk;

        public Copy(Content.Source source, Content.Sink sink, Callback callback)
        {
            this.source = source;
            this.sink = sink;
            // The callback to notify when the copy is completed.
            this.callback = callback;
        }

        @Override
        protected Action process() throws Throwable
        {
            // If the last write completed, succeed this IteratingCallback,
            // causing onCompleteSuccess() to be invoked.
            if (chunk != null && chunk.isLast())
                return Action.SUCCEEDED;

            // Read a chunk.
            chunk = source.read();

            // No chunk, demand to be called back when there will be more chunks.
            if (chunk == null)
            {
                source.demand(this::iterate);
                return Action.IDLE;
            }

            // The read failed, re-throw the failure
            // causing onCompleteFailure() to be invoked.
            if (Content.Chunk.isFailure(chunk))
                throw chunk.getFailure();

            // Copy the chunk.
            sink.write(chunk.isLast(), chunk.getByteBuffer(), this);
            return Action.SCHEDULED;
        }

        @Override
        public void succeeded()
        {
            // After every successful write, release the chunk.
            chunk.release();
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            super.failed(x);
        }

        @Override
        protected void onCompleteSuccess()
        {
            // The copy is succeeded, succeed the callback.
            callback.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable failure)
        {
            // In case of a failure, either on the
            // read or on the write, release the chunk.
            chunk.release();

            // The copy is failed, fail the callback.
            callback.failed(failure);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }
    }
    // end::copy[]

    static class Blocking
    {
        // tag::blocking[]
        public void blockingWrite(Content.Sink sink, ByteBuffer content1, ByteBuffer content2) throws IOException
        {
            // First blocking write, returns only when the write is complete.
            Content.Sink.write(sink, false, content1);

            // Second blocking write, returns only when the write is complete.
            // It is legal to perform the writes sequentially, since they are blocking.
            Content.Sink.write(sink, true, content2);
        }
        // end::blocking[]
    }
}
