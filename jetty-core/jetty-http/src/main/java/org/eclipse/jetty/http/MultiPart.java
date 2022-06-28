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
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.io.content.ChunkContentSource;
import org.eclipse.jetty.io.content.PathContentSource;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.SearchPattern;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.SerializedInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

public class MultiPart
{
    /**
     * <p>A single part of a multipart content.</p>
     * <p>A part has an optional name, an optional fileName,
     * optional headers and an optional content.</p>
     */
    public abstract static class Part
    {
        private final String name;
        private final String fileName;
        private final HttpFields fields;

        public Part(String name, String fileName, HttpFields fields)
        {
            this.name = name;
            this.fileName = fileName;
            this.fields = fields;
        }

        /**
         * <p>Returns the name of this part, as specified by the
         * {@link HttpHeader#CONTENT_DISPOSITION}'s {@code name} parameter.</p>
         * <p>While the {@code name} parameter is mandatory per RFC 7578,
         * older clients may not send it.</p>
         *
         * @return the name of this part, or {@code null} if there is no name
         */
        public String getName()
        {
            return name;
        }

        /**
         * <p>Returns the file name of this part, as specified by the
         * {@link HttpHeader#CONTENT_DISPOSITION}'s {@code filename} parameter.</p>
         * <p>While the {@code filename} parameter is mandatory per RFC 7578 when
         * uploading files, older clients may not send it.</p>
         * <p>The file name may be absent if the part is not a file upload.</p>
         *
         * @return the file name of this part, or {@code null} if there is no file name
         */
        public String getFileName()
        {
            return fileName;
        }

        /**
         * <p>Returns the content of this part.</p>
         * <p>The content type and content encoding are specified in this part's
         * {@link #getHttpFields() headers}.</p>
         * <p>The content encoding may be specified by the part named {@code _charset_},
         * as specified in
         * <a href="https://datatracker.ietf.org/doc/html/rfc7578#section-4.6">RFC 7578, section 4.6</a>.</p>
         *
         * @return the content of this part
         */
        public abstract Content.Source getContent();

        /**
         * <p>Returns the content of this part as a string.</p>
         * <p>The charset used to decode the bytes is:
         * <ul>
         * <li>the {@code charset} parameter of the {@link HttpHeader#CONTENT_TYPE} header, if non-null;</li>
         * <li>otherwise, the given {@code defaultCharset} parameter, if non-null;</li>
         * <li>otherwise, UTF-8.</li>
         * </ul>
         *
         * @param defaultCharset the charset to use to decode the bytes,
         * if the {@code charset} parameter of the {@code Content-Type} header is missing
         * @return the content of this part as a string
         */
        public String getContentAsString(Charset defaultCharset)
        {
            try
            {
                String contentType = getHttpFields().get(HttpHeader.CONTENT_TYPE);
                String charsetName = MimeTypes.getCharsetFromContentType(contentType);
                Charset charset = defaultCharset != null ? defaultCharset : UTF_8;
                if (charsetName != null)
                    charset = Charset.forName(charsetName);
                return Content.Source.asString(getContent(), charset);
            }
            catch (IOException x)
            {
                throw new UncheckedIOException(x);
            }
        }

        /**
         * @return the headers associated with this part
         */
        public HttpFields getHttpFields()
        {
            return fields;
        }

        public void writeTo(Path path) throws IOException
        {
            try (OutputStream out = Files.newOutputStream(path))
            {
                IO.copy(Content.Source.asInputStream(getContent()), out);
            }
        }
    }

    /**
     * <p>A {@link Part} that holds its content in memory.</p>
     */
    public static class ByteBufferPart extends Part
    {
        private final List<ByteBuffer> content;
        private final long length;

        public ByteBufferPart(String name, String fileName, HttpFields fields, ByteBuffer... buffers)
        {
            this(name, fileName, fields, List.of(buffers));
        }

        public ByteBufferPart(String name, String fileName, HttpFields fields, List<ByteBuffer> content)
        {
            super(name, fileName, fields);
            this.content = content;
            this.length = content.stream().mapToLong(Buffer::remaining).sum();
        }

        @Override
        public Content.Source getContent()
        {
            return new ByteBufferContentSource(content);
        }

        @Override
        public String toString()
        {
            return "%s@%x[name=%s,fileName=%s,length=%d]".formatted(
                getClass().getSimpleName(),
                hashCode(),
                getName(),
                getFileName(),
                length
            );
        }
    }

