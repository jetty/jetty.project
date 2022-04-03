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
        MimeTypes.Type type = MimeTypes.CACHE.get(contentType);
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
        return new FutureFormFields(request, charset, -1, -1);
    }

    private final Content.Reader _reader;
    private final Fields _fields = new Fields();
    private final CharsetStringBuilder _builder;
    private final int _maxFields;
    private final int _maxSize;
    private String _name;
    private int _size;

    public FutureFormFields(Content.Reader reader)
    {
        this(reader, StandardCharsets.UTF_8, -1, -1);
    }

    public FutureFormFields(Content.Reader reader, Charset charset, int maxFields, int maxSize)
    {
        _reader = reader;
        _maxFields = maxFields;
        _maxSize = maxSize;
        _builder = CharsetStringBuilder.forCharset(charset);
        run();
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
        while (BufferUtil.hasContent(buffer))
        {
            byte b = buffer.get();
            if (_name == null)
            {
                if (b == '=')
                {
                    _name = _builder.takeString();
                    checkSize(_name);
                }
                else
                {
                    _builder.append(b);
                }
            }
            else
            {
                if (b == '&')
                {
                    value = _builder.takeString();
                    checkSize(value);
                    break;
                }
                else
                {
                    _builder.append(b);
                }
            }
        }

        if (_name != null)
        {
            if (value == null && last)
            {
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
