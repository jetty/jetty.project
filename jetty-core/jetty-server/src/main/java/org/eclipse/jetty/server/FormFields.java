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
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ContentSourceCompletableFuture;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.CharsetStringBuilder;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.StringUtil;

import static org.eclipse.jetty.util.UrlEncoded.decodeHexByte;

/**
 * <p>A {@link CompletableFuture} that is completed once a {@code application/x-www-form-urlencoded}
 * content has been parsed asynchronously from the {@link Content.Source}.</p>
 * <p><a href="https://url.spec.whatwg.org/#application/x-www-form-urlencoded">Specification</a>.</p>
 */
public class FormFields extends ContentSourceCompletableFuture<Fields>
{
    public static final String MAX_FIELDS_ATTRIBUTE = "org.eclipse.jetty.server.Request.maxFormKeys";
    public static final String MAX_LENGTH_ATTRIBUTE = "org.eclipse.jetty.server.Request.maxFormContentSize";
    public static final int MAX_FIELDS_DEFAULT = 1000;
    public static final int MAX_LENGTH_DEFAULT = 200000;

    private static final CompletableFuture<Fields> EMPTY = CompletableFuture.completedFuture(Fields.EMPTY);

    public static Charset getFormEncodedCharset(Request request)
    {
        HttpConfiguration config = request.getConnectionMetaData().getHttpConfiguration();
        if (!config.getFormEncodedMethods().contains(request.getMethod()))
            return null;

        String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (request.getLength() == 0 || StringUtil.isBlank(contentType))
            return null;

        String contentTypeWithoutCharset = MimeTypes.getContentTypeWithoutCharset(contentType);
        MimeTypes.Type type = MimeTypes.CACHE.get(contentTypeWithoutCharset);
        if (type != null)
        {
            if (type != MimeTypes.Type.FORM_ENCODED)
                return null;
        }
        else
        {
            // Could be a non-cached Content-Type with other parameters such as "application/x-www-form-urlencoded; p=v".
            // Verify that it is actually application/x-www-form-urlencoded.
            int semi = contentTypeWithoutCharset.indexOf(';');
            if (semi > 0)
                contentTypeWithoutCharset = contentTypeWithoutCharset.substring(0, semi);
            if (!MimeTypes.Type.FORM_ENCODED.is(contentTypeWithoutCharset.trim()))
                return null;
        }

        String cs = MimeTypes.getCharsetFromContentType(contentType);
        return StringUtil.isEmpty(cs) ? StandardCharsets.UTF_8 : Charset.forName(cs);
    }

    /**
     * Set a {@link Fields} or related failure for the request
     * @param request The request to which to associate the fields with
     * @param fields A {@link CompletableFuture} that will provide either the fields or a failure.
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    public static void set(Request request, CompletableFuture<Fields> fields)
    {
        request.setAttribute(FormFields.class.getName(), fields);
    }

    /**
     * Set a {@link Fields} or related failure for the request
     * @param request The request to which to associate the fields with
     * @param fields A {@link CompletableFuture} that will provide either the fields or a failure.
     */
    public static void setFields(Request request, Fields fields)
    {
        request.setAttribute(FormFields.class.getName(), fields);
    }

    /**
     * Get the Fields from a request. If the Fields have not been set, then attempt to parse them
     * from the Request content, blocking if necessary.   If the Fields have previously been read asynchronously
     * by {@link #onFields(Request, Promise, Promise.Invocable)} or similar, then those field will return
     * and this method will not block.
     * <p>
     * Calls to {@code onFields} and {@code getFields} methods are idempotent, and
     * can be called multiple times, with subsequent calls returning the results of the first call.
     * @param request The request to get or read the Fields from
     * @return the Fields
     * @see #onFields(Request, Promise, Promise.Invocable)
     * @see #onFields(Request, Charset, Promise, Promise.Invocable)
     * @see #getFields(Request, int, int)
     */
    public static Fields getFields(Request request)
    {
        int maxFields = getContextAttribute(request.getContext(), FormFields.MAX_FIELDS_ATTRIBUTE, FormFields.MAX_FIELDS_DEFAULT);
        int maxLength = getContextAttribute(request.getContext(), FormFields.MAX_LENGTH_ATTRIBUTE, FormFields.MAX_LENGTH_DEFAULT);
        Charset charset = getFormEncodedCharset(request);
        CompletableFuture<Fields> fields = from(request, InvocationType.NON_BLOCKING, request, charset, maxFields, maxLength);
        return fields.join();
    }

