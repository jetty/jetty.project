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

package org.eclipse.jetty.server;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.CharsetStringBuilder;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.util.UrlEncoded.decodeHexByte;

/**
 * A {@link CompletableFuture} that is completed once a {@link org.eclipse.jetty.http.MimeTypes.Type#FORM_ENCODED}
 * content has been parsed asynchronously from the {@link Content.Reader}.
 */
public class FutureFormFields extends CompletableFuture<Fields> implements Runnable
{
    private static final Logger LOG = LoggerFactory.getLogger(FutureFormFields.class);
    private static final CompletableFuture<Fields> NONE = CompletableFuture.completedFuture(null);

    public static Charset getFormEncodedCharset(Request request)
    {
        HttpConfiguration config = request.getConnectionMetaData().getHttpConfiguration();
        if (!config.getFormEncodedMethods().contains(request.getMethod()))
            return null;

        String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (request.getContentLength() == 0 || StringUtil.isBlank(contentType))
            return null;

        // TODO mimeTypes from context
        MimeTypes.Type type = MimeTypes.CACHE.get(MimeTypes.getContentTypeWithoutCharset(contentType));
        if (MimeTypes.Type.FORM_ENCODED != type)
            return null;

        String cs = MimeTypes.getCharsetFromContentType(contentType);
        return StringUtil.isEmpty(cs) ? StandardCharsets.UTF_8 : Charset.forName(cs);
    }

    public static CompletableFuture<Fields> forRequest(Request request)
    {
        Object attr = request.getAttribute(FutureFormFields.class.getName());
        if (attr instanceof FutureFormFields futureFormFields)
            return futureFormFields;

        Charset charset = getFormEncodedCharset(request);
        if (charset == null)
            return NONE;

        // TODO get max sizes
        FutureFormFields futureFormFields = new FutureFormFields(request, charset, -1, -1);
        futureFormFields.run();
        return futureFormFields;
    }

    private final Content.Reader _reader;
    private final Fields _fields;
    private final CharsetStringBuilder _builder;
    private final int _maxFields;
    private final int _maxSize;
    private String _name;
    private int _size;
    private int _percent = 0;
    private byte _percentCode;

    public FutureFormFields(Content.Reader reader)
    {
        this(reader, StandardCharsets.UTF_8, -1, -1, null);
    }

    public FutureFormFields(Content.Reader reader, Charset charset, int maxFields, int maxSize)
    {
        this(reader, charset, maxFields, maxSize, null);
    }

    public FutureFormFields(Content.Reader reader, Charset charset, int maxFields, int maxSize, Fields fields)
    {
        _reader = reader;
        _maxFields = maxFields;
        _maxSize = maxSize;
        _builder = CharsetStringBuilder.forCharset(charset);
        _fields = fields == null ? new Fields() : fields;
    }

    @Override
    public void run()
    {
        try
        {
            while (true)
            {
                Content content = _reader.readContent();
                if (content == null)
                {
                    _reader.demandContent(this);
                    return;
                }

                if (content instanceof Content.Error error)
                {
                    completeExceptionally(error.getCause());
                    content.release();
                    return;
                }

                Fields.Field field = parse(content.getByteBuffer(), content.isLast());
                while (field != null)
                {
                    if (_maxFields >= 0 && _fields.getSize() >= _maxFields)
                    {
                        completeExceptionally(new IllegalStateException("Too many fields"));
                        return;
                    }
                    _fields.add(field);
                    field = parse(content.getByteBuffer(), content.isLast());
                }

                content.release();
                if (content.isLast())
                {
                    complete(_fields);
                    return;
                }
            }
        }
        catch (Throwable t)
        {
            completeExceptionally(t);
        }
    }

    protected Fields.Field parse(ByteBuffer buffer, boolean last) throws CharacterCodingException
    {
        String value = null;
        loop:
        while (BufferUtil.hasContent(buffer))
        {
            byte b = buffer.get();
            switch (_percent)
            {
                case 1 ->
                {
                    _percentCode = b;
                    _percent++;
                    continue;
                }
                case 2 ->
                {
                    _builder.append(decodeHexByte((char)_percentCode, (char)b));
                    _percent = 0;
                    continue;
                }
            }

            if (_name == null)
            {
                switch (b)
                {
                    case '=' ->
                    {
                        _name = _builder.takeString();
                        checkSize(_name);
                    }
                    case '+' -> _builder.append((byte)' ');
                    case '%' -> _percent++;
                    default -> _builder.append(b);
                }
            }
            else
            {
                switch (b)
                {
                    case '&' ->
                    {
                        value = _builder.takeString();
                        checkSize(value);
                        break loop;
                    }
                    case '+' -> _builder.append((byte)' ');
                    case '%' -> _percent++;
                    default -> _builder.append(b);
                }
            }
        }

        if (_name != null)
        {
            if (value == null && last)
            {
                if (_percent > 0)
                {
                    _builder.append((byte)'%');
                    _builder.append(_percentCode);
                }
                value = _builder.takeString();
                checkSize(value);
            }

            if (value != null)
            {
                Fields.Field field = new Fields.Field(_name, value);
                _name = null;
                return field;
            }
        }

        return null;
    }

    private void checkSize(String name)
    {
        if (_maxSize > 0)
        {
            _size += name.length();
            if (_size > _maxSize)
                throw new IllegalStateException("too large");
        }
    }
}
