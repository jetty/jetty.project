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
import java.util.concurrent.CompletionException;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ContentSourceCompletableFuture;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.CharsetStringBuilder;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;

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

        HttpField contentTypeField = request.getHeaders().getField(HttpHeader.CONTENT_TYPE);
        if (contentTypeField == null)
            return null;

        MimeTypes.Type type = MimeTypes.getMimeTypeFromContentType(contentTypeField);
        if (type != null)
        {
            if (type.getBaseType() != MimeTypes.Type.FORM_ENCODED)
                return null;

            return type.getCharset() == null ? StandardCharsets.UTF_8 : type.getCharset();
        }

        String contentType = contentTypeField.getValue();
        int semicolon = contentType.indexOf(';');
        if (semicolon >= 0)
            contentType = contentType.substring(0, semicolon).trim();
        if (!MimeTypes.Type.FORM_ENCODED.is(contentType))
            return null;

        Charset charset = MimeTypes.getCharsetFromContentType(contentTypeField);

        return charset == null ? StandardCharsets.UTF_8 : charset;
    }

    /**
     * Set a {@link Fields} or related failure for the request
     * @param request The request to which to associate the fields with
     * @param fields A {@link CompletableFuture} that will provide either the fields or a failure.
     * @deprecated do not use it, no replacement.
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
     * by {@link #onFields(Request, Promise.Invocable)} or similar, then those field will return
     * and this method will not block.
     * <p>
     * Calls to {@code onFields} and {@code getFields} methods are idempotent, and
     * can be called multiple times, with subsequent calls returning the results of the first call.
     * @param request The request to get or read the Fields from
     * @return the Fields
     * @see #onFields(Request, Promise.Invocable)
     * @see #onFields(Request, Charset, Promise.Invocable)
     * @see #getFields(Request, int, int)
     */
    public static Fields getFields(Request request)
    {
        return getFields(request, -1, -1);
    }

    /**
     * Get the Fields from a request. If the Fields have not been set, then attempt to parse them
     * from the Request content, blocking if necessary.   If the Fields have previously been read asynchronously
     * by {@link #onFields(Request, Promise.Invocable)} or similar, then those field will return
     * and this method will not block.
     * <p>
     * Calls to {@code onFields} and {@code getFields} methods are idempotent, and
     * can be called multiple times, with subsequent calls returning the results of the first call.
     * @param request The request to get or read the Fields from
     * @param maxFields The maximum number of fields to accept or -1 for a default.
     * @param maxLength The maximum length of fields or -1 for a default.
     * @return the Fields
     * @see #onFields(Request, Promise.Invocable)
     * @see #onFields(Request, Charset, Promise.Invocable)
     * @see #getFields(Request)
     */
    public static Fields getFields(Request request, int maxFields, int maxLength)
    {
        Charset charset = getFormEncodedCharset(request);
        return join(from(request, InvocationType.NON_BLOCKING, request, charset, maxFields, maxLength));
    }

    /**
     * Get the Fields from a request. If the Fields have not been set, then attempt to parse them
     * from the Request content, blocking if necessary.   If the Fields have previously been read asynchronously
     * by {@link #onFields(Request, Promise.Invocable)} or similar, then those field will return
     * and this method will not block.
     * <p>
     * Calls to {@code onFields} and {@code getFields} methods are idempotent, and
     * can be called multiple times, with subsequent calls returning the results of the first call.
     * @param source The {@link Content.Source} from which to read the fields.
     * @param attributes The {@link Attributes} in which to look for an existing {@link CompletableFuture} of
     *                   {@link FormFields}, using the classname as the attribute name.  If not found the attribute
     *                   is set with the created {@link CompletableFuture} of {@link FormFields}.
     * @param charset the {@link Charset} to use for byte to string conversion.
     * @param maxFields The maximum number of fields to accept or -1 for a default.
     * @param maxLength The maximum length of fields or -1 for a default.
     * @return the Fields
     * @see #onFields(Request, Promise.Invocable)
     * @see #onFields(Request, Charset, Promise.Invocable)
     * @see #getFields(Request)
     * @deprecated use {@link #getFields(Content.Source, Request, Charset, int, int)} instead.
     */
    @Deprecated(since = "12.1.11", forRemoval = true)
    public static Fields getFields(Content.Source source, Attributes attributes, Charset charset, int maxFields, int maxLength)
    {
        if (attributes instanceof Request request)
            return join(from(source, InvocationType.NON_BLOCKING, request, charset, maxFields, maxLength));
        else
            return join(from(source, InvocationType.NON_BLOCKING, attributes, null, charset, maxFields, maxLength));
    }

    /**
     * Get the Fields from a request. If the Fields have not been set, then attempt to parse them
     * from the Request content, blocking if necessary.   If the Fields have previously been read asynchronously
     * by {@link #onFields(Request, Promise.Invocable)} or similar, then those field will return
     * and this method will not block.
     * <p>
     * Calls to {@code onFields} and {@code getFields} methods are idempotent, and
     * can be called multiple times, with subsequent calls returning the results of the first call.
     * @param source The {@link Content.Source} from which to read the fields.
     * @param request The {@link Request} in which to look for an existing {@link CompletableFuture} of
     *                   {@link FormFields}, using the classname as the attribute name.  If not found the attribute
     *                   is set with the created {@link CompletableFuture} of {@link FormFields}.
     * @param charset the {@link Charset} to use for byte to string conversion.
     * @param maxFields The maximum number of fields to accept or -1 for a default.
     * @param maxLength The maximum length of fields or -1 for a default.
     * @return the Fields
     * @see #onFields(Request, Promise.Invocable)
     * @see #onFields(Request, Charset, Promise.Invocable)
     * @see #getFields(Request)
     */
    public static Fields getFields(Content.Source source, Request request, Charset charset, int maxFields, int maxLength)
    {
        return join(from(source, InvocationType.NON_BLOCKING, request, charset, maxFields, maxLength));
    }

    private static Fields join(CompletableFuture<Fields> fields)
    {
        try
        {
            return fields.join();
        }
        catch (CompletionException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException)
                throw runtimeException;
            if (cause instanceof Error error)
                throw error;
            if (cause instanceof HttpException httpException)
                throw new HttpException.RuntimeException(httpException.getCode(), httpException.getReason(), cause);
            throw e;
        }
    }

    /**
     * Asynchronously read and parse FormFields from a {@link Request}.
     * <p>
     * Calls to {@code onFields} and {@code getFields} methods are idempotent, and
     * can be called multiple times, with subsequent calls returning the results of the first call.
     * @param request The request to get or read the Fields from
     * @param promise The action to take when the {@link FormFields} are available.
     * @see #onFields(Request, Charset, Promise.Invocable)
     * @see #getFields(Request)
     * @see #getFields(Request, int, int)
     */
    public static void onFields(Request request, Promise.Invocable<Fields> promise)
    {
        onFields(request, getFormEncodedCharset(request), promise);
    }

    /**
     * Asynchronously read and parse FormFields from a {@link Request}.
     * <p>
     * Calls to {@code onFields} and {@code getFields} methods are idempotent, and
     * can be called multiple times, with subsequent calls returning the results of the first call.
     * @param request The request to get or read the Fields from
     * @param charset The {@link Charset} of the request content, if previously extracted.
     * @param promise The action to take when the FormFields are available.
     * @see #onFields(Request, Charset, Promise.Invocable)
     * @see #getFields(Request)
     * @see #getFields(Request, int, int)
     */
    public static void onFields(Request request, Charset charset, Promise.Invocable<Fields> promise)
    {
        onFields(request, charset, -1, -1, promise);
    }

    /**
     * <p>Asynchronously reads and parses {@link FormFields} from a {@link Request}.</p>
     * <p>Calls to {@code onFields} and {@code getFields} methods are idempotent, and
     * can be called multiple times, with subsequent calls returning the results of the first call.</p>
     *
     * @param request The request to get or read the Fields from
     * @param charset The {@link Charset} of the request content, if previously extracted.
     * @param maxFields The maximum number of fields to accept; or -1 for a default.
     * @param maxLength The maximum length of fields; or -1 for a default.
     * @param promise The action to take when the FormFields are available.
     */
    public static void onFields(Request request, Charset charset, int maxFields, int maxLength, Promise.Invocable<Fields> promise)
    {
        from(request, promise.getInvocationType(), request, charset, maxFields, maxLength).whenComplete(Promise.Invocable.toBiConsumer(promise));
    }

    /**
     * @param request The request to enquire from
     * @return A {@link CompletableFuture} that will provide either the fields or a failure, or null if none set.
     * @see #from(Request)
     * @deprecated use {@link #getFields(Request)} instead.
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
     * @deprecated use {@link #onFields(Request, Promise.Invocable)} instead.
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    public static CompletableFuture<Fields> from(Request request)
    {
        return from(request, -1, -1);
    }

    /**
     * Find or create a {@link FormFields} from a {@link Content.Source}.
     * @param request The {@link Request} in which to look for an existing {@link FormFields} attribute,
     *                using the classname as the attribute name, else the request is used
     *                as a {@link Content.Source} from which to read the fields and set the attribute.
     * @param charset the {@link Charset} to use for byte to string conversion.
     * @return A {@link CompletableFuture} that will provide the {@link Fields} or a failure.
     * @deprecated use {@link #onFields(Request, Charset, Promise.Invocable)} instead.
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    public static CompletableFuture<Fields> from(Request request, Charset charset)
    {
        return from(request, charset, -1, -1);
    }

    /**
     * Find or create a {@link FormFields} from a {@link Content.Source}.
     * @param request The {@link Request} in which to look for an existing {@link FormFields} attribute,
     *                using the classname as the attribute name, else the request is used
     *                as a {@link Content.Source} from which to read the fields and set the attribute.
     * @param maxFields The maximum number of fields to be parsed or -1 for a default.
     * @param maxLength The maximum total size of the fields or -1 for a default.
     * @return A {@link CompletableFuture} that will provide the {@link Fields} or a failure.
     * @deprecated use {@link #onFields(Request, Charset, int, int, Promise.Invocable)} instead.
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
     * @param maxFields The maximum number of fields to be parsed or -1 for a default.
     * @param maxLength The maximum total size of the fields or -1 for a default.
     * @return A {@link CompletableFuture} that will provide the {@link Fields} or a failure.
     * @deprecated use {@link #onFields(Request, Charset, int, int, Promise.Invocable)} instead.
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    public static CompletableFuture<Fields> from(Request request, Charset charset, int maxFields, int maxLength)
    {
        return from(request, InvocationType.NON_BLOCKING, request, charset, maxFields, maxLength);
    }

    /**
     * Find or create a {@link FormFields} from a {@link Content.Source}.
     * @param source The {@link Content.Source} from which to read the fields.
     * @param invocationType The invocation type to use for demand callbacks
     * @param request The {@link Request} in which to look for an existing {@link CompletableFuture} of
     *                   {@link FormFields}, using the classname as the attribute name.  If not found the attribute
     *                   is set with the created {@link CompletableFuture} of {@link FormFields}.
     *                   And is used to look for default values for maxFields and maxLength.
     * @param charset the {@link Charset} to use for byte to string conversion.
     * @param maxFields The maximum number of fields to be parsed or -1 for a default.
     * @param maxLength The maximum total size of the fields or -1 for a default.
     * @return A {@link CompletableFuture} that will provide the {@link Fields} or a failure.
     */
    static CompletableFuture<Fields> from(Content.Source source, InvocationType invocationType, Request request, Charset charset, int maxFields, int maxLength)
    {
        return from(source, invocationType, request, request, charset, maxFields, maxLength);
    }

    /**
     * Find or create a {@link FormFields} from a {@link Content.Source}.
     * @param source The {@link Content.Source} from which to read the fields.
     * @param invocationType The invocation type to use for demand callbacks
     * @param attributes The {@link Attributes} in which to look for an existing {@link CompletableFuture} of
     *                   {@link FormFields}, using the classname as the attribute name.  If not found the attribute
     *                   is set with the created {@link CompletableFuture} of {@link FormFields}.
     * @param request The {@link Request} in which to look for default values for maxFields and maxLength.
     * @param charset the {@link Charset} to use for byte to string conversion.
     * @param maxFields The maximum number of fields to be parsed or -1 for a default.
     * @param maxLength The maximum total size of the fields or -1 for a default.
     * @return A {@link CompletableFuture} that will provide the {@link Fields} or a failure.
     */
    static CompletableFuture<Fields> from(Content.Source source, InvocationType invocationType, Attributes attributes, Request request, Charset charset, int maxFields, int maxLength)
    {
        if (maxFields < 0)
            maxFields = findDefaultMaxFields(request);
        if (maxLength < 0)
            maxLength = findDefaultMaxLength(request);

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

    private static int findDefaultMaxFields(Request request)
    {
        int maxLength;
        if (request != null)
        {
            maxLength = parse(request.getContext().getAttribute(FormFields.MAX_FIELDS_ATTRIBUTE));
            if (maxLength != -1)
                return maxLength;
        }

        // Use value from the system property if it is set.
        String property = System.getProperty(FormFields.MAX_FIELDS_ATTRIBUTE);
        maxLength = parse(property);
        if (maxLength != -1)
            return maxLength;

        return FormFields.MAX_FIELDS_DEFAULT;
    }

    private static int findDefaultMaxLength(Request request)
    {
        int maxLength;
        if (request != null)
        {
            maxLength = parse(request.getContext().getAttribute(FormFields.MAX_LENGTH_ATTRIBUTE));
            if (maxLength != -1)
                return maxLength;
        }

        // Use value from the system property if it is set.
        String property = System.getProperty(FormFields.MAX_LENGTH_ATTRIBUTE);
        maxLength = parse(property);
        if (maxLength != -1)
            return maxLength;

        return FormFields.MAX_LENGTH_DEFAULT;
    }

    private static int parse(Object value)
    {
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
        if (_maxLength > 0 && source.getLength() > _maxLength)
            throw new HttpException.IllegalStateException(HttpStatus.PAYLOAD_TOO_LARGE_413, "form too large > " + _maxLength);
    }

    @Override
    protected Fields parse(Content.Chunk chunk)
    {
        if (_maxLength >= 0)
        {
            _length += chunk.remaining();
            if (_length > _maxLength)
                throw new HttpException.IllegalStateException(HttpStatus.PAYLOAD_TOO_LARGE_413, "form too large > " + _maxLength);
        }

        try
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
                            onNewField(name, "");
                        }
                        case '=' -> _name = _builder.build();
                        case '+' -> _builder.append(' ');
                        case '%' -> _percent++;
                        default ->
                        {
                            _builder.append(b);
                        }
                    }
                }
                else
                {
                    switch (b)
                    {
                        case '&' ->
                        {
                            String value = _builder.build();
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
                throw new HttpException.IllegalStateException(HttpStatus.BAD_REQUEST_400, "invalid percent encoding");
            String value = _builder.build();

            if (_name == null)
            {
                if (!value.isEmpty())
                    onNewField(value, "");
                return _fields;
            }

            onNewField(_name, value);
            return _fields;
        }
        catch (CharacterCodingException e)
        {
            throw new HttpException.IllegalStateException(HttpStatus.BAD_REQUEST_400, "invalid form encoding", e);
        }
    }

    private void onNewField(String name, String value)
    {
        Fields.Field field = new Fields.Field(name, value);
        _fields.add(field);
        if (_maxFields >= 0 && _fields.getSize() > _maxFields)
            throw new HttpException.IllegalStateException(HttpStatus.PAYLOAD_TOO_LARGE_413, "form with too many fields > " + _maxFields);
    }
}