    /**
     * <p>A {@link Part} that holds its content in retainable memory.</p>
     */
    public static class ChunkPart extends Part
    {
        private final List<Content.Chunk> content;
        private final long length;

        public ChunkPart(String name, String fileName, HttpFields fields, List<Content.Chunk> content)
        {
            super(name, fileName, fields);
            this.content = content;
            this.length = content.stream().mapToLong(c -> c.getByteBuffer().remaining()).sum();
        }

        @Override
        public Content.Source getContent()
        {
            return new ChunkContentSource(content);
        }

        @Override
        public String toString()
        {
            return "%s@%x[name=%s,fileName=%s,length=%d]".formatted(
                getClass().getSimpleName(),
                hashCode(),
                getName(),
                getFileName(),
                length
            );
        }
    }

    /**
     * <p>A {@link Part} that holds its content in the file-system.</p>
     */
    public static class PathPart extends Part
    {
        private final Path path;

        public PathPart(String name, String fileName, HttpFields fields, Path path)
        {
            super(name, fileName, fields);
            this.path = path;
        }

        public Path getPath()
        {
            return path;
        }

        @Override
        public Content.Source getContent()
        {
            try
            {
                return new PathContentSource(path);
            }
            catch (IOException x)
            {
                throw new UncheckedIOException(x);
            }
        }

        @Override
        public void writeTo(Path path) throws IOException
        {
            // TODO: make it more efficient via Files.move().
            super.writeTo(path);
        }

        public void delete()
        {
            try
            {
                Files.delete(path);
            }
            catch (IOException x)
            {
                throw new UncheckedIOException(x);
            }
        }

        @Override
        public String toString()
        {
            return "%s@%x[name=%s,fileName=%s,path=%s]".formatted(
                getClass().getSimpleName(),
                hashCode(),
                getName(),
                getFileName(),
                getPath()
            );
        }
    }

    public static class ContentSourcePart extends Part
    {
        private final Content.Source content;

        public ContentSourcePart(String name, String fileName, HttpFields fields, Content.Source content)
        {
            super(name, fileName, fields);
            this.content = content;
        }

        @Override
        public Content.Source getContent()
        {
            return content;
        }

        @Override
        public String toString()
        {
            return "%s@%x[name=%s,fileName=%s,length=%d]".formatted(
                getClass().getSimpleName(),
                hashCode(),
                getName(),
                getFileName(),
                content.getLength()
            );
        }
    }

    public static class ContentSource implements Content.Source, Closeable
    {
        private static final byte[] CONTENT_DISPOSITION_BYTES = "Content-Disposition: form-data".getBytes(US_ASCII);
        private static final byte[] CONTENT_DISPOSITION_NAME_BYTES = "; name=\"".getBytes(US_ASCII);
        private static final byte[] CONTENT_DISPOSITION_FILENAME_BYTES = "; filename=\"".getBytes(US_ASCII);

        private final AutoLock lock = new AutoLock();
        private final SerializedInvoker invoker = new SerializedInvoker();
        private final Queue<Part> parts = new ArrayDeque<>();
        private final String boundary;
        private final ByteBuffer firstBoundary;
        private final ByteBuffer middleBoundary;
        private final ByteBuffer onlyBoundary;
        private final ByteBuffer lastBoundary;
        private ByteBufferPool byteBufferPool;
        private boolean useDirectByteBuffers = true;
        private int headersMaxLength = -1;
        private State state = State.FIRST;
        private boolean closed;
        private Runnable demand;
        private Content.Chunk.Error errorChunk;
        private Part part;

        public ContentSource(String boundary)
        {
            this.boundary = Objects.requireNonNull(boundary);
            String firstBoundaryLine = "--" + boundary + "\r\n";
            this.firstBoundary = ByteBuffer.wrap(firstBoundaryLine.getBytes(US_ASCII));
            String middleBoundaryLine = "\r\n" + firstBoundaryLine;
            this.middleBoundary = ByteBuffer.wrap(middleBoundaryLine.getBytes(US_ASCII));
            String onlyBoundaryLine = "--" + boundary + "--\r\n";
            this.onlyBoundary = ByteBuffer.wrap(onlyBoundaryLine.getBytes(US_ASCII));
            String lastBoundaryLine = "\r\n" + onlyBoundaryLine;
            this.lastBoundary = ByteBuffer.wrap(lastBoundaryLine.getBytes(US_ASCII));
        }

