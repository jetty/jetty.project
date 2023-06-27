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

package org.eclipse.jetty.http;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ContentSourceCompletableFuture;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * <p>A {@link CompletableFuture} that is completed when a multipart/form-data content
 * has been parsed asynchronously from a {@link Content.Source}.</p>
 * <p>Once the parsing of the multipart/form-data content completes successfully,
 * objects of this class are completed with a {@link Parts} object.</p>
 * <p>Objects of this class may be configured to save multipart files in a configurable
 * directory, and configure the max size of such files, etc.</p>
 * <p>Typical usage:</p>
 * <pre>{@code
 * // Some headers that include Content-Type.
 * HttpFields headers = ...;
 * String boundary = MultiPart.extractBoundary(headers.get(HttpHeader.CONTENT_TYPE));
 *
 * // Some multipart/form-data content.
 * Content.Source content = ...;
 *
 * // Create and configure MultiPartFormData.
 * MultiPartFormData formData = new MultiPartFormData(boundary);
 * // Where to store the files.
 * formData.setFilesDirectory(Path.of("/tmp"));
 * // Max 1 MiB files.
 * formData.setMaxFileSize(1024 * 1024);
 *
 * // Parse the content.
 * formData.parse(content)
 *     // When complete, use the parts.
 *     .thenAccept(parts -> ...);
 * }</pre>
 *
 * @see Parts
 */
public class MultiPartFormData extends ContentSourceCompletableFuture<MultiPartFormData.Parts>
{
    // TODO base the implementation on a ContentSourceCompletableFuture

    private static final Logger LOG = LoggerFactory.getLogger(MultiPartFormData.class);

    private final PartsListener listener = new PartsListener();
    private final MultiPart.Parser parser;
    private boolean useFilesForPartsWithoutFileName;
    private Path filesDirectory;
    private long maxFileSize = -1;
    private long maxMemoryFileSize;
    private long maxLength = -1;
    private long length;
    private Parts parts;

    public MultiPartFormData(Content.Source source, String boundary)
    {
        super(source);
        parser = new MultiPart.Parser(Objects.requireNonNull(boundary), listener);
    }

    /**
     * @return the boundary string
     */
    public String getBoundary()
    {
        return parser.getBoundary();
    }

    /**
     * <p>Parses the given chunk containing multipart/form-data bytes.</p>
     * <p>One or more chunks may be passed to this method, until the parsing
     * of the multipart/form-data content completes.</p>
     *
     * @param chunk the {@link Content.Chunk} to parse.
     */
    @Override
    protected MultiPartFormData.Parts parse(Content.Chunk chunk)
    {
        if (listener.isFailed())
            return null;
        length += chunk.getByteBuffer().remaining();
        long max = getMaxLength();
        if (max > 0 && length > max)
            throw new IllegalStateException("max length exceeded: %d".formatted(max));
        parser.parse(chunk);
        return parts;
    }

    /**
     * <p>Returns the default charset as specified by
     * <a href="https://datatracker.ietf.org/doc/html/rfc7578#section-4.6">RFC 7578, section 4.6</a>,
     * that is the charset specified by the part named {@code _charset_}.</p>
     * <p>If that part is not present, returns {@code null}.</p>
     *
     * @return the default charset specified by the {@code _charset_} part,
     * or null if that part is not present
     */
    public Charset getDefaultCharset()
    {
        return listener.getDefaultCharset();
    }

    /**
     * @return the max length of a {@link MultiPart.Part} headers, in bytes, or -1 for unlimited length
     */
    public int getPartHeadersMaxLength()
    {
        return parser.getPartHeadersMaxLength();
    }

    /**
     * @param partHeadersMaxLength the max length of a {@link MultiPart.Part} headers, in bytes, or -1 for unlimited length
     */
    public void setPartHeadersMaxLength(int partHeadersMaxLength)
    {
        parser.setPartHeadersMaxLength(partHeadersMaxLength);
    }