    /**
     * Get the Fields from a request. If the Fields have not been set, then attempt to parse them
     * from the Request content, blocking if necessary.   If the Fields have previously been read asynchronously
     * by {@link #onFields(Request, Promise, Promise.Invocable)} or similar, then those field will return
     * and this method will not block.
     * <p>
     * Calls to {@code onFields} and {@code getFields} methods are idempotent, and
     * can be called multiple times, with subsequent calls returning the results of the first call.
     * @param request The request to get or read the Fields from
     * @param maxFields The maximum number of fields to accept
     * @param maxLength The maximum length of fields
     * @return the Fields
     * @see #onFields(Request, Promise, Promise.Invocable)
     * @see #onFields(Request, Charset, Promise, Promise.Invocable)
     * @see #getFields(Request)
     */
    public static Fields getFields(Request request, int maxFields, int maxLength)
    {
        Charset charset = getFormEncodedCharset(request);
        CompletableFuture<Fields> fields = from(request, InvocationType.NON_BLOCKING, request, charset, maxFields, maxLength);
        return fields.join();
    }

    /**
     * Asynchronously read and parse FormFields from a {@link Request}.
     * <p>
     * Calls to {@code onFields} and {@code getFields} methods are idempotent, and
     * can be called multiple times, with subsequent calls returning the results of the first call.
     * @param request The request to get or read the Fields from
     * @param future The action to take when the FormFields are available. The {@link org.eclipse.jetty.util.thread.Invocable.InvocationType}
     *               of this parameter will be used as the type for any implementation calls to {@link Content.Source#demand(Runnable)}.
     * @see #onFields(Request, Charset, Promise, Promise.Invocable)
     * @see #getFields(Request)
     * @see #getFields(Request, int, int)
     */
    public static void onFields(Request request, Promise.Invocable<Fields> future)
    {
        onFields(request, future, future);
    }

    /**
     * Asynchronously read and parse FormFields from a {@link Request}.
     * <p>
     * Calls to {@code onFields} and {@code getFields} methods are idempotent, and
     * can be called multiple times, with subsequent calls returning the results of the first call.
     * @param request The request to get or read the Fields from
     * @param immediate The action to take if the FormFields are available immediately (from within the scope of the call to this method).
     * @param future The action to take when the FormFields are available, if they are not available immediately.  The {@link org.eclipse.jetty.util.thread.Invocable.InvocationType}
     *               of this parameter will be used as the type for any implementation calls to {@link Content.Source#demand(Runnable)}.
     * @see #onFields(Request, Charset, Promise, Promise.Invocable)
     * @see #getFields(Request)
     * @see #getFields(Request, int, int)
     */
    public static void onFields(Request request, Promise<Fields> immediate, Promise.Invocable<Fields> future)
    {
        InvocationType invocationType = future.getInvocationType();
        int maxFields = getContextAttribute(request.getContext(), FormFields.MAX_FIELDS_ATTRIBUTE, FormFields.MAX_FIELDS_DEFAULT);
        int maxLength = getContextAttribute(request.getContext(), FormFields.MAX_LENGTH_ATTRIBUTE, FormFields.MAX_LENGTH_DEFAULT);
        Charset charset = getFormEncodedCharset(request);
        onFields(from(request, invocationType, request, charset, maxFields, maxLength), immediate, future);
    }