        public String getBoundary()
        {
            return boundary;
        }

        public ByteBufferPool getByteBufferPool()
        {
            return byteBufferPool;
        }

        public void setByteBufferPool(ByteBufferPool byteBufferPool)
        {
            this.byteBufferPool = byteBufferPool;
        }

        public boolean isUseDirectByteBuffers()
        {
            return useDirectByteBuffers;
        }

        public void setUseDirectByteBuffers(boolean useDirectByteBuffers)
        {
            this.useDirectByteBuffers = useDirectByteBuffers;
        }

        public int getHeadersMaxLength()
        {
            return headersMaxLength;
        }

        public void setHeadersMaxLength(int headersMaxLength)
        {
            this.headersMaxLength = headersMaxLength;
        }

        public boolean addPart(Part part)
        {
            boolean wasEmpty;
            try (AutoLock ignored = lock.lock())
            {
                if (closed || errorChunk != null)
                    return false;
                wasEmpty = parts.isEmpty();
                parts.offer(part);
            }
            if (wasEmpty)
                invoker.run(this::invokeDemandCallback);
            return true;
        }

        @Override
        public void close()
        {
            boolean wasEmpty;
            try (AutoLock ignored = lock.lock())
            {
                closed = true;
                wasEmpty = parts.isEmpty();
            }
            if (wasEmpty)
                invoker.run(this::invokeDemandCallback);
        }

        @Override
        public long getLength()
        {
            // TODO can it be computed?
            return -1;
        }

        @Override
        public Content.Chunk read()
        {
            try (AutoLock ignored = lock.lock())
            {
                if (errorChunk != null)
                    return errorChunk;
            }

            return switch (state)
            {
                case FIRST ->
                {
                    try (AutoLock ignored = lock.lock())
                    {
                        if (parts.isEmpty())
                        {
                            if (closed)
                            {
                                state = State.COMPLETE;
                                yield Content.Chunk.from(onlyBoundary.slice(), true);
                            }
                            else
                            {
                                yield null;
                            }
                        }
                        else
                        {
                            part = parts.poll();
                            state = State.HEADERS;
                            yield Content.Chunk.from(firstBoundary.slice(), false);
                        }
                    }
                }
                case MIDDLE ->
                {
                    part = null;
                    try (AutoLock ignored = lock.lock())
                    {
                        if (parts.isEmpty())
                        {
                            if (closed)
                            {
                                state = State.COMPLETE;
                                yield Content.Chunk.from(lastBoundary.slice(), true);
                            }
                            else
                            {
                                yield null;
                            }
                        }
                        else
                        {
                            part = parts.poll();
                            state = State.HEADERS;
                            yield Content.Chunk.from(middleBoundary.slice(), false);
                        }
                    }
                }
                case HEADERS ->
                {
                    Utf8StringBuilder builder = new Utf8StringBuilder(4096);
                    HttpFields headers = part.getHttpFields();
                    // Content-Disposition is mandatory.
                    if (!headers.contains(HttpHeader.CONTENT_DISPOSITION))
                    {
                        builder.append(CONTENT_DISPOSITION_BYTES);
                        String name = part.getName();
                        if (name != null)
                        {
                            builder.append(CONTENT_DISPOSITION_NAME_BYTES);
                            builder.append(name);
                            builder.append("\"");
                            checkHeadersLength(builder);
                        }
                        String fileName = part.getFileName();
                        if (fileName != null)
                        {
                            builder.append(CONTENT_DISPOSITION_FILENAME_BYTES);
                            builder.append(fileName);
                            builder.append("\"");
                            checkHeadersLength(builder);
                        }
                        builder.append("\r\n");
                    }
                    // Encode all the headers.
                    headers.forEach(field ->
                    {
                        HttpHeader header = field.getHeader();
                        if (header != null)
                        {
                            builder.append(header.getBytesColonSpace());
                        }
                        else
                        {
                            builder.append(field.getName());
                            builder.append(": ");
                        }
                        checkHeadersLength(builder);
                        String value = field.getValue();
                        if (value != null)
                        {
                            builder.append(value);
                            checkHeadersLength(builder);
                        }
                        builder.append("\r\n");
                    });
                    builder.append("\r\n");

                    // TODO: use the ByteBufferPool and useDirectByteBuffers!
                    ByteBuffer byteBuffer = UTF_8.encode(builder.toString());
                    state = State.CONTENT;
                    yield Content.Chunk.from(byteBuffer, false);
                }
                case CONTENT ->
                {
                    Content.Chunk chunk = part.getContent().read();
                    if (chunk == null || chunk instanceof Content.Chunk.Error)
                        yield chunk;
                    if (chunk.isLast())
                    {
                        chunk = chunk.slice(false);
                        state = State.MIDDLE;
                    }
                    yield chunk;
                }
                case COMPLETE -> Content.Chunk.EOF;
            };
        }

