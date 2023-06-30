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

package org.eclipse.jetty.server;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ContentSourceCompletableFuture;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.CharsetStringBuilder;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.StringUtil;

import static org.eclipse.jetty.util.UrlEncoded.decodeHexByte;

/**
 * A {@link CompletableFuture} that is completed once a {@code application/x-www-form-urlencoded}
 * content has been parsed asynchronously from the {@link Content.Source}.
 */
public class FormFields extends ContentSourceCompletableFuture<Fields>
{
    public static final String MAX_FIELDS_ATTRIBUTE = "org.eclipse.jetty.server.Request.maxFormKeys";
    public static final String MAX_LENGTH_ATTRIBUTE = "org.eclipse.jetty.server.Request.maxFormContentSize";
    private static final CompletableFuture<Fields> EMPTY = CompletableFuture.completedFuture(Fields.EMPTY);

    public static Charset getFormEncodedCharset(Request request)
    {
        HttpConfiguration config = request.getConnectionMetaData().getHttpConfiguration();
        if (!config.getFormEncodedMethods().contains(request.getMethod()))
            return null;

        String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (request.getLength() == 0 || StringUtil.isBlank(contentType))
            return null;

        MimeTypes.Type type = MimeTypes.CACHE.get(MimeTypes.getContentTypeWithoutCharset(contentType));
        if (MimeTypes.Type.FORM_ENCODED != type)
            return null;

        String cs = MimeTypes.getCharsetFromContentType(contentType);
        return StringUtil.isEmpty(cs) ? StandardCharsets.UTF_8 : Charset.forName(cs);
    }

    public static CompletableFuture<Fields> from(Request request)
    {
        int maxFields = getRequestAttribute(request, FormFields.MAX_FIELDS_ATTRIBUTE);
        int maxLength = getRequestAttribute(request, FormFields.MAX_LENGTH_ATTRIBUTE);
        return from(request, maxFields, maxLength);
    }

    public static CompletableFuture<Fields> from(Request request, Charset charset)
    {
        int maxFields = getRequestAttribute(request, FormFields.MAX_FIELDS_ATTRIBUTE);
        int maxLength = getRequestAttribute(request, FormFields.MAX_LENGTH_ATTRIBUTE);
        return from(request, charset, maxFields, maxLength);
    }

    public static void set(Request request, CompletableFuture<Fields> fields)
    {
        request.setAttribute(FormFields.class.getName(), fields);
    }

    public static CompletableFuture<Fields> get(Request request)
    {
        Object attr = request.getAttribute(FormFields.class.getName());
        if (attr instanceof FormFields futureFormFields)
            return futureFormFields;
        return EMPTY;
    }

    public static CompletableFuture<Fields> from(Request request, int maxFields, int maxLength)
    {
        return from(request, getFormEncodedCharset(request), maxFields, maxLength);
    }

    public static CompletableFuture<Fields> from(Request request, Charset charset, int maxFields, int maxLength)
    {
        return from(request, request, charset, maxFields, maxLength);
    }

    static CompletableFuture<Fields> from(Content.Source source, Attributes attributes, Charset charset, int maxFields, int maxLength)
    {
        Object attr = attributes.getAttribute(FormFields.class.getName());
        if (attr instanceof FormFields futureFormFields)
            return futureFormFields;
        else if (attr instanceof Fields fields)
            return CompletableFuture.completedFuture(fields);

        if (charset == null)
            return EMPTY;

        FormFields futureFormFields = new FormFields(source, charset, maxFields, maxLength);
        attributes.setAttribute(FormFields.class.getName(), futureFormFields);
        futureFormFields.parse();
        return futureFormFields;
    }

    private static int getRequestAttribute(Request request, String attribute)
    {
        Object value = request.getAttribute(attribute);
        if (value == null)
            return -1;
        try
        {
            return Integer.parseInt(value.toString());
        }
        catch (NumberFormatException x)
        {
            return -1;
        }
    }

    private final Content.Source _source;
    private final Fields _fields;
    private final CharsetStringBuilder _builder;
    private final int _maxFields;
    private final int _maxLength;
    private int _length;
    private String _name;
    private int _percent = 0;
    private byte _percentCode;

    public FormFields(Content.Source source, Charset charset, int maxFields, int maxSize)
    {
        super(source);
        _source = source;
        _maxFields = maxFields;
        _maxLength = maxSize;
        _builder = CharsetStringBuilder.forCharset(charset);
        _fields = new Fields();
    }

    @Override
    protected Fields parse(Content.Chunk chunk) throws CharacterCodingException
    {
        String value = null;
        ByteBuffer buffer = chunk.getByteBuffer();

        do
        {
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
                            _name = _builder.build();
                            checkLength(_name);
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
                            value = _builder.build();
                            checkLength(value);
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
                if (value == null && chunk.isLast())
                {
                    if (_percent > 0)
                    {
                        _builder.append((byte)'%');
                        _builder.append(_percentCode);
                    }
                    value = _builder.build();
                    checkLength(value);
                }

                if (value != null)
                {
                    Fields.Field field = new Fields.Field(_name, value);
                    _name = null;
                    value = null;
                    if (_maxFields > 0 && _fields.getSize() >= _maxFields)
                        throw new IllegalStateException("form with too many fields > " + _maxFields);
                    _fields.add(field);
                }
            }
        }
        while (BufferUtil.hasContent(buffer));

        return chunk.isLast() ? _fields : null;
    }

    private void checkLength(String nameOrValue)
    {
        if (_maxLength >= 0)
        {
            _length += nameOrValue.length();
            if (_length > _maxLength)
                throw new IllegalStateException("form too large");
        }
    }
}