    /**
     * Actions to take when parsing FormFields asynchronously from a request is complete
     * <p>
     * Calls to {@code onFields} and {@code getFields} methods are idempotent, and
     * can be called multiple times, with subsequent calls returning the results of the first call.
     * @param request The request to get or read the Fields from
     * @param charset The charset of the form.
     * @param immediate The action to take if the FormFields are available immediately (from within the scope of the call to this method).
     * @param future The action to take when the FormFields are available, if they are not available immediately.  The {@link org.eclipse.jetty.util.thread.Invocable.InvocationType}
     *               of this parameter will be used as the type for any implementation calls to {@link Content.Source#demand(Runnable)}.
     * @see #onFields(Request, Promise, Promise.Invocable)
     * @see #getFields(Request)
     * @see #getFields(Request, int, int)
     */
    public static void onFields(Request request, Charset charset, Promise<Fields> immediate, Promise.Invocable<Fields> future)
    {
        InvocationType invocationType = future.getInvocationType();
        int maxFields = getContextAttribute(request.getContext(), FormFields.MAX_FIELDS_ATTRIBUTE, FormFields.MAX_FIELDS_DEFAULT);
        int maxLength = getContextAttribute(request.getContext(), FormFields.MAX_LENGTH_ATTRIBUTE, FormFields.MAX_FIELDS_DEFAULT);
        onFields(from(request, invocationType, request, charset, maxFields, maxLength), immediate, future);
    }

    private static void onFields(CompletableFuture<Fields> futureFields, Promise<Fields> immediate, Promise.Invocable<Fields> future)
    {
        if (futureFields.isDone())
        {
            Fields fields = null;
            Throwable error = null;
            try
            {
                fields = futureFields.get();
            }
            catch (ExecutionException t)
            {
                error = t.getCause();
            }
            catch (Throwable t)
            {
                error = t;
            }
            if (error != null)
                immediate.failed(error);
            else
                immediate.succeeded(fields);
        }
        else
        {
            futureFields.whenComplete(future);
        }
    }

