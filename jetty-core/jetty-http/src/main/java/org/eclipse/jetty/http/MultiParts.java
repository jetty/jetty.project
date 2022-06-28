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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.US_ASCII;

public class MultiParts extends CompletableFuture<MultiParts.Parts>
{
    private static final Logger LOG = LoggerFactory.getLogger(MultiParts.class);

    public static String extractBoundary(String contentType)
    {
        Map<String, String> parameters = new HashMap<>();
        HttpField.valueParameters(contentType, parameters);
        String boundary = QuotedStringTokenizer.unquote(parameters.get("boundary"));
        return boundary != null ? boundary : "";
    }

    private final PartsListener listener = new PartsListener();
    private final MultiPart.Parser parser;
    private boolean useFilesForPartsWithoutFileName;
    private Path fileDirectory;
    private long maxFileSize = -1;
    private long maxMemoryFileSize;
    private long maxLength = -1;
    private long length;

    public MultiParts(String boundary)
    {
        parser = new MultiPart.Parser(Objects.requireNonNull(boundary), listener);
    }

    public String getBoundary()
    {
        return parser.getBoundary();
    }

    public MultiParts parse(Content.Source content)
    {
        new Runnable()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    Content.Chunk chunk = content.read();
                    if (chunk == null)
                    {
                        content.demand(this);
                        return;
                    }
                    if (chunk instanceof Content.Chunk.Error error)
                    {
                        completeExceptionally(error.getCause());
                        return;
                    }
                    parse(chunk);
                    chunk.release();
                    if (chunk.isLast())
                        return;
                }
            }
        }.run();
        return this;
    }

    public void parse(Content.Chunk chunk)
    {
        if (listener.isFailed())
            return;
        length += chunk.getByteBuffer().remaining();
        long max = getMaxLength();
        if (max > 0 && length > max)
            listener.onFailure(new IllegalStateException("max length exceeded: %d".formatted(max)));
        else
            parser.parse(chunk);
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
        return getParts().stream()
            .filter(part -> "_charset_".equals(part.getName()))
            .map(part -> part.getContentAsString(US_ASCII))
            .map(Charset::forName)
            .findFirst()
            .orElse(null);
    }

    public int getHeadersMaxLength()
    {
        return parser.getHeadersMaxLength();
    }

    public void setHeadersMaxLength(int headersMaxLength)
    {
        parser.setHeadersMaxLength(headersMaxLength);
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
    public Path getFileDirectory()
    {
        return fileDirectory;
    }

    /**
     * <p>Sets the directory where the files uploaded in the parts will be saved.</p>
     *
     * @param fileDirectory the directory where files are saved
     */
    public void setFileDirectory(Path fileDirectory)
    {
        this.fileDirectory = fileDirectory;
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
     * <p>Sets the maximum memory file size in bytes, after which files will be save
     * in the directory specified by {@link #setFileDirectory(Path)}.</p>
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

    @Override
    public boolean completeExceptionally(Throwable failure)
    {
        listener.fail(failure);
        return super.completeExceptionally(failure);
    }

    // Only used for testing.
    List<MultiPart.Part> getParts()
    {
        return listener.getParts();
    }

    public static class Parts implements Iterable<MultiPart.Part>
    {
        private final String boundary;
        private final List<MultiPart.Part> parts;

        private Parts(String boundary, List<MultiPart.Part> parts)
        {
            this.boundary = boundary;
            this.parts = parts;
        }

        public String getBoundary()
        {
            return boundary;
        }

        public MultiPart.Part get(int index)
        {
            return parts.get(index);
        }

        public MultiPart.Part getFirst(String name)
        {
            return parts.stream()
                .filter(part -> part.getName().equals(name))
                .findFirst()
                .orElse(null);
        }

        public List<MultiPart.Part> getAll(String name)
        {
            return parts.stream()
                .filter(part -> part.getName().equals(name))
                .toList();
        }

        public int size()
        {
            return parts.size();
        }

        @Override
        public Iterator<MultiPart.Part> iterator()
        {
            return parts.iterator();
        }

        public MultiPart.ContentSource toContentSource()
        {
            MultiPart.ContentSource result = new MultiPart.ContentSource(getBoundary());
            parts.forEach(result::addPart);
            result.close();
            return result;
        }
    }

    private class PartsListener extends MultiPart.AbstractPartsListener
    {
        private final AutoLock lock = new AutoLock();
        private Throwable failure;
        private final List<MultiPart.Part> parts = new ArrayList<>();
        private long fileSize;
        private long memoryFileSize;
        private Path filePath;
        private volatile SeekableByteChannel fileChannel;

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
                        // Must save to disk.
                        if (ensureFileChannel())
                        {
                            // Write existing memory chunks.
                            for (Content.Chunk c : getContent())
                            {
                                if (!write(c.getByteBuffer()))
                                    return;
                            }
                        }
                        write(buffer);
                        if (chunk.isLast())
                            close();
                        return;
                    }
                }
            }
            super.onPartContent(chunk);
        }

        private boolean write(ByteBuffer buffer)
        {
            try
            {
                int remaining = buffer.remaining();
                while (remaining > 0)
                {
                    int written = fileChannel.write(buffer);
                    if (written == 0)
                        throw new NonWritableChannelException();
                    remaining -= written;
                }
                return true;
            }
            catch (Throwable x)
            {
                onFailure(x);
                return false;
            }
        }

        private void close()
        {
            try
            {
                Closeable closeable = fileChannel;
                if (closeable != null)
                    closeable.close();
            }
            catch (Throwable x)
            {
                onFailure(x);
            }
        }

        @Override
        protected MultiPart.Part newPart(String name, String fileName, HttpFields headers)
        {
            if (fileChannel != null)
                return new MultiPart.PathPart(name, fileName, headers, filePath);
            else
                return super.newPart(name, fileName, headers);
        }

        @Override
        public void onPart(MultiPart.Part part)
        {
            // Reset part-related state.
            fileSize = 0;
            memoryFileSize = 0;
            filePath = null;
            fileChannel = null;
            // Store the new part.
            try (AutoLock ignored = lock.lock())
            {
                parts.add(part);
            }
        }

        @Override
        public void onComplete()
        {
            super.onComplete();
            complete(new Parts(getBoundary(), getParts()));
        }

        private List<MultiPart.Part> getParts()
        {
            try (AutoLock ignored = lock.lock())
            {
                return List.copyOf(parts);
            }
        }

        @Override
        public void onFailure(Throwable failure)
        {
            super.onFailure(failure);
            completeExceptionally(failure);
        }

        private void fail(Throwable cause)
        {
            try (AutoLock ignored = lock.lock())
            {
                if (failure == null)
                {
                    failure = cause;
                    parts.stream()
                        .filter(part -> part instanceof MultiPart.PathPart)
                        .map(MultiPart.PathPart.class::cast)
                        .forEach(MultiPart.PathPart::delete);
                    parts.clear();
                }
            }
            close();
            delete();
        }

        private void delete()
        {
            try
            {
                if (fileChannel != null)
                    Files.delete(filePath);
                filePath = null;
                fileChannel = null;
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
            if (fileChannel != null)
                return false;
            createFileChannel();
            return true;
        }

        private void createFileChannel()
        {
            try
            {
                Path directory = getFileDirectory();
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
