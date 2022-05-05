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

package org.eclipse.jetty.client.util;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RuntimeIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link Request.Content} for form uploads with the {@code "multipart/form-data"}
 * content type.</p>
 * <p>Example usage:</p>
 * <pre>
 * MultiPartRequestContent multiPart = new MultiPartRequestContent();
 * multiPart.addFieldPart("field", new StringRequestContent("foo"), null);
 * multiPart.addFilePart("icon", "img.png", new PathRequestContent(Paths.get("/tmp/img.png")), null);
 * multiPart.close();
 * ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
 *         .method(HttpMethod.POST)
 *         .content(multiPart)
 *         .send();
 * </pre>
 * <p>The above example would be the equivalent of submitting this form:</p>
 * <pre>
 * &lt;form method="POST" enctype="multipart/form-data"  accept-charset="UTF-8"&gt;
 *     &lt;input type="text" name="field" value="foo" /&gt;
 *     &lt;input type="file" name="icon" /&gt;
 * &lt;/form&gt;
 * </pre>
 */
public class MultiPartRequestContent /*extends AbstractRequestContent*/ implements Request.Content, Closeable
{
    private static final Logger LOG = LoggerFactory.getLogger(MultiPartRequestContent.class);
    private static final byte[] COLON_SPACE_BYTES = new byte[]{':', ' '};
    private static final byte[] CR_LF_BYTES = new byte[]{'\r', '\n'};

    private static String makeBoundary()
    {
        Random random = new Random();
        StringBuilder builder = new StringBuilder("JettyHttpClientBoundary");
        int length = builder.length();
        while (builder.length() < length + 16)
        {
            long rnd = random.nextLong();
            builder.append(Long.toString(rnd < 0 ? -rnd : rnd, 36));
        }
        builder.setLength(length + 16);
        return builder.toString();
    }

    private final List<Part> parts = new ArrayList<>();
    private final String contentType;
    private final ByteBuffer firstBoundary;
    private final ByteBuffer middleBoundary;
    private final ByteBuffer onlyBoundary;
    private final ByteBuffer lastBoundary;
    private long length;
    private boolean closed;

    public MultiPartRequestContent()
    {
        this(makeBoundary());
    }

    public MultiPartRequestContent(String boundary)
    {
        this.contentType = "multipart/form-data; boundary=" + boundary;
        String firstBoundaryLine = "--" + boundary + "\r\n";
        this.firstBoundary = ByteBuffer.wrap(firstBoundaryLine.getBytes(StandardCharsets.US_ASCII));
        String middleBoundaryLine = "\r\n" + firstBoundaryLine;
        this.middleBoundary = ByteBuffer.wrap(middleBoundaryLine.getBytes(StandardCharsets.US_ASCII));
        String onlyBoundaryLine = "--" + boundary + "--\r\n";
        this.onlyBoundary = ByteBuffer.wrap(onlyBoundaryLine.getBytes(StandardCharsets.US_ASCII));
        String lastBoundaryLine = "\r\n" + onlyBoundaryLine;
        this.lastBoundary = ByteBuffer.wrap(lastBoundaryLine.getBytes(StandardCharsets.US_ASCII));
        this.length = -1;
    }

    @Override
    public String getContentType()
    {
        return contentType;
    }

    @Override
    public long getLength()
    {
        return length;
    }

    @Override
    public Content.Chunk read()
    {
        return null;
    }

    @Override
    public void demand(Runnable demandCallback)
    {
    }

    @Override
    public void fail(Throwable failure)
    {
    }

/*
    @Override
    protected Subscription newSubscription(Consumer consumer, boolean emitInitialContent)
    {
        if (!closed)
            throw new IllegalStateException("MultiPartRequestContent must be closed before sending the request");
        if (subscription != null)
            throw new IllegalStateException("Multiple subscriptions not supported on " + this);
        length = calculateLength();
        return subscription = new SubscriptionImpl(consumer, emitInitialContent);
    }

    @Override
    public void fail(Throwable failure)
    {
        parts.stream()
            .map(part -> part.content)
            .forEach(content -> content.fail(failure));
    }
*/

    /**
     * <p>Adds a field part with the given {@code name} as field name, and the given
     * {@code content} as part content.</p>
     * <p>The {@code Content-Type} of this part will be obtained from:</p>
     * <ul>
     * <li>the {@code Content-Type} header in the {@code fields} parameter; otherwise</li>
     * <li>the {@link Request.Content#getContentType()}</li>
     * </ul>
     *
     * @param name the part name
     * @param content the part content
     * @param fields the headers associated with this part
     */
    public void addFieldPart(String name, Request.Content content, HttpFields fields)
    {
        addPart(new Part(name, null, content, fields));
    }

    /**
     * <p>Adds a file part with the given {@code name} as field name, the given
     * {@code fileName} as file name, and the given {@code content} as part content.</p>
     * <p>The {@code Content-Type} of this part will be obtained from:</p>
     * <ul>
     * <li>the {@code Content-Type} header in the {@code fields} parameter; otherwise</li>
     * <li>the {@link Request.Content#getContentType()}</li>
     * </ul>
     *
     * @param name the part name
     * @param fileName the file name associated to this part
     * @param content the part content
     * @param fields the headers associated with this part
     */
    public void addFilePart(String name, String fileName, Request.Content content, HttpFields fields)
    {
        addPart(new Part(name, fileName, content, fields));
    }

    private void addPart(Part part)
    {
        parts.add(part);
        if (LOG.isDebugEnabled())
            LOG.debug("Added {}", part);
    }