    /**
     * @return whether parts without fileName may be stored as files
     */
    public boolean isUseFilesForPartsWithoutFileName()
    {
        return useFilesForPartsWithoutFileName;
    }

    /**
     * @param useFilesForPartsWithoutFileName whether parts without fileName may be stored as files
     */
    public void setUseFilesForPartsWithoutFileName(boolean useFilesForPartsWithoutFileName)
    {
        this.useFilesForPartsWithoutFileName = useFilesForPartsWithoutFileName;
    }

    /**
     * @return the directory where files are saved
     */
    public Path getFilesDirectory()
    {
        return filesDirectory;
    }

    /**
     * <p>Sets the directory where the files uploaded in the parts will be saved.</p>
     *
     * @param filesDirectory the directory where files are saved
     */
    public void setFilesDirectory(Path filesDirectory)
    {
        this.filesDirectory = filesDirectory;
    }

    /**
     * @return the maximum file size in bytes, or -1 for unlimited file size
     */
    public long getMaxFileSize()
    {
        return maxFileSize;
    }

    /**
     * @param maxFileSize the maximum file size in bytes, or -1 for unlimited file size
     */
    public void setMaxFileSize(long maxFileSize)
    {
        this.maxFileSize = maxFileSize;
    }

    /**
     * @return the maximum memory file size in bytes, or -1 for unlimited memory file size
     */
    public long getMaxMemoryFileSize()
    {
        return maxMemoryFileSize;
    }

    /**
     * <p>Sets the maximum memory file size in bytes, after which files will be saved
     * in the directory specified by {@link #setFilesDirectory(Path)}.</p>
     * <p>Use value {@code 0} to always save the files in the directory.</p>
     * <p>Use value {@code -1} to never save the files in the directory.</p>
     *
     * @param maxMemoryFileSize the maximum memory file size in bytes, or -1 for unlimited memory file size
     */
    public void setMaxMemoryFileSize(long maxMemoryFileSize)
    {
        this.maxMemoryFileSize = maxMemoryFileSize;
    }

    /**
     * @return the maximum length in bytes of the whole multipart content, or -1 for unlimited length
     */
    public long getMaxLength()
    {
        return maxLength;
    }

    /**
     * @param maxLength the maximum length in bytes of the whole multipart content, or -1 for unlimited length
     */
    public void setMaxLength(long maxLength)
    {
        this.maxLength = maxLength;
    }

    /**
     * @return the maximum number of parts that can be parsed from the multipart content.
     */
    public long getMaxParts()
    {
        return parser.getMaxParts();
    }

    /**
     * @param maxParts the maximum number of parts that can be parsed from the multipart content.
     */
    public void setMaxParts(long maxParts)
    {
        parser.setMaxParts(maxParts);
    }

    @Override
    public boolean completeExceptionally(Throwable failure)
    {
        listener.fail(failure);
        return super.completeExceptionally(failure);
    }

    // Only used for testing.
    int getPartsSize()
    {
        return listener.getPartsSize();
    }

    /**
     * <p>An ordered list of {@link MultiPart.Part}s that can
     * be accessed by index or by name, or iterated over.</p>
     */
    public class Parts implements Iterable<MultiPart.Part>, Closeable
    {
        private final List<MultiPart.Part> parts;

        private Parts(List<MultiPart.Part> parts)
        {
            this.parts = parts;
        }

        public MultiPartFormData getMultiPartFormData()
        {
            return MultiPartFormData.this;
        }

        /**
         * <p>Returns the {@link MultiPart.Part}  at the given index, a number
         * between {@code 0} included and the value returned by {@link #size()}
         * excluded.</p>
         *
         * @param index the index of the {@code MultiPart.Part} to return
         * @return the {@code MultiPart.Part} at the given index
         */
        public MultiPart.Part get(int index)
        {
            return parts.get(index);
        }

