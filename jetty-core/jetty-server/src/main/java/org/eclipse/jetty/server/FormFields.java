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
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.CharsetStringBuilder;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.StringUtil;

import static org.eclipse.jetty.util.UrlEncoded.decodeHexByte;

/**
 * A {@link CompletableFuture} that is completed once a {@code application/x-www-form-urlencoded}
 * content has been parsed asynchronously from the {@link Content.Source}.
 */
public class FormFields extends CompletableFuture<Fields> implements Runnable
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
        // TODO make this attributes provided by the ContextRequest wrapper
        int maxFields = getRequestAttribute(request, FormFields.MAX_FIELDS_ATTRIBUTE);
        int maxLength = getRequestAttribute(request, FormFields.MAX_LENGTH_ATTRIBUTE);

        return from(request, maxFields, maxLength);
    }

    public static CompletableFuture<Fields> from(Request request, Charset charset)
    {
        // TODO make this attributes provided by the ContextRequest wrapper
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
        Object attr = request.getAttribute(FormFields.class.getName());
        if (attr instanceof FormFields futureFormFields)
            return futureFormFields;
        else if (attr instanceof Fields fields)
            return CompletableFuture.completedFuture(fields);

        Charset charset = getFormEncodedCharset(request);
        if (charset == null)
            return EMPTY;

        return from(request, charset, maxFields, maxLength);
    }

    public static CompletableFuture<Fields> from(Request request, Charset charset, int maxFields, int maxLength)
    {
        FormFields futureFormFields = new FormFields(request, charset, maxFields, maxLength);
        request.setAttribute(FormFields.class.getName(), futureFormFields);
        futureFormFields.run();
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
        _source = source;
        _maxFields = maxFields;
        _maxLength = maxSize;
        _builder = CharsetStringBuilder.forCharset(charset);
        _fields = new Fields();
    }

    @Override
    public void run()
    {
        Content.Chunk chunk = null;
        try
        {
            while (true)
            {
                chunk = _source.read();
                if (chunk == null)
                {
                    _source.demand(this);
                    return;
                }

                if (Content.Chunk.isError(chunk))
                {
                    completeExceptionally(chunk.getCause());
                    return;
                }

                while (true)
                {
                    Fields.Field field = parse(chunk);
                    if (field == null)
                        break;
                    if (_maxFields >= 0 && _fields.getSize() >= _maxFields)
                    {
                        chunk.release();
                        // Do not double release if completeExceptionally() throws.
                        chunk = null;
                        completeExceptionally(new IllegalStateException("form with too many fields"));
                        return;
                    }
                    _fields.add(field);
                }

                chunk.release();
                if (chunk.isLast())
                {
                    // Do not double release if complete() throws.
                    chunk = null;
                    complete(_fields);
                    return;
                }
            }
        }
        catch (Throwable x)
        {
            if (chunk != null)
                chunk.release();
            completeExceptionally(x);
        }
    }

    protected Fields.Field parse(Content.Chunk chunk) throws CharacterCodingException
    {
        String value = null;
        ByteBuffer buffer = chunk.getByteBuffer();
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
                return field;
            }
        }

        return null;
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