        private void checkHeadersLength(Utf8StringBuilder builder)
        {
            int max = getHeadersMaxLength();
            if (max > 0 && builder.length() > max)
                throw new IllegalStateException("headers max length exceeded: %d".formatted(max));
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            boolean invoke;
            try (AutoLock ignored = lock.lock())
            {
                if (this.demand != null)
                    throw new IllegalStateException("demand pending");
                this.demand = Objects.requireNonNull(demandCallback);
                invoke = !parts.isEmpty() || closed || errorChunk != null;
            }
            if (invoke)
                invoker.run(this::invokeDemandCallback);
        }

        @Override
        public void fail(Throwable failure)
        {
            List<Part> drained;
            try (AutoLock ignored = lock.lock())
            {
                if (closed && parts.isEmpty())
                    return;
                if (errorChunk != null)
                    return;
                errorChunk = Content.Chunk.from(failure);
                drained = List.copyOf(parts);
                parts.clear();
            }
            drained.forEach(part -> part.getContent().fail(failure));
            invoker.run(this::invokeDemandCallback);
        }

        private void invokeDemandCallback()
        {
            Runnable callback;
            try (AutoLock ignored = lock.lock())
            {
                callback = this.demand;
                this.demand = null;
            }
            if (callback != null)
                runDemandCallback(callback);
        }

        private void runDemandCallback(Runnable callback)
        {
            try
            {
                callback.run();
            }
            catch (Throwable x)
            {
                fail(x);
            }
        }

        private enum State
        {
            FIRST, MIDDLE, HEADERS, CONTENT, COMPLETE
        }
    }

    /**
     * <p>A {@code multipart/form-data} parser that follows
     * <a href="https://datatracker.ietf.org/doc/html/rfc7578">RFC 7578</a>.</p>
     * <p>RFC 7578 mandates that end-of-lines are CRLF, but this parser is
     * more lenient and it is able to parse multipart content that only
     * uses LF as end-of-line.</p>
     * <p>The parser emits events specified by {@link Listener}, that can be
     * implemented to support specific logic (for example, the max content
     * length of a part, etc.</p>
     */
    public static class Parser
    {
        private static final Logger LOG = LoggerFactory.getLogger(Parser.class);
        private static final ByteBuffer CR = US_ASCII.encode("\r");

        private final Utf8StringBuilder text = new Utf8StringBuilder();
        private final String boundary;
        private final SearchPattern boundaryFinder;
        private final Listener listener;
        private int headerLength;
        private int headersMaxLength = -1;
        private State state;
        private int partialBoundaryMatch;
        private boolean crFlag;
        private boolean crContent;
        private int trailingWhiteSpaces;
        private String fieldName;
        private String fieldValue;

        public Parser(String boundary, Listener listener)
        {
            this.boundary = boundary;
            // While the spec mandates CRLF before the boundary, be more lenient and only require LF.
            this.boundaryFinder = SearchPattern.compile("\n--" + boundary);
            this.listener = listener;
            reset();
        }

        public String getBoundary()
        {
            return boundary;
        }

        public int getHeadersMaxLength()
        {
            return headersMaxLength;
        }

        public void setHeadersMaxLength(int headersMaxLength)
        {
            this.headersMaxLength = headersMaxLength;
        }

        public void reset()
        {
            text.reset();
            headerLength = 0;
            state = State.PREAMBLE;
            // Skip initial LF for the first boundary.
            partialBoundaryMatch = 1;
            crFlag = false;
            crContent = false;
            trailingWhiteSpaces = 0;
            fieldName = null;
            fieldValue = null;
        }

