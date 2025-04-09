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
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ContentSourceCompletableFuture;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable.InvocationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * <p>A container class for {@code multipart/form-data} parsing and generation.</p>
 * <p>Use {@link Parser} to parse {@code multipart/form-data}, and use
 * {@link ContentSource} to generate {@code multipart/form-data} from parts.</p>
 * <p>Once the parsing of the {@code multipart/form-data} content completes successfully,
 * a {@link Parts} object is provided.</p>
 * <p>{@link Parser} may be configured to save multipart files in a configurable
 * directory, and configure the max size of such files, etc. via {@link MultiPartConfig}.</p>
 * <p>Typical usage:</p>
 * <pre>{@code
 * // Some headers that include Content-Type.
 * HttpFields headers = ...;
 * String boundary = MultiPart.extractBoundary(headers.get(HttpHeader.CONTENT_TYPE));
 *
 * // Some multipart/form-data content.
 * Content.Source content = ...;
 *
 * // Create and configure MultiPartFormData.Parser.
 * MultiPartFormData.Parser parser = new MultiPartFormData.Parser(boundary);
 * // Where to store the files.
 * parser.setFilesDirectory(Path.of("/tmp"));
 * // Max 1 MiB files.
 * parser.setMaxFileSize(1024 * 1024);
 *
 * // Parse the content.
 * parser.parse(content, new Promise.Invocable<>()
 * {
 *     @Override
 *     public void succeeded(MultiPartFormData.Parts parts)
 *     {
 *         // Use the parts.
 *     }
 *
 *     @Override
 *     public void failed(Throwable x)
 *     {
 *         // Handle failure.
 *     }
 * }
 * }</pre>
 * <p>For usage within a server environment, use:</p>
 * <pre>{@code
 * Request request = ...;
 * String contentType = request.getHeaders().get("Content-Type");
 * MultiPartConfig config = new MultiPartConfig.Builder()
 *     .location(Path.of("/tmp"))
 *     .maxPartSize(1024 * 1024)
 *     .build();
 * MultiPartFormData.onParts(request, request, contentType, config, new Promise.Invocable<>()
 * {
 *     @Override
 *     public void succeeded(MultiPartFormData.Parts parts)
 *     {
 *         // Use the parts.
 *     }
 *
 *     @Override
 *     public void failed(Throwable x)
 *     {
 *         // Handle failure.
 *     }
 * });
 * }</pre>
 *
 * @see Parts
 */
public class MultiPartFormData
{
    private static final Logger LOG = LoggerFactory.getLogger(MultiPartFormData.class);

    private MultiPartFormData()
    {
    }

    /**
     * Get {@code multipart/form-data} {@link Parts} from an {@link Attributes}, typically
     * cached there by calls to {@link #getParts(Content.Source, Attributes, String, MultiPartConfig)}
     * or {@link #onParts(Content.Source, Attributes, String, MultiPartConfig, Promise.Invocable)}
     *
     * @param attributes the attributes where the futureParts are cahced
     * @return the parts or null
     */
    public static Parts getParts(Attributes attributes)
    {
        Object attribute = attributes.getAttribute(MultiPartFormData.class.getName());
        if (attribute instanceof Parts parts)
            return parts;
        if (attribute instanceof CompletableFuture<?> futureParts && futureParts.isDone())
            return (Parts)futureParts.join();
        return null;
    }

    /**
     * Get {@code multipart/form-data} {@link Parts} from a {@link Content.Source}, caching the results in an
     * {@link Attributes}.  If not already available, the {@code Parts} are read and parsed, blocking if necessary.
     * <p>
     * Calls to {@code onParts} and {@code getParts} methods are idempotent, and
     * can be called multiple times, with subsequent calls returning the results of the first call.
     * @param content the source of the multipart content.
     * @param attributes the attributes where the Parts are cached.
     * @param contentType the value of the {@link HttpHeader#CONTENT_TYPE} header.
     * @param config the multipart configuration.
     * @return the parts
     */
    public static MultiPartFormData.Parts getParts(Content.Source content, Attributes attributes, String contentType, MultiPartConfig config)
    {
        return from(content, InvocationType.NON_BLOCKING, attributes, contentType, config).join();
    }

    /**
     * Asynchronously get {@code multipart/form-data} {@link Parts} from a {@link Content.Source}, caching the results in an
     * {@link Attributes}.  If not already available, the {@code Parts} are read and parsed.
     * <p>
     * Calls to {@code onParts} and {@code getParts} methods are idempotent, and
     * can be called multiple times, with subsequent calls returning the results of the first call.
     * @param content the source of the multipart content.
     * @param attributes the attributes where the futureParts are tracked.
     * @param contentType the value of the {@link HttpHeader#CONTENT_TYPE} header.
     * @param config the multipart configuration.
     * @param promise The action to take when the {@link Parts} are available.
     */
    public static void onParts(Content.Source content, Attributes attributes, String contentType, MultiPartConfig config, Promise.Invocable<Parts> promise)
    {
        from(content, promise.getInvocationType(), attributes, contentType, config).whenComplete(promise);
    }

    /**
     * Returns {@code multipart/form-data} parts using the given {@link Content.Source} and {@link MultiPartConfig}.
     *
     * @param content the source of the multipart content.
     * @param attributes the attributes where the futureParts are tracked.
     * @param contentType the value of the {@link HttpHeader#CONTENT_TYPE} header.
     * @param config the multipart configuration.
     * @return the future parts
     * @deprecated use {@link #getParts(Content.Source, Attributes, String, MultiPartConfig)}
     *             and/or {@link #onParts(Content.Source, Attributes, String, MultiPartConfig, Promise.Invocable)}
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    public static CompletableFuture<MultiPartFormData.Parts> from(Content.Source content, Attributes attributes, String contentType, MultiPartConfig config)
    {
        return from(content, InvocationType.NON_BLOCKING, attributes, contentType, config);
    }

    /**
     * Returns {@code multipart/form-data} parts using the given {@link Content.Source} and {@link MultiPartConfig}.
     *
     * @param content the source of the multipart content.
     * @param attributes the attributes where the futureParts are tracked.
     * @param contentType the value of the {@link HttpHeader#CONTENT_TYPE} header.
     * @param config the multipart configuration.
     * @return the future parts
     * @deprecated use {@link #getParts(Content.Source, Attributes, String, MultiPartConfig)}
     *             and/or {@link #onParts(Content.Source, Attributes, String, MultiPartConfig, Promise.Invocable)}
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    private static CompletableFuture<MultiPartFormData.Parts> from(Content.Source content, InvocationType invocationType, Attributes attributes, String contentType, MultiPartConfig config)
    {
        // Look for an existing future (we use the future here rather than the parts as it can remember any failure).
        CompletableFuture<MultiPartFormData.Parts> futureParts = MultiPartFormData.get(attributes);
        if (futureParts == null)
        {
            // No existing parts, so we need to try to read them ourselves

            // Are we the right content type to produce our own parts?
            if (contentType == null || !MimeTypes.Type.MULTIPART_FORM_DATA.is(HttpField.getValueParameters(contentType, null)))
                return CompletableFuture.failedFuture(new IllegalStateException("Not multipart Content-Type"));

            // Do we have a boundary?
            String boundary = MultiPart.extractBoundary(contentType);
            if (boundary == null)
                return CompletableFuture.failedFuture(new IllegalStateException("No multipart boundary parameter in Content-Type"));

            Parser parser = new Parser(boundary);
            parser.configure(config);
            futureParts = parser.parse(content, invocationType);
            attributes.setAttribute(MultiPartFormData.class.getName(), futureParts);
            return futureParts;
        }
        return futureParts;
    }

    /**
     * Returns {@code multipart/form-data} parts using {@link MultiPartCompliance#RFC7578}.
     * @deprecated use {@link #getParts(Content.Source, Attributes, String, MultiPartConfig)}
     *             and/or {@link #onParts(Content.Source, Attributes, String, MultiPartConfig, Promise.Invocable)}
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    public static CompletableFuture<Parts> from(Attributes attributes, String boundary, Function<Parser, CompletableFuture<Parts>> parse)
    {
        return from(attributes, MultiPartCompliance.RFC7578, ComplianceViolation.Listener.NOOP, boundary, parse);
    }

    /**
     * Returns {@code multipart/form-data} parts using the given {@link MultiPartCompliance} and listener.
     *
     * @param attributes the attributes where the futureParts are tracked
     * @param compliance the compliance mode
     * @param listener the compliance violation listener
     * @param boundary the boundary for the {@code multipart/form-data} parts
     * @param parse the parser completable future
     * @return the future parts
     * @deprecated use {@link #getParts(Content.Source, Attributes, String, MultiPartConfig)}
     *             and/or {@link #onParts(Content.Source, Attributes, String, MultiPartConfig, Promise.Invocable)}
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    public static CompletableFuture<Parts> from(Attributes attributes, MultiPartCompliance compliance, ComplianceViolation.Listener listener, String boundary, Function<Parser, CompletableFuture<Parts>> parse)
    {
        CompletableFuture<Parts> futureParts = get(attributes);
        if (futureParts == null)
        {
            futureParts = parse.apply(new Parser(boundary, compliance, listener));
            attributes.setAttribute(MultiPartFormData.class.getName(), futureParts);
        }
        return futureParts;
    }

    /**
     * Returns {@code multipart/form-data} parts if they have already been created.
     *
     * @param attributes the attributes where the futureParts are tracked
     * @return the future parts
     * @deprecated use {@link #getParts(Attributes)} instead
     */
    @SuppressWarnings("unchecked")
    @Deprecated(forRemoval = true, since = "12.0.15")
    public static CompletableFuture<Parts> get(Attributes attributes)
    {
        Object value = attributes.getAttribute(MultiPartFormData.class.getName());
        if (value instanceof CompletableFuture<?> cfp)
            return (CompletableFuture<Parts>)cfp;
        if (value instanceof Parts parts)
            return CompletableFuture.completedFuture(parts);
        return null;
    }

    /**
     * <p>An ordered list of {@link MultiPart.Part}s that can
     * be accessed by index or by name, or iterated over.</p>
     */
    public static class Parts implements Iterable<MultiPart.Part>, Closeable
    {
        private final List<MultiPart.Part> parts;

        private Parts(List<MultiPart.Part> parts)
        {
            this.parts = parts;
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

    public static class Parser
    {
        private final PartsListener listener = new PartsListener();
        private final MultiPart.Parser parser;
        private MultiPartCompliance compliance;
        private ComplianceViolation.Listener complianceListener;
        private boolean useFilesForPartsWithoutFileName = true;
        private Path filesDirectory;
        private long maxFileSize = -1;
        private long maxMemoryFileSize;
        private long maxLength = -1;
        private long length;

        public Parser(String boundary)
        {
            this(boundary, MultiPartCompliance.RFC7578, ComplianceViolation.Listener.NOOP);
        }

        /**
         * @deprecated use {@link Parser#Parser(String)} with {@link #configure(MultiPartConfig)}.
         */
        @Deprecated
        public Parser(String boundary, MultiPartCompliance multiPartCompliance, ComplianceViolation.Listener complianceViolationListener)
        {
            compliance = Objects.requireNonNull(multiPartCompliance);
            complianceListener = Objects.requireNonNull(complianceViolationListener);
            parser = new MultiPart.Parser(Objects.requireNonNull(boundary), compliance, listener);
        }

        /**
         * @deprecated use {@link #parse(Content.Source, Promise.Invocable)} or
         * {@link MultiPartFormData#onParts(Content.Source, Attributes, String, MultiPartConfig, Promise.Invocable)} instead.
         */
        @Deprecated(forRemoval = true, since = "12.0.16")
        public void parse(Content.Source content, Promise<Parts> immediate, Promise.Invocable<Parts> future)
        {
            CompletableFuture<Parts> cf = parse(content, future.getInvocationType());
            if (cf.isDone())
            {
                Parts parts = null;
                Throwable failure = null;
                try
                {
                    parts = cf.get();
                }
                catch (ExecutionException e)
                {
                    failure = e.getCause();
                }
                catch (Throwable t)
                {
                    failure = t;
                }
                if (failure == null)
                    immediate.succeeded(parts);
                else
                    immediate.failed(failure);
            }
            else
            {
                cf.whenComplete(future);
            }
        }

        /**
         * @deprecated use {@link #parse(Content.Source, Promise.Invocable)} or
         * {@link MultiPartFormData#onParts(Content.Source, Attributes, String, MultiPartConfig, Promise.Invocable)} instead.
         */
        @Deprecated(forRemoval = true, since = "12.0.15")
        public CompletableFuture<Parts> parse(Content.Source content)
        {
            return parse(content, InvocationType.NON_BLOCKING);
        }

        /**
         * @deprecated use {@link #parse(Content.Source, Promise.Invocable)} or
         * {@link MultiPartFormData#onParts(Content.Source, Attributes, String, MultiPartConfig, Promise.Invocable)} instead.
         */
        @Deprecated(forRemoval = true, since = "12.0.15")
        public CompletableFuture<Parts> parse(Content.Source content, InvocationType invocationType)
        {
            CompletableFuture<Parts> completable = new CompletableFuture<>();
            Promise.Invocable<Parts> futureParts = new Promise.Invocable<>()
            {
                @Override
                public void succeeded(Parts result)
                {
                    completable.complete(result);
                }

                @Override
                public void failed(Throwable x)
                {
                    completable.completeExceptionally(x);
                }

                @Override
                public InvocationType getInvocationType()
                {
                    return invocationType;
                }
            };
            parse(content, futureParts);
            return completable;
        }

        public void parse(Content.Source content, Promise.Invocable<Parts> promise)
        {
            ContentSourceCompletableFuture<Parts> futureParts = new ContentSourceCompletableFuture<>(content, promise.getInvocationType())
            {
                @Override
                protected Parts parse(Content.Chunk chunk) throws Throwable
                {
                    listener.rethrowIfFailed();
                    length += chunk.getByteBuffer().remaining();
                    long max = getMaxLength();
                    if (max >= 0 && length > max)
                        throw new IllegalStateException("max length exceeded: %d".formatted(max));
                    parser.parse(chunk);
                    listener.rethrowIfFailed();
                    return listener.getParts();
                }

                @Override
                public boolean completeExceptionally(Throwable failure)
                {
                    boolean failed = super.completeExceptionally(failure);
                    if (failed)
                        listener.fail(failure);
                    return failed;
                }
            };
            futureParts.parse();
            futureParts.whenComplete(promise);
        }

        /**
         * @return the boundary string
         */
        public String getBoundary()
        {
            return parser.getBoundary();
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

        private Path findFilesDirectory()
        {
            Path dir = getFilesDirectory();
            if (dir != null)
                return dir;
            String jettyBase = System.getProperty("jetty.base");
            if (jettyBase != null)
            {
                dir = Path.of(jettyBase).resolve("work");
                if (Files.exists(dir))
                    return dir;
            }
            throw new IllegalArgumentException("No files directory configured");
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

        /**
         * Configure the Parser given a {@link MultiPartConfig} instance.
         * @param config the configuration.
         */
        public void configure(MultiPartConfig config)
        {
            parser.setMaxParts(config.getMaxParts());
            maxMemoryFileSize = config.getMaxMemoryPartSize();
            maxFileSize = config.getMaxPartSize();
            maxLength = config.getMaxSize();
            parser.setPartHeadersMaxLength(config.getMaxHeadersSize());
            useFilesForPartsWithoutFileName = config.isUseFilesForPartsWithoutFileName();
            filesDirectory = config.getLocation();
            complianceListener = config.getViolationListener();
            compliance = config.getMultiPartCompliance();
        }

        // Only used for testing.
        int getPartsSize()
        {
            return listener.getPartsSize();
        }

        private class PartsListener extends MultiPart.AbstractPartsListener
        {
            private final AutoLock lock = new AutoLock();
            private final List<MultiPart.Part> partList = new ArrayList<>();
            private final List<Content.Chunk> partChunks = new ArrayList<>();
            private long size;
            private Path filePath;
            private SeekableByteChannel fileChannel;
            private Throwable failure;
            private Parts parts;

            @Override
            public void onPartContent(Content.Chunk chunk)
            {
                ByteBuffer buffer = chunk.getByteBuffer();
                long maxPartSize = getMaxFileSize();
                size += buffer.remaining();
                if (maxPartSize >= 0 && size > maxPartSize)
                {
                    onFailure(new IllegalStateException("max file size exceeded: %d".formatted(maxPartSize)));
                    return;
                }

                String fileName = getFileName();
                if (fileName != null || isUseFilesForPartsWithoutFileName())
                {
                    long maxMemoryPartSize = getMaxMemoryFileSize();
                    if (maxMemoryPartSize >= 0)
                    {
                        if (size > maxMemoryPartSize)
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
                                    try (AutoLock ignored = lock.lock())
                                    {
                                        this.partChunks.forEach(Content.Chunk::release);
                                        this.partChunks.clear();
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
                            return;
                        }
                    }
                }
                else
                {
                    long maxMemoryPartSize = getMaxMemoryFileSize();
                    if (maxMemoryPartSize >= 0)
                    {
                        if (size > maxMemoryPartSize)
                        {
                            onFailure(new IllegalStateException("max memory file size exceeded: %d".formatted(maxMemoryPartSize)));
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
                size = 0;
                try (AutoLock ignored = lock.lock())
                {
                    // Content-Transfer-Encoding is not a multi-valued field.
                    String value = headers.get(HttpHeader.CONTENT_TRANSFER_ENCODING);
                    if (value != null)
                    {
                        switch (StringUtil.asciiToLowerCase(value))
                        {
                            case "base64" ->
                            {
                                complianceListener.onComplianceViolation(
                                    new ComplianceViolation.Event(compliance,
                                        MultiPartCompliance.Violation.BASE64_TRANSFER_ENCODING,
                                        value));
                            }
                            case "quoted-printable" ->
                            {
                                complianceListener.onComplianceViolation(
                                    new ComplianceViolation.Event(compliance,
                                        MultiPartCompliance.Violation.QUOTED_PRINTABLE_TRANSFER_ENCODING,
                                        value));
                            }
                            case "8bit", "binary" ->
                            {
                                // ignore
                            }
                            default ->
                            {
                                complianceListener.onComplianceViolation(
                                    new ComplianceViolation.Event(compliance,
                                        MultiPartCompliance.Violation.CONTENT_TRANSFER_ENCODING,
                                        value));
                            }
                        }
                    }

                    MultiPart.Part part;
                    if (fileChannel != null)
                        part = new MultiPart.PathPart(null, name, fileName, headers, filePath); // TODO use a pool
                    else
                        part = new MultiPart.ChunksPart(name, fileName, headers, List.copyOf(partChunks));
                    // Reset part-related state.
                    filePath = null;
                    fileChannel = null;
                    partChunks.forEach(Content.Chunk::release);
                    partChunks.clear();
                    // Store the new part.
                    partList.add(part);
                }
            }

            @Override
            public void onComplete()
            {
                super.onComplete();
                List<MultiPart.Part> result;
                try (AutoLock ignored = lock.lock())
                {
                    result = List.copyOf(partList);
                    parts = new Parts(result);
                }
            }

            Charset getDefaultCharset()
            {
                try (AutoLock ignored = lock.lock())
                {
                    return partList.stream()
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
                    return partList.size();
                }
            }

            @Override
            public void onFailure(Throwable failure)
            {
                fail(failure);
            }

            @Override
            public void onViolation(MultiPartCompliance.Violation violation)
            {
                try
                {
                    ComplianceViolation.Event event = new ComplianceViolation.Event(compliance, violation, "multipart spec violation");
                    complianceListener.onComplianceViolation(event);
                }
                catch (Throwable x)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("failure while notifying listener {}", complianceListener, x);
                }
            }

            private void fail(Throwable cause)
            {
                List<MultiPart.Part> partsToFail;
                try (AutoLock ignored = lock.lock())
                {
                    if (failure != null)
                        return;
                    failure = cause;
                    partsToFail = List.copyOf(partList);
                    partList.clear();
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

            private void rethrowIfFailed() throws Throwable
            {
                try (AutoLock ignored = lock.lock())
                {
                    if (failure != null)
                        throw failure;
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
                    Path directory = findFilesDirectory();
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

            private Parts getParts()
            {
                try (AutoLock ignored = lock.lock())
                {
                    return parts;
                }
            }
        }
    }
}