    /**
     * @param request The request to enquire from
     * @return A {@link CompletableFuture} that will provide either the fields or a failure, or null if none set.
     * @see #from(Request)
     *
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    public static CompletableFuture<Fields> get(Request request)
    {
        Object attr = request.getAttribute(FormFields.class.getName());
        if (attr instanceof FormFields futureFormFields)
            return futureFormFields;
        else if (attr instanceof Fields fields)
            return CompletableFuture.completedFuture(fields);
        return EMPTY;
    }

    /**
     * Find or create a {@link FormFields} from a {@link Content.Source}.
     * @param request The {@link Request} in which to look for an existing {@link FormFields} attribute,
     *                using the classname as the attribute name, else the request is used
     *                as a {@link Content.Source} from which to read the fields and set the attribute.
     * @return A {@link CompletableFuture} that will provide the {@link Fields} or a failure.
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    public static CompletableFuture<Fields> from(Request request)
    {
        int maxFields = getContextAttribute(request.getContext(), FormFields.MAX_FIELDS_ATTRIBUTE, FormFields.MAX_FIELDS_DEFAULT);
        int maxLength = getContextAttribute(request.getContext(), FormFields.MAX_LENGTH_ATTRIBUTE, FormFields.MAX_LENGTH_DEFAULT);
        return from(request, maxFields, maxLength);
    }

    /**
     * Find or create a {@link FormFields} from a {@link Content.Source}.
     * @param request The {@link Request} in which to look for an existing {@link FormFields} attribute,
     *                using the classname as the attribute name, else the request is used
     *                as a {@link Content.Source} from which to read the fields and set the attribute.
     * @param charset the {@link Charset} to use for byte to string conversion.
     * @return A {@link CompletableFuture} that will provide the {@link Fields} or a failure.
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    public static CompletableFuture<Fields> from(Request request, Charset charset)
    {
        int maxFields = getContextAttribute(request.getContext(), FormFields.MAX_FIELDS_ATTRIBUTE, FormFields.MAX_FIELDS_DEFAULT);
        int maxLength = getContextAttribute(request.getContext(), FormFields.MAX_LENGTH_ATTRIBUTE, FormFields.MAX_FIELDS_DEFAULT);
        return from(request, charset, maxFields, maxLength);
    }

    /**
     * Find or create a {@link FormFields} from a {@link Content.Source}.
     * @param request The {@link Request} in which to look for an existing {@link FormFields} attribute,
     *                using the classname as the attribute name, else the request is used
     *                as a {@link Content.Source} from which to read the fields and set the attribute.
     * @param maxFields The maximum number of fields to be parsed
     * @param maxLength The maximum total size of the fields
     * @return A {@link CompletableFuture} that will provide the {@link Fields} or a failure.
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    public static CompletableFuture<Fields> from(Request request, int maxFields, int maxLength)
    {
        return from(request, getFormEncodedCharset(request), maxFields, maxLength);
    }

    /**
     * Find or create a {@link FormFields} from a {@link Content.Source}.
     * @param request The {@link Request} in which to look for an existing {@link FormFields} attribute,
     *                using the classname as the attribute name, else the request is used
     *                as a {@link Content.Source} from which to read the fields and set the attribute.
     * @param charset the {@link Charset} to use for byte to string conversion.
     * @param maxFields The maximum number of fields to be parsed
     * @param maxLength The maximum total size of the fields
     * @return A {@link CompletableFuture} that will provide the {@link Fields} or a failure.
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    public static CompletableFuture<Fields> from(Request request, Charset charset, int maxFields, int maxLength)
    {
        return from(request, InvocationType.NON_BLOCKING, request, charset, maxFields, maxLength);
    }

    static CompletableFuture<Fields> from(Content.Source source, InvocationType invocationType, Attributes attributes, Charset charset, int maxFields, int maxLength)
    {
        Object attr = attributes.getAttribute(FormFields.class.getName());
        if (attr instanceof FormFields futureFormFields)
            return futureFormFields;
        else if (attr instanceof Fields fields)
            return CompletableFuture.completedFuture(fields);

        if (charset == null)
            return EMPTY;

        FormFields futureFormFields = new FormFields(source, invocationType, charset, maxFields, maxLength);
        attributes.setAttribute(FormFields.class.getName(), futureFormFields);
        futureFormFields.parse();
        return futureFormFields;
    }

    private static int getContextAttribute(Context context, String attribute, int defValue)
    {
        Object value = context.getAttribute(attribute);
        if (value == null)
            return defValue;
        try
        {
            return Integer.parseInt(value.toString());
        }
        catch (NumberFormatException x)
        {
            return defValue;
        }
    }

    private final Fields _fields;
    private final CharsetStringBuilder _builder;
    private final int _maxFields;
    private final int _maxLength;
    private int _length;
    private String _name;
    private int _percent = 0;
    private byte _percentCode;

    private FormFields(Content.Source source, InvocationType invocationType, Charset charset, int maxFields, int maxSize)
    {
        super(source, invocationType);
        _maxFields = maxFields;
        _maxLength = maxSize;
        _builder = CharsetStringBuilder.forCharset(charset);
        _fields = new Fields(true);
    }

    @Override
    protected Fields parse(Content.Chunk chunk) throws CharacterCodingException
    {
        ByteBuffer buffer = chunk.getByteBuffer();

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
                    _percent = 0;
                    _builder.append(decodeHexByte((char)_percentCode, (char)b));
                    continue;
                }
            }

            if (_name == null)
            {
                switch (b)
                {
                    case '&' ->
                    {
                        String name = _builder.build();
                        checkMaxLength(name);
                        onNewField(name, "");
                    }
                    case '=' ->
                    {
                        _name = _builder.build();
                        checkMaxLength(_name);
                    }
                    case '+' -> _builder.append(' ');
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
                        String value = _builder.build();
                        checkMaxLength(value);
                        onNewField(_name, value);
                        _name = null;
                    }
                    case '+' -> _builder.append(' ');
                    case '%' -> _percent++;
                    default -> _builder.append(b);
                }
            }
        }

        if (!chunk.isLast())
            return null;

        // Append any remaining %x.
        if (_percent > 0)
            throw new IllegalStateException("invalid percent encoding");
        String value = _builder.build();

        if (_name == null)
        {
            if (!value.isEmpty())
            {
                checkMaxLength(value);
                onNewField(value, "");
            }
            return _fields;
        }

        checkMaxLength(value);
        onNewField(_name, value);
        return _fields;
    }

    private void checkMaxLength(String nameOrValue)
    {
        if (_maxLength >= 0)
        {
            _length += nameOrValue.length();
            if (_length > _maxLength)
                throw new IllegalStateException("form too large > " + _maxLength);
        }
    }

    private void onNewField(String name, String value)
    {
        Fields.Field field = new Fields.Field(name, value);
        _fields.add(field);
        if (_maxFields >= 0 && _fields.getSize() > _maxFields)
            throw new IllegalStateException("form with too many fields > " + _maxFields);
    }
}