        public void parse(Content.Chunk chunk)
        {
            ByteBuffer buffer = chunk.getByteBuffer();
            boolean last = chunk.isLast();
            try
            {
                while (buffer.hasRemaining())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("parse {} {}", state, BufferUtil.toDetailString(buffer));

                    switch (state)
                    {
                        case PREAMBLE ->
                        {
                            if (parsePreamble(buffer))
                                state = State.BOUNDARY;
                        }
                        case BOUNDARY ->
                        {
                            HttpTokens.Token token = next(buffer);
                            HttpTokens.Type type = token.getType();
                            if (type == HttpTokens.Type.CR)
                            {
                                // Ignore CR and loop around.
                            }
                            else if (type == HttpTokens.Type.LF)
                            {
                                notifyPartBegin();
                                state = State.HEADER_START;
                                trailingWhiteSpaces = 0;
                                text.reset();
                                headerLength = 0;
                            }
                            else if (token.getByte() == '-')
                            {
                                state = State.BOUNDARY_CLOSE;
                            }
                            // SPEC: ignore linear whitespace after boundary.
                            else if (type != HttpTokens.Type.SPACE && type != HttpTokens.Type.HTAB)
                            {
                                throw new BadMessageException("bad last boundary");
                            }
                        }
                        case BOUNDARY_CLOSE ->
                        {
                            HttpTokens.Token token = next(buffer);
                            if (token.getByte() != '-')
                                throw new BadMessageException("bad last boundary");
                            state = State.EPILOGUE;
                        }
                        case HEADER_START ->
                        {
                            state = parseHeaderStart(buffer);
                        }
                        case HEADER_NAME ->
                        {
                            if (parseHeaderName(buffer))
                                state = State.HEADER_VALUE;
                        }
                        case HEADER_VALUE ->
                        {
                            if (parseHeaderValue(buffer))
                                state = State.HEADER_START;
                        }
                        case CONTENT_START ->
                        {
                            if (parseContent(chunk))
                                state = State.BOUNDARY;
                            else
                                state = State.CONTENT;
                        }
                        case CONTENT ->
                        {
                            if (parseContent(chunk))
                                state = State.BOUNDARY;
                        }
                        case EPILOGUE ->
                        {
                            // Just discard the epilogue.
                            buffer.position(buffer.limit());
                        }
                    }
                }

                if (last)
                {
                    if (state == State.EPILOGUE)
                        notifyComplete();
                    else
                        throw new EOFException("unexpected EOF");
                }
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("parse failure {} {}", state, BufferUtil.toDetailString(buffer), x);
                buffer.position(buffer.limit());
                notifyFailure(x);
            }
        }

        /**
         * <p>Returns the token corresponding to the next byte in the given {@code buffer}.</p>
         * <p>Token {@link HttpTokens.Type#CR} should be ignored when looking for EOL, as
         * {@link HttpTokens.Type#LF} marks the actual EOL.</p>
         *
         * @param buffer the {@code ByteBuffer} containing bytes to parse
         * @return a token corresponding to the next byte in the buffer
         */
        private HttpTokens.Token next(ByteBuffer buffer)
        {
            byte b = buffer.get();
            HttpTokens.Token t = HttpTokens.TOKENS[b & 0xFF];
            switch (t.getType())
            {
                case CNTL ->
                {
                    throw new BadMessageException("invalid byte " + Integer.toHexString(t.getChar()));
                }
                case LF ->
                {
                    crFlag = false;
                }
                case CR ->
                {
                    if (crFlag)
                        throw new BadMessageException("invalid EOL");
                    crFlag = true;
                }
                default ->
                {
                    if (crFlag)
                        throw new BadMessageException("invalid EOL");
                }
            }
            return t;
        }

        private boolean parsePreamble(ByteBuffer buffer)
        {
            if (partialBoundaryMatch > 0)
            {
                int boundaryMatch = boundaryFinder.startsWith(buffer, partialBoundaryMatch);
                if (boundaryMatch > 0)
                {
                    if (boundaryMatch == boundaryFinder.getLength())
                    {
                        // The boundary was fully matched.
                        buffer.position(buffer.position() + boundaryMatch - partialBoundaryMatch);
                        partialBoundaryMatch = 0;
                        return true;
                    }
                    else
                    {
                        // The boundary was partially matched.
                        buffer.position(buffer.limit());
                        partialBoundaryMatch = boundaryMatch;
                        return false;
                    }
                }
                else
                {
                    // There was a partial boundary match, but a mismatch was found.
                    partialBoundaryMatch = 0;
                }
            }

            // Search for a full boundary.
            int boundaryOffset = boundaryFinder.match(buffer);
            if (boundaryOffset >= 0)
            {
                // Found a full boundary.
                buffer.position(buffer.position() + boundaryOffset + boundaryFinder.getLength());
                return true;
            }

            // Search for a partial boundary at the end of the buffer.
            partialBoundaryMatch = boundaryFinder.endsWith(buffer);
            buffer.position(buffer.limit());
            return false;
        }