        /**
         * <p>Returns the first {@link MultiPart.Part} with the given name, or
         * {@code null} if no {@code MultiPart.Part} with that name exists.</p>
         *
         * @param name the {@code MultiPart.Part} name
         * @return the first {@code MultiPart.Part} with the given name, or {@code null}
         */
        public MultiPart.Part getFirst(String name)
        {
            return parts.stream()
                .filter(part -> part.getName().equals(name))
                .findFirst()
                .orElse(null);
        }

        /**
         * <p>Returns all the {@link MultiPart.Part}s with the given name.</p>
         *
         * @param name the {@code MultiPart.Part}s name
         * @return all the {@code MultiPart.Part}s with the given name
         */
        public List<MultiPart.Part> getAll(String name)
        {
            return parts.stream()
                .filter(part -> part.getName().equals(name))
                .toList();
        }

        /**
         * @return the number of parts
         * @see #get(int)
         */
        public int size()
        {
            return parts.size();
        }

        @Override
        public Iterator<MultiPart.Part> iterator()
        {
            return parts.iterator();
        }

        @Override
        public void close()
        {
            for (MultiPart.Part p : parts)
            {
                IO.close(p);
            }
        }
    }

    /**
     * <p>The multipart/form-data specific content source.</p>
     *
     * @see MultiPart.AbstractContentSource
     */
    public static class ContentSource extends MultiPart.AbstractContentSource
    {
        public ContentSource(String boundary)
        {
            super(boundary);
        }

        protected HttpFields customizePartHeaders(MultiPart.Part part)
        {
            HttpFields headers = super.customizePartHeaders(part);
            if (headers.contains(HttpHeader.CONTENT_DISPOSITION))
                return headers;
            String value = "form-data";
            String name = part.getName();
            if (name != null)
                value += "; name=" + QuotedCSV.quote(name);
            String fileName = part.getFileName();
            if (fileName != null)
                value += "; filename=" + QuotedCSV.quote(fileName);
            return HttpFields.build(headers).put(HttpHeader.CONTENT_DISPOSITION, value);
        }
    }

    private class PartsListener extends MultiPart.AbstractPartsListener
    {
        private final AutoLock lock = new AutoLock();
        private final List<MultiPart.Part> parts = new ArrayList<>();
        private final List<Content.Chunk> partChunks = new ArrayList<>();
        private long fileSize;
        private long memoryFileSize;
        private Path filePath;
        private SeekableByteChannel fileChannel;
        private Throwable failure;

        @Override
        public void onPartContent(Content.Chunk chunk)
        {
            ByteBuffer buffer = chunk.getByteBuffer();
            String fileName = getFileName();
            if (fileName != null || isUseFilesForPartsWithoutFileName())
            {
                long maxFileSize = getMaxFileSize();
                fileSize += buffer.remaining();
                if (maxFileSize >= 0 && fileSize > maxFileSize)
                {
                    onFailure(new IllegalStateException("max file size exceeded: %d".formatted(maxFileSize)));
                    return;
                }

                long maxMemoryFileSize = getMaxMemoryFileSize();
                if (maxMemoryFileSize >= 0)
                {
                    memoryFileSize += buffer.remaining();
                    if (memoryFileSize > maxMemoryFileSize)
                    {
                        try
                        {
                            // Must save to disk.
                            if (ensureFileChannel())
                            {
                                // Write existing memory chunks.
                                List<Content.Chunk> partChunks;
                                try (AutoLock ignored = lock.lock())
                                {
                                    partChunks = List.copyOf(this.partChunks);
                                }
                                for (Content.Chunk c : partChunks)
                                {
                                    write(c.getByteBuffer());
                                }
                            }
                            write(buffer);
                            if (chunk.isLast())
                                close();
                        }
                        catch (Throwable x)
                        {
                            onFailure(x);
                        }

                        try (AutoLock ignored = lock.lock())
                        {
                            partChunks.forEach(Content.Chunk::release);
                            partChunks.clear();
                        }
                        return;
                    }
                }
            }
            // Retain the chunk because it is stored for later use.
            chunk.retain();
            try (AutoLock ignored = lock.lock())
            {
                partChunks.add(chunk);
            }
        }

