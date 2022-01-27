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

package org.eclipse.jetty.proxy;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.Destroyable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A specialized transformer for {@link AsyncMiddleManServlet} that performs
 * the transformation when the whole content has been received.</p>
 * <p>The content is buffered in memory up to a configurable {@link #getMaxInputBufferSize() maximum size},
 * after which it is overflown to a file on disk. The overflow file is saved
 * in the {@link #getOverflowDirectory() overflow directory} as a
 * {@link Files#createTempFile(Path, String, String, FileAttribute[]) temporary file}
 * with a name starting with the {@link #getInputFilePrefix() input prefix}
 * and default suffix.</p>
 * <p>Application must implement the {@link #transform(Source, Sink) transformation method}
 * to transform the content.</p>
 * <p>The transformed content is buffered in memory up to a configurable {@link #getMaxOutputBufferSize() maximum size}
 * after which it is overflown to a file on disk. The overflow file is saved
 * in the {@link #getOverflowDirectory() overflow directory} as a
 * {@link Files#createTempFile(Path, String, String, FileAttribute[]) temporary file}
 * with a name starting with the {@link #getOutputFilePrefix()} output prefix}
 * and default suffix.</p>
 */
public abstract class AfterContentTransformer implements AsyncMiddleManServlet.ContentTransformer, Destroyable
{
    private static final Logger LOG = LoggerFactory.getLogger(AfterContentTransformer.class);

    private final List<ByteBuffer> sourceBuffers = new ArrayList<>();
    private Path overflowDirectory = Paths.get(System.getProperty("java.io.tmpdir"));
    private String inputFilePrefix = "amms_adct_in_";
    private String outputFilePrefix = "amms_adct_out_";
    private long maxInputBufferSize = 1024 * 1024;
    private long inputBufferSize;
    private FileChannel inputFile;
    private long maxOutputBufferSize = maxInputBufferSize;
    private long outputBufferSize;
    private FileChannel outputFile;

    /**
     * <p>Returns the directory where input and output are overflown to
     * temporary files if they exceed, respectively, the
     * {@link #getMaxInputBufferSize() max input size} or the
     * {@link #getMaxOutputBufferSize() max output size}.</p>
     * <p>Defaults to the directory pointed by the {@code java.io.tmpdir}
     * system property.</p>
     *
     * @return the overflow directory path
     * @see #setOverflowDirectory(Path)
     */
    public Path getOverflowDirectory()
    {
        return overflowDirectory;
    }

    /**
     * @param overflowDirectory the overflow directory path
     * @see #getOverflowDirectory()
     */
    public void setOverflowDirectory(Path overflowDirectory)
    {
        this.overflowDirectory = overflowDirectory;
    }

    /**
     * @return the prefix of the input overflow temporary files
     * @see #setInputFilePrefix(String)
     */
    public String getInputFilePrefix()
    {
        return inputFilePrefix;
    }

    /**
     * @param inputFilePrefix the prefix of the input overflow temporary files
     * @see #getInputFilePrefix()
     */
    public void setInputFilePrefix(String inputFilePrefix)
    {
        this.inputFilePrefix = inputFilePrefix;
    }

    /**
     * <p>Returns the maximum input buffer size, after which the input is overflown to disk.</p>
     * <p>Defaults to 1 MiB, i.e. 1048576 bytes.</p>
     *
     * @return the max input buffer size
     * @see #setMaxInputBufferSize(long)
     */
    public long getMaxInputBufferSize()
    {
        return maxInputBufferSize;
    }

    /**
     * @param maxInputBufferSize the max input buffer size
     * @see #getMaxInputBufferSize()
     */
    public void setMaxInputBufferSize(long maxInputBufferSize)
    {
        this.maxInputBufferSize = maxInputBufferSize;
    }

    /**
     * @return the prefix of the output overflow temporary files
     * @see #setOutputFilePrefix(String)
     */
    public String getOutputFilePrefix()
    {
        return outputFilePrefix;
    }

    /**
     * @param outputFilePrefix the prefix of the output overflow temporary files
     * @see #getOutputFilePrefix()
     */
    public void setOutputFilePrefix(String outputFilePrefix)
    {
        this.outputFilePrefix = outputFilePrefix;
    }

    /**
     * <p>Returns the maximum output buffer size, after which the output is overflown to disk.</p>
     * <p>Defaults to 1 MiB, i.e. 1048576 bytes.</p>
     *
     * @return the max output buffer size
     * @see #setMaxOutputBufferSize(long)
     */
    public long getMaxOutputBufferSize()
    {
        return maxOutputBufferSize;
    }

    /**
     * @param maxOutputBufferSize the max output buffer size
     */
    public void setMaxOutputBufferSize(long maxOutputBufferSize)
    {
        this.maxOutputBufferSize = maxOutputBufferSize;
    }

    @Override
    public final void transform(ByteBuffer input, boolean finished, List<ByteBuffer> output) throws IOException
    {
        int remaining = input.remaining();
        if (remaining > 0)
        {
            inputBufferSize += remaining;
            long max = getMaxInputBufferSize();
            if (max >= 0 && inputBufferSize > max)
            {
                overflow(input);
            }
            else
            {
                ByteBuffer copy = ByteBuffer.allocate(input.remaining());
                copy.put(input).flip();
                sourceBuffers.add(copy);
            }
        }

        if (finished)
        {
            Source source = new Source();
            Sink sink = new Sink();
            if (transform(source, sink))
                sink.drainTo(output);
            else
                source.drainTo(output);
        }
    }

    /**
     * <p>Transforms the original content read from the {@code source} into
     * transformed content written to the {@code sink}.</p>
     * <p>The transformation must happen synchronously in the context of a call
     * to this method (it is not supported to perform the transformation in another
     * thread spawned during the call to this method).</p>
     * <p>Differently from {@link #transform(ByteBuffer, boolean, List)}, this
     * method is invoked only when the whole content is available, and offers
     * a blocking API via the InputStream and OutputStream that can be obtained
     * from {@link Source} and {@link Sink} respectively.</p>
     * <p>Implementations may read the source, inspect the input bytes and decide
     * that no transformation is necessary, and therefore the source must be copied
     * unchanged to the sink. In such case, the implementation must return false to
     * indicate that it wishes to just pipe the bytes from the source to the sink.</p>
     * <p>Typical implementations:</p>
     * <pre>
     * // Identity transformation (no transformation, the input is copied to the output)
     * public boolean transform(Source source, Sink sink)
     * {
     *     org.eclipse.jetty.util.IO.copy(source.getInputStream(), sink.getOutputStream());
     *     return true;
     * }
     * </pre>
     *
     * @param source where the original content is read
     * @param sink where the transformed content is written
     * @return true if the transformation happened and the transformed bytes have
     * been written to the sink, false if no transformation happened and the source
     * must be copied to the sink.
     * @throws IOException if the transformation fails
     */
    public abstract boolean transform(Source source, Sink sink) throws IOException;

    private void overflow(ByteBuffer input) throws IOException
    {
        if (inputFile == null)
        {
            Path path = Files.createTempFile(getOverflowDirectory(), getInputFilePrefix(), null);
            inputFile = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.DELETE_ON_CLOSE);
            int size = sourceBuffers.size();
            if (size > 0)
            {
                ByteBuffer[] buffers = sourceBuffers.toArray(new ByteBuffer[size]);
                sourceBuffers.clear();
                IO.write(inputFile, buffers, 0, buffers.length);
            }
        }
        inputFile.write(input);
    }

    @Override
    public void destroy()
    {
        close(inputFile);
        close(outputFile);
    }

    private void drain(FileChannel file, List<ByteBuffer> output) throws IOException
    {
        long position = 0;
        long length = file.size();
        file.position(position);
        while (length > 0)
        {
            // At most 1 GiB file maps.
            long size = Math.min(1024 * 1024 * 1024, length);
            ByteBuffer buffer = file.map(FileChannel.MapMode.READ_ONLY, position, size);
            output.add(buffer);
            position += size;
            length -= size;
        }
    }

    private void close(Closeable closeable)
    {
        try
        {
            if (closeable != null)
                closeable.close();
        }
        catch (IOException x)
        {
            LOG.trace("IGNORED", x);
        }
    }

    /**
     * <p>The source from where the original content is read to be transformed.</p>
     * <p>The {@link #getInputStream() input stream} provided by this
     * class supports the {@link InputStream#reset()} method so that
     * the stream can be rewound to the beginning.</p>
     */
    public class Source
    {
        private final InputStream stream;

        private Source() throws IOException
        {
            if (inputFile != null)
            {
                inputFile.force(true);
                stream = new ChannelInputStream();
            }
            else
            {
                stream = new MemoryInputStream();
            }
            stream.reset();
        }

        /**
         * @return an input stream to read the original content from
         */
        public InputStream getInputStream()
        {
            return stream;
        }

        private void drainTo(List<ByteBuffer> output) throws IOException
        {
            if (inputFile == null)
            {
                output.addAll(sourceBuffers);
                sourceBuffers.clear();
            }
            else
            {
                drain(inputFile, output);
            }
        }
    }

    private class ChannelInputStream extends InputStream
    {
        private final InputStream stream = Channels.newInputStream(inputFile);

        @Override
        public int read(byte[] b, int off, int len) throws IOException
        {
            return stream.read(b, off, len);
        }

        @Override
        public int read() throws IOException
        {
            return stream.read();
        }

        @Override
        public void reset() throws IOException
        {
            inputFile.position(0);
        }
    }

    private class MemoryInputStream extends InputStream
    {
        private final byte[] oneByte = new byte[1];
        private int index;
        private ByteBuffer slice;

        @Override
        public int read(byte[] b, int off, int len) throws IOException
        {
            if (len == 0)
                return 0;
            if (index == sourceBuffers.size())
                return -1;

            if (slice == null)
                slice = sourceBuffers.get(index).slice();

            int size = Math.min(len, slice.remaining());
            slice.get(b, off, size);

            if (!slice.hasRemaining())
            {
                ++index;
                slice = null;
            }

            return size;
        }

        @Override
        public int read() throws IOException
        {
            int read = read(oneByte, 0, 1);
            return read < 0 ? read : oneByte[0] & 0xFF;
        }

        @Override
        public void reset() throws IOException
        {
            index = 0;
            slice = null;
        }
    }

    /**
     * <p>The target to where the transformed content is written after the transformation.</p>
     */
    public class Sink
    {
        private final List<ByteBuffer> sinkBuffers = new ArrayList<>();
        private final OutputStream stream = new SinkOutputStream();

        /**
         * @return an output stream to write the transformed content to
         */
        public OutputStream getOutputStream()
        {
            return stream;
        }

        private void overflow(ByteBuffer output) throws IOException
        {
            if (outputFile == null)
            {
                Path path = Files.createTempFile(getOverflowDirectory(), getOutputFilePrefix(), null);
                outputFile = FileChannel.open(path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.DELETE_ON_CLOSE);
                int size = sinkBuffers.size();
                if (size > 0)
                {
                    ByteBuffer[] buffers = sinkBuffers.toArray(new ByteBuffer[size]);
                    sinkBuffers.clear();

                    IO.write(outputFile, buffers, 0, buffers.length);
                }
            }
            outputFile.write(output);
        }

        private void drainTo(List<ByteBuffer> output) throws IOException
        {
            if (outputFile == null)
            {
                output.addAll(sinkBuffers);
                sinkBuffers.clear();
            }
            else
            {
                outputFile.force(true);
                drain(outputFile, output);
            }
        }

        private class SinkOutputStream extends OutputStream
        {
            @Override
            public void write(byte[] b, int off, int len) throws IOException
            {
                if (len <= 0)
                    return;

                outputBufferSize += len;
                long max = getMaxOutputBufferSize();
                if (max >= 0 && outputBufferSize > max)
                {
                    overflow(ByteBuffer.wrap(b, off, len));
                }
                else
                {
                    // The array may be reused by the
                    // application so we need to copy it.
                    byte[] copy = new byte[len];
                    System.arraycopy(b, off, copy, 0, len);
                    sinkBuffers.add(ByteBuffer.wrap(copy));
                }
            }

            @Override
            public void write(int b) throws IOException
            {
                write(new byte[]{(byte)b}, 0, 1);
            }
        }
    }
}