    @Override
    public void close()
    {
        closed = true;
    }

    private long calculateLength()
    {
        // Compute the length, if possible.
        if (parts.isEmpty())
        {
            return onlyBoundary.remaining();
        }
        else
        {
            long result = 0;
            for (int i = 0; i < parts.size(); ++i)
            {
                result += (i == 0) ? firstBoundary.remaining() : middleBoundary.remaining();
                Part part = parts.get(i);
                long partLength = part.length;
                result += partLength;
                if (partLength < 0)
                {
                    result = -1;
                    break;
                }
            }
            if (result > 0)
                result += lastBoundary.remaining();
            return result;
        }
    }

    private static class Part
    {
        private final String name;
        private final String fileName;
        private final Request.Content content;
        private final HttpFields fields;
        private final ByteBuffer headers;
        private final long length;

        private Part(String name, String fileName, Request.Content content, HttpFields fields)
        {
            this.name = name;
            this.fileName = fileName;
            this.content = content;
            this.fields = fields;
            this.headers = headers();
            this.length = content.getLength() < 0 ? -1 : headers.remaining() + content.getLength();
        }

        private ByteBuffer headers()
        {
            try
            {
                // Compute the Content-Disposition.
                String contentDisposition = "Content-Disposition: form-data; name=\"" + name + "\"";
                if (fileName != null)
                    contentDisposition += "; filename=\"" + fileName + "\"";
                contentDisposition += "\r\n";

                // Compute the Content-Type.
                String contentType = fields == null ? null : fields.get(HttpHeader.CONTENT_TYPE);
                if (contentType == null)
                    contentType = content.getContentType();
                contentType = "Content-Type: " + contentType + "\r\n";

                if (fields == null || fields.size() == 0)
                {
                    String headers = contentDisposition;
                    headers += contentType;
                    headers += "\r\n";
                    return ByteBuffer.wrap(headers.getBytes(StandardCharsets.UTF_8));
                }

                ByteArrayOutputStream buffer = new ByteArrayOutputStream((fields.size() + 1) * contentDisposition.length());
                buffer.write(contentDisposition.getBytes(StandardCharsets.UTF_8));
                buffer.write(contentType.getBytes(StandardCharsets.UTF_8));
                for (HttpField field : fields)
                {
                    if (HttpHeader.CONTENT_TYPE.equals(field.getHeader()))
                        continue;
                    buffer.write(field.getName().getBytes(StandardCharsets.US_ASCII));
                    buffer.write(COLON_SPACE_BYTES);
                    String value = field.getValue();
                    if (value != null)
                        buffer.write(value.getBytes(StandardCharsets.UTF_8));
                    buffer.write(CR_LF_BYTES);
                }
                buffer.write(CR_LF_BYTES);
                return ByteBuffer.wrap(buffer.toByteArray());
            }
            catch (IOException x)
            {
                throw new RuntimeIOException(x);
            }
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[name=%s,fileName=%s,length=%d,headers=%s]",
                getClass().getSimpleName(),
                hashCode(),
                name,
                fileName,
                content.getLength(),
                fields);
        }
    }

/*
    private class SubscriptionImpl extends AbstractSubscription implements Consumer
    {
        private State state = State.FIRST_BOUNDARY;
        private int index;
        private Subscription subscription;

        private SubscriptionImpl(Consumer consumer, boolean emitInitialContent)
        {
            super(consumer, emitInitialContent);
        }

        @Override
        protected boolean produceContent(Producer producer) throws IOException
        {
            ByteBuffer buffer;
            boolean last = false;
            switch (state)
            {
                case FIRST_BOUNDARY:
                {
                    if (parts.isEmpty())
                    {
                        state = State.COMPLETE;
                        buffer = onlyBoundary.slice();
                        last = true;
                        break;
                    }
                    else
                    {
                        state = State.HEADERS;
                        buffer = firstBoundary.slice();
                        break;
                    }
                }
                case HEADERS:
                {
                    Part part = parts.get(index);
                    Request.Content content = part.content;
                    subscription = content.subscribe(this, true);
                    state = State.CONTENT;
                    buffer = part.headers.slice();
                    break;
                }
                case CONTENT:
                {
                    buffer = null;
                    subscription.demand();
                    break;
                }
                case MIDDLE_BOUNDARY:
                {
                    state = State.HEADERS;
                    buffer = middleBoundary.slice();
                    break;
                }
                case LAST_BOUNDARY:
                {
                    state = State.COMPLETE;
                    buffer = lastBoundary.slice();
                    last = true;
                    break;
                }
                case COMPLETE:
                {
                    throw new EOFException("Demand after last content");
                }
                default:
                {
                    throw new IllegalStateException("Invalid state " + state);
                }
            }
            return producer.produce(buffer, last, Callback.NOOP);
        }

        @Override
        public void onContent(ByteBuffer buffer, boolean last, Callback callback)
        {
            if (last)
            {
                ++index;
                if (index < parts.size())
                    state = State.MIDDLE_BOUNDARY;
                else
                    state = State.LAST_BOUNDARY;
            }
            notifyContent(buffer, false, callback);
        }

        @Override
        public void onFailure(Throwable failure)
        {
            if (subscription != null)
                subscription.fail(failure);
        }
    }
*/

    private enum State
    {
        FIRST_BOUNDARY, HEADERS, CONTENT, MIDDLE_BOUNDARY, LAST_BOUNDARY, COMPLETE
    }
}