        private State parseHeaderStart(ByteBuffer buffer)
        {
            while (buffer.hasRemaining())
            {
                HttpTokens.Token token = next(buffer);
                switch (token.getType())
                {
                    case CR ->
                    {
                        // Ignore CR and loop around;
                    }
                    case LF ->
                    {
                        // End of fields.
                        notifyPartHeaders();
                        // A part may have an empty content.
                        partialBoundaryMatch = 1;
                        return State.CONTENT_START;
                    }
                    case COLON ->
                    {
                        throw new BadMessageException("invalid empty header name");
                    }
                    default ->
                    {
                        if (Character.isWhitespace(token.getByte()))
                        {
                            if (text.length() == 0)
                                throw new BadMessageException("invalid leading whitespace before header");
                        }
                        else
                        {
                            // Beginning of a field name.
                            incrementAndCheckHeadersLength();
                            text.append(token.getByte());
                            return State.HEADER_NAME;
                        }
                    }
                }
            }
            return State.HEADER_START;
        }

        private boolean parseHeaderName(ByteBuffer buffer)
        {
            while (buffer.hasRemaining())
            {
                HttpTokens.Token token = next(buffer);
                switch (token.getType())
                {
                    case COLON ->
                    {
                        // End of field name.
                        incrementAndCheckHeadersLength();
                        fieldName = text.toString();
                        trailingWhiteSpaces = 0;
                        text.reset();
                        return true;
                    }
                    case ALPHA, DIGIT, TCHAR ->
                    {
                        byte current = token.getByte();
                        if (trailingWhiteSpaces > 0)
                            throw new BadMessageException("invalid header name");
                        incrementAndCheckHeadersLength();
                        text.append(current);
                    }
                    default ->
                    {
                        byte current = token.getByte();
                        if (Character.isWhitespace(current))
                        {
                            incrementAndCheckHeadersLength();
                            ++trailingWhiteSpaces;
                        }
                        else
                        {
                            throw new BadMessageException("invalid header name");
                        }
                    }
                }
            }
            return false;
        }

        private boolean parseHeaderValue(ByteBuffer buffer)
        {
            while (buffer.hasRemaining())
            {
                HttpTokens.Token token = next(buffer);
                switch (token.getType())
                {
                    case CR ->
                    {
                        // Ignore CR and loop around;
                    }
                    case LF ->
                    {
                        // End of header value.
                        // Ignore trailing whitespace.
                        fieldValue = text.toString().stripTrailing();
                        text.reset();
                        notifyPartHeader(fieldName, fieldValue);
                        fieldName = null;
                        fieldValue = null;
                        return true;
                    }
                    default ->
                    {
                        byte current = token.getByte();
                        incrementAndCheckHeadersLength();
                        if (Character.isWhitespace(current))
                        {
                            // Ignore leading whitespace.
                            if (text.length() > 0)
                                text.append(" ");
                        }
                        else
                        {
                            text.append(current);
                        }
                    }
                }
            }
            return false;
        }

        private void incrementAndCheckHeadersLength()
        {
            ++headerLength;
            int max = getHeadersMaxLength();
            if (max > 0 && headerLength > max)
                throw new IllegalStateException("headers max length exceeded: %d".formatted(max));
        }