        private void write(ByteBuffer buffer) throws Exception
        {
            int remaining = buffer.remaining();
            while (remaining > 0)
            {
                SeekableByteChannel channel = fileChannel();
                if (channel == null)
                    throw new IllegalStateException();
                int written = channel.write(buffer);
                if (written == 0)
                    throw new NonWritableChannelException();
                remaining -= written;
            }
        }

        private void close()
        {
            try
            {
                Closeable closeable = fileChannel();
                if (closeable != null)
                    closeable.close();
            }
            catch (Throwable x)
            {
                onFailure(x);
            }
        }

        @Override
        public void onPart(String name, String fileName, HttpFields headers)
        {
            fileSize = 0;
            memoryFileSize = 0;
            try (AutoLock ignored = lock.lock())
            {
                MultiPart.Part part;
                if (fileChannel != null)
                    part = new MultiPart.PathPart(name, fileName, headers, filePath);
                else
                    part = new MultiPart.ChunksPart(name, fileName, headers, List.copyOf(partChunks));
                // Reset part-related state.
                filePath = null;
                fileChannel = null;
                partChunks.forEach(Content.Chunk::release);
                partChunks.clear();
                // Store the new part.
                parts.add(part);
            }
        }

        @Override
        public void onComplete()
        {
            super.onComplete();
            List<MultiPart.Part> result;
            try (AutoLock ignored = lock.lock())
            {
                result = List.copyOf(parts);
                MultiPartFormData.this.parts = new Parts(result);
            }
        }

        Charset getDefaultCharset()
        {
            try (AutoLock ignored = lock.lock())
            {
                return parts.stream()
                    .filter(part -> "_charset_".equals(part.getName()))
                    .map(part -> part.getContentAsString(US_ASCII))
                    .map(Charset::forName)
                    .findFirst()
                    .orElse(null);
            }
        }

        int getPartsSize()
        {
            try (AutoLock ignored = lock.lock())
            {
                return parts.size();
            }
        }

        @Override
        public void onFailure(Throwable failure)
        {
            super.onFailure(failure);
            completeExceptionally(failure); // TODO not here
        }

        private void fail(Throwable cause)
        {
            List<MultiPart.Part> partsToFail;
            try (AutoLock ignored = lock.lock())
            {
                if (failure != null)
                    return;
                failure = cause;
                partsToFail = List.copyOf(parts);
                parts.clear();
                partChunks.forEach(Content.Chunk::release);
                partChunks.clear();
            }
            partsToFail.forEach(p -> p.fail(cause));
            close();
            delete();
        }

        private SeekableByteChannel fileChannel()
        {
            try (AutoLock ignored = lock.lock())
            {
                return fileChannel;
            }
        }

        private void delete()
        {
            try
            {
                Path path = null;
                try (AutoLock ignored = lock.lock())
                {
                    if (filePath != null)
                        path = filePath;
                    filePath = null;
                    fileChannel = null;
                }
                if (path != null)
                    Files.delete(path);
            }
            catch (Throwable x)
            {
                if (LOG.isTraceEnabled())
                    LOG.trace("IGNORED", x);
            }
        }

        private boolean isFailed()
        {
            try (AutoLock ignored = lock.lock())
            {
                return failure != null;
            }
        }

        private boolean ensureFileChannel()
        {
            try (AutoLock ignored = lock.lock())
            {
                if (fileChannel != null)
                    return false;
                createFileChannel();
                return true;
            }
        }

        private void createFileChannel()
        {
            try (AutoLock ignored = lock.lock())
            {
                Path directory = getFilesDirectory();
                Files.createDirectories(directory);
                String fileName = "MultiPart";
                filePath = Files.createTempFile(directory, fileName, "");
                fileChannel = Files.newByteChannel(filePath, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            }
            catch (Throwable x)
            {
                onFailure(x);
            }
        }
    }
}