        private boolean parseContent(Content.Chunk chunk)
        {
            ByteBuffer buffer = chunk.getByteBuffer();

            if (partialBoundaryMatch > 0)
            {
                int boundaryMatch = boundaryFinder.startsWith(buffer, partialBoundaryMatch);
                if (boundaryMatch > 0)
                {
                    if (boundaryMatch == boundaryFinder.getLength())
                    {
                        // The boundary was fully matched.
                        buffer.position(buffer.position() + boundaryMatch - partialBoundaryMatch);
                        partialBoundaryMatch = 0;
                        notifyPartContent(Content.Chunk.from(BufferUtil.EMPTY_BUFFER, true));
                        notifyPartEnd();
                        return true;
                    }
                    else
                    {
                        // The boundary was partially matched.
                        buffer.position(buffer.limit());
                        partialBoundaryMatch = boundaryMatch;
                        return false;
                    }
                }
                else
                {
                    // There was a partial boundary match, but a mismatch was found.
                    // Handle the special case of parts with no content.
                    if (state == State.CONTENT_START)
                    {
                        // There is some content, reset the boundary match.
                        partialBoundaryMatch = 0;
                        return false;
                    }

                    // Must output as content the previous partial match.
                    if (crContent)
                    {
                        crContent = false;
                        notifyPartContent(Content.Chunk.from(CR.slice(), false));
                    }
                    ByteBuffer content = ByteBuffer.wrap(boundaryFinder.getPattern(), 0, partialBoundaryMatch);
                    partialBoundaryMatch = 0;
                    notifyPartContent(Content.Chunk.from(content, false));
                    return false;
                }
            }

            // Search for a full boundary.
            int boundaryOffset = boundaryFinder.match(buffer);
            if (boundaryOffset >= 0)
            {
                int limit = buffer.limit();
                int sliceLimit = buffer.position() + boundaryOffset;
                if (sliceLimit > 0 && buffer.get(sliceLimit - 1) == '\r')
                    --sliceLimit;
                buffer.limit(sliceLimit);
                Content.Chunk content = chunk.slice(true);
                buffer.limit(limit);
                buffer.position(buffer.position() + boundaryOffset + boundaryFinder.getLength());
                notifyPartContent(content);
                notifyPartEnd();
                return true;
            }

            // Search for a partial boundary at the end of the buffer.
            partialBoundaryMatch = boundaryFinder.endsWith(buffer);
            if (partialBoundaryMatch > 0)
            {
                int limit = buffer.limit();
                int sliceLimit = limit - partialBoundaryMatch;
                if (sliceLimit > 0 && buffer.get(sliceLimit - 1) == '\r')
                {
                    // Remember that there was a CR in case there will be a boundary mismatch.
                    crContent = true;
                    --sliceLimit;
                }
                buffer.limit(sliceLimit);
                Content.Chunk content = chunk.slice(false);
                buffer.limit(limit);
                buffer.position(limit);
                if (content.hasRemaining())
                    notifyPartContent(content);
                return false;
            }

            // There is normal content with no boundary.
            if (crContent)
            {
                crContent = false;
                notifyPartContent(Content.Chunk.from(CR.slice(), false));
            }
            int limit = buffer.limit();
            int sliceLimit = limit;
            if (buffer.get(sliceLimit - 1) == '\r')
            {
                crContent = true;
                --sliceLimit;
            }
            buffer.limit(sliceLimit);
            Content.Chunk content = chunk.slice(false);
            buffer.limit(limit);
            buffer.position(limit);
            if (content.hasRemaining())
                notifyPartContent(content);
            return false;
        }

        private void notifyPartBegin()
        {
            try
            {
                listener.onPartBegin();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying listener {}", listener, x);
            }
        }

        private void notifyPartHeader(String name, String value)
        {
            try
            {
                listener.onPartHeader(name, value);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying listener {}", listener, x);
            }
        }

        private void notifyPartHeaders()
        {
            try
            {
                listener.onPartHeaders();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying listener {}", listener, x);
            }
        }

        private void notifyPartContent(Content.Chunk chunk)
        {
            try
            {
                listener.onPartContent(chunk);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying listener {}", listener, x);
            }
        }

        private void notifyPartEnd()
        {
            try
            {
                listener.onPartEnd();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying listener {}", listener, x);
            }
        }

        private void notifyComplete()
        {
            try
            {
                listener.onComplete();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying listener {}", listener, x);
            }
        }

        private void notifyFailure(Throwable failure)
        {
            try
            {
                listener.onFailure(failure);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying listener {}", listener, x);
            }
        }

        /**
         * <p>A listener for events emitted by a {@link Parser}.</p>
         */
        public interface Listener
        {
            /**
             * <p>Callback method invoked when the begin of a new part is parsed.</p>
             */
            default void onPartBegin()
            {
            }

            /**
             * <p>Callback method invoked when a part header is parsed.</p>
             *
             * @param name the header name
             * @param value the header value
             */
            default void onPartHeader(String name, String value)
            {
            }

            /**
             * <p>Callback method invoked when all the part headers have been parsed.</p>
             */
            default void onPartHeaders()
            {
            }

            /**
             * <p>Callback method invoked when a part content chunk has been parsed.</p>
             *
             * @param chunk the part content chunk, must be released after use
             */
            default void onPartContent(Content.Chunk chunk)
            {
            }

            /**
             * <p>Callback method invoked when the end of a part is parsed.</p>
             */
            default void onPartEnd()
            {
            }

            /**
             * <p>Callback method invoked when the whole multipart content has been parsed.</p>
             */
            default void onComplete()
            {
            }

            /**
             * <p>Callback method invoked when the parser cannot parse the multipart content.</p>
             *
             * @param failure the failure cause
             */
            default void onFailure(Throwable failure)
            {
            }
        }

        private enum State
        {
            PREAMBLE, BOUNDARY, BOUNDARY_CLOSE, HEADER_START, HEADER_NAME, HEADER_VALUE, CONTENT_START, CONTENT, EPILOGUE
        }
    }

    /**
     * <p>A {@link Parser.Listener} that emits {@link Part} objects.</p>
     * <p>The part content is stored in memory.</p>
     * <p>Subclasses should just implement {@link #onPart(Part)} to
     * receive the parts of the multipart content.</p>
     */
    public abstract static class AbstractPartsListener implements Parser.Listener
    {
        private static final Logger LOG = LoggerFactory.getLogger(AbstractPartsListener.class);

        private final HttpFields.Mutable fields = HttpFields.build();
        private final List<Content.Chunk> content = new ArrayList<>();
        private String name;
        private String fileName;

        public String getName()
        {
            return name;
        }

        public String getFileName()
        {
            return fileName;
        }

        public List<Content.Chunk> getContent()
        {
            return content;
        }

        @Override
        public void onPartHeader(String headerName, String headerValue)
        {
            if (HttpHeader.CONTENT_DISPOSITION.is(headerName))
            {
                String namePrefix = "name=";
                String fileNamePrefix = "filename=";
                QuotedStringTokenizer tokenizer = new QuotedStringTokenizer(headerValue, ";", false, true);
                while (tokenizer.hasMoreTokens())
                {
                    String token = tokenizer.nextToken().trim();
                    String lowerToken = StringUtil.asciiToLowerCase(token);
                    if (lowerToken.startsWith(namePrefix))
                    {
                        int index = lowerToken.indexOf(namePrefix);
                        String value = token.substring(index + namePrefix.length()).trim();
                        name = QuotedStringTokenizer.unquoteOnly(value);
                    }
                    else if (lowerToken.startsWith(fileNamePrefix))
                    {
                        fileName = fileNameValue(token);
                    }
                }
            }
            fields.add(new HttpField(headerName, headerValue));
        }

        private String fileNameValue(String token)
        {
            int idx = token.indexOf('=');
            String value = token.substring(idx + 1).trim();

            if (value.matches(".??[a-zA-Z]:\\\\[^\\\\].*"))
            {
                // Matched incorrectly escaped IE filenames that have the whole
                // path, as in C:\foo, we just strip any leading & trailing quotes
                char first = value.charAt(0);
                if (first == '"' || first == '\'')
                    value = value.substring(1);
                char last = value.charAt(value.length() - 1);
                if (last == '"' || last == '\'')
                    value = value.substring(0, value.length() - 1);
                return value;
            }
            else
            {
                // unquote the string, but allow any backslashes that don't
                // form a valid escape sequence to remain as many browsers
                // even on *nix systems will not escape a filename containing
                // backslashes
                return QuotedStringTokenizer.unquoteOnly(value, true);
            }
        }

        @Override
        public void onPartContent(Content.Chunk chunk)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("part content last={} {}", chunk.isLast(), BufferUtil.toDetailString(chunk.getByteBuffer()));
            content.add(chunk);
        }

        @Override
        public void onPartEnd()
        {
            Part part = newPart(getName(), getFileName(), fields.takeAsImmutable());
            content.clear();
            name = null;
            fileName = null;
            notifyPart(part);
        }

        /**
         * <p>Callback method invoked when a {@link Part} has been parsed.</p>
         *
         * @param part the {@link Part} that has been parsed
         */
        public abstract void onPart(Part part);

        protected Part newPart(String name, String fileName, HttpFields headers)
        {
            return new ChunkPart(name, fileName, headers, List.copyOf(content));
        }

        private void notifyPart(Part part)
        {
            try
            {
                onPart(part);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying part {}", part, x);
            }
        }
    }
}
