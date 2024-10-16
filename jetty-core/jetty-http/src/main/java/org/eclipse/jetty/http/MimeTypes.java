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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MIME Type enum and utilities
 */
public class MimeTypes
{
    static final  Logger LOG = LoggerFactory.getLogger(MimeTypes.class);
    private static final Set<Locale> KNOWN_LOCALES = Set.copyOf(Arrays.asList(Locale.getAvailableLocales()));
    public static final String ISO_8859_1 = StandardCharsets.ISO_8859_1.name().toLowerCase();
    public static final String UTF8 = StandardCharsets.UTF_8.name().toLowerCase();
    public static final String UTF16 = StandardCharsets.UTF_16.name().toLowerCase();
    private static final Index<String> CHARSETS = new Index.Builder<String>()
        .caseSensitive(false)
        .with("utf-8", UTF8)
        .with("utf8", UTF8)
        .with("utf-16", UTF16)
        .with("utf16", UTF16)
        .with("iso-8859-1", ISO_8859_1)
        .with("iso_8859_1", ISO_8859_1)
        .build();

    /** Enumeration of predefined MimeTypes. This is not exhaustive */
    public enum Type
    {
        FORM_ENCODED("application/x-www-form-urlencoded"),
        MESSAGE_HTTP("message/http"),
        MULTIPART_BYTERANGES("multipart/byteranges"),
        MULTIPART_FORM_DATA("multipart/form-data"),

        TEXT_HTML("text/html")
            {
                @Override
                public HttpField getContentTypeField(Charset charset)
                {
                    if (Objects.equals(charset, StandardCharsets.UTF_8))
                        return TEXT_HTML_UTF_8.getContentTypeField();
                    if (Objects.equals(charset, StandardCharsets.ISO_8859_1))
                        return TEXT_HTML_8859_1.getContentTypeField();
                    return super.getContentTypeField(charset);
                }
            },
        TEXT_PLAIN("text/plain")
            {
                @Override
                public HttpField getContentTypeField(Charset charset)
                {
                    if (Objects.equals(charset, StandardCharsets.UTF_8))
                        return TEXT_PLAIN_UTF_8.getContentTypeField();
                    if (Objects.equals(charset, StandardCharsets.ISO_8859_1))
                        return TEXT_PLAIN_8859_1.getContentTypeField();
                    return super.getContentTypeField(charset);
                }
            },
        TEXT_XML("text/xml")
            {
                @Override
                public HttpField getContentTypeField(Charset charset)
                {
                    if (Objects.equals(charset, StandardCharsets.UTF_8))
                        return TEXT_XML_UTF_8.getContentTypeField();
                    if (Objects.equals(charset, StandardCharsets.ISO_8859_1))
                        return TEXT_XML_8859_1.getContentTypeField();
                    return super.getContentTypeField(charset);
                }
            },
        TEXT_JSON("text/json", StandardCharsets.UTF_8),
        APPLICATION_JSON("application/json", StandardCharsets.UTF_8),

        TEXT_HTML_8859_1("text/html;charset=iso-8859-1", TEXT_HTML),
        TEXT_HTML_UTF_8("text/html;charset=utf-8", TEXT_HTML),

        TEXT_PLAIN_8859_1("text/plain;charset=iso-8859-1", TEXT_PLAIN),
        TEXT_PLAIN_UTF_8("text/plain;charset=utf-8", TEXT_PLAIN),

        TEXT_XML_8859_1("text/xml;charset=iso-8859-1", TEXT_XML),
        TEXT_XML_UTF_8("text/xml;charset=utf-8", TEXT_XML),

        TEXT_JSON_8859_1("text/json;charset=iso-8859-1", TEXT_JSON),
        TEXT_JSON_UTF_8("text/json;charset=utf-8", TEXT_JSON),

        APPLICATION_JSON_8859_1("application/json;charset=iso-8859-1", APPLICATION_JSON),
        APPLICATION_JSON_UTF_8("application/json;charset=utf-8", APPLICATION_JSON);

        private final String _string;
        private final Type _base;
        private final Charset _charset;
        private final String _charsetString;
        private final boolean _assumedCharset;
        private final ContentTypeField _field;

        Type(String name)
        {
            _string = name;
            _base = this;
            _charset = null;
            _charsetString = null;
            _assumedCharset = false;
            _field = new ContentTypeField(this);
        }

        Type(String name, Type base)
        {
            _string = name;
            _base = Objects.requireNonNull(base);
            int i = name.indexOf(";charset=");
            _charset = Charset.forName(name.substring(i + 9));
            _charsetString = _charset.toString().toLowerCase(Locale.ENGLISH);
            _assumedCharset = false;
            _field = new ContentTypeField(this);
        }

        Type(String name, Charset cs)
        {
            _string = name;
            _base = this;
            _charset = cs;
            _charsetString = _charset == null ? null : _charset.toString().toLowerCase(Locale.ENGLISH);
            _assumedCharset = true;
            _field = new ContentTypeField(this);
        }

        /**
         * @return The {@link Charset} for this type or {@code null} if it is not known
         */
        public Charset getCharset()
        {
            return _charset;
        }

        public String getCharsetString()
        {
            return _charsetString;
        }

        /**
         * Check if this type is equal to the type passed as a string
         * @param type The type to compare to
         * @return {@code true} if this is the same type
         */
        public boolean is(String type)
        {
            return _string.equalsIgnoreCase(type);
        }

        public String asString()
        {
            return _string;
        }

        @Override
        public String toString()
        {
            return _string;
        }

        /**
         * @return {@code true} If the {@link Charset} for this type is assumed rather than being explicitly declared.
         */
        public boolean isCharsetAssumed()
        {
            return _assumedCharset;
        }

        public HttpField getContentTypeField()
        {
            return _field;
        }

        public HttpField getContentTypeField(Charset charset)
        {
            if (Objects.equals(_charset, charset))
                return _field;
            return new HttpField(HttpHeader.CONTENT_TYPE, getContentTypeWithoutCharset(_string) + ";charset=" + charset.name());
        }

        /**
         * Get the base type of this type, which is the type without a charset specified
         * @return The base type or this type if it is a base type
         */
        public Type getBaseType()
        {
            return _base;
        }
    }

    public static final Index<Type> CACHE = new Index.Builder<Type>()
        .caseSensitive(false)
        .withAll(() ->
        {
            Map<String, Type> result = new HashMap<>();
            
            for (Type type : Type.values())
            {
                String key1 = type.toString();
                result.put(key1, type);

                if (key1.indexOf(";charset=") > 0)
                {
                    String key2 = StringUtil.replace(key1, ";charset=", "; charset=");
                    result.put(key2, type);
                }
            }
            return result;
        })
        .build();

    /**
     * Get the base value, stripped of any parameters
     * @param value The value
     * @return A string with any semicolon separated parameters removed
     */
    public static String getBase(String value)
    {
        int index = value.indexOf(';');
        return index == -1 ? value : value.substring(0, index);
    }

    /**
     * Get the base type of this type, which is the type without a charset specified
     * @param contentType The mimetype as a string
     * @return The base type or this type if it is a base type
     */
    public static Type getBaseType(String contentType)
    {
        if (StringUtil.isEmpty(contentType))
            return null;
        Type type = CACHE.getBest(contentType);
        if (type == null)
        {
            type = CACHE.get(getBase(contentType));
            if (type == null)
                return null;
        }
        return type.getBaseType();
    }

    public static boolean isKnownLocale(Locale locale)
    {
        return KNOWN_LOCALES.contains(locale);
    }

    /**
     * Convert alternate charset names (eg utf8) to normalized
     * name (eg UTF-8).
     *
     * @param charsetName the charset to normalize
     * @return the normalized charset (or null if normalized version not found)
     */
    public static String normalizeCharset(String charsetName)
    {
        String n = CHARSETS.get(charsetName);
        return (n == null) ? charsetName : n;
    }

    /**
     * Convert alternate charset names (eg utf8) to normalized
     * name (eg UTF-8).
     *
     * @param charsetName the charset to normalize
     * @param offset the offset in the charset
     * @param length the length of the charset in the input param
     * @return the normalized charset (or null if not found)
     */
    public static String normalizeCharset(String charsetName, int offset, int length)
    {
        String n = CHARSETS.get(charsetName, offset, length);
        return (n == null) ? charsetName.substring(offset, offset + length) : n;
    }

    /**
     * @param charsetName The name of the charset
     * @return The {@link Charset} for the normalized name
     * @throws UnsupportedEncodingException Thrown if the charset is not known to the JVM.
     */
    public static Charset getKnownCharset(String charsetName) throws UnsupportedEncodingException
    {
        // check encoding is supported
        if (StandardCharsets.UTF_8.name().equalsIgnoreCase(charsetName))
            return StandardCharsets.UTF_8;
        charsetName = normalizeCharset(charsetName);
        if (StandardCharsets.UTF_8.name().equalsIgnoreCase(charsetName))
            return StandardCharsets.UTF_8;
        try
        {
            return Charset.forName(charsetName);
        }
        catch (UnsupportedCharsetException e)
        {
            throw new UnsupportedEncodingException(e.getMessage());
        }
    }

    private static String nameOf(Charset charset)
    {
        return charset == null ? null : charset.name();
    }

    protected final Map<String, String> _mimeMap = new HashMap<>();
    protected final Map<String, Charset> _inferredEncodings = new HashMap<>();
    protected final Map<String, Charset> _assumedEncodings = new HashMap<>();

    public MimeTypes()
    {
        this(DEFAULTS);
    }

    public MimeTypes(MimeTypes defaults)
    {
        if (defaults != null)
        {
            _mimeMap.putAll(defaults.getMimeMap());
            _assumedEncodings.putAll(defaults._assumedEncodings);
            _inferredEncodings.putAll(defaults._inferredEncodings);
        }
    }

    /**
     * Get the explicit, assumed, or inferred Charset for a HttpField containing a mime type value
     * @param field HttpField with a mime type value (e.g. Content-Type)
     * @return A {@link Charset} or null;
     * @throws  IllegalCharsetNameException
     *          If the given charset name is illegal
     * @throws  UnsupportedCharsetException
     *          If no support for the named charset is available
     *          in this instance of the Java virtual machine
     */
    public Charset getCharset(HttpField field) throws IllegalCharsetNameException, UnsupportedCharsetException
    {
        if (field instanceof ContentTypeField contentTypeField)
            return contentTypeField.getMimeType().getCharset();
        return getCharset(field.getValue());
    }

    /**
     * Get the explicit, assumed, or inferred Charset for a mime type
     * @param mimeType String form or a mimeType
     * @return A {@link Charset} or null;
     * @throws  IllegalCharsetNameException
     *          If the given charset name is illegal
     * @throws  UnsupportedCharsetException
     *          If no support for the named charset is available
     *          in this instance of the Java virtual machine
     */
    public Charset getCharset(String mimeType) throws IllegalCharsetNameException, UnsupportedCharsetException
    {
        if (mimeType == null)
            return null;

        MimeTypes.Type mime = MimeTypes.CACHE.get(mimeType);
        if (mime != null && mime.getCharset() != null)
            return mime.getCharset();

        String charsetName = MimeTypes.getCharsetFromContentType(mimeType);
        if (charsetName != null)
            return Charset.forName(charsetName);

        Charset charset = getAssumedCharset(mimeType);
        if (charset != null)
            return charset;

        charset = getInferredCharset(mimeType);
        return charset;
    }

    /**
     * Get the MIME type by filename extension.
     *
     * @param filename A file name
     * @return MIME type matching the last dot extension of the
     * file name, or matching "*" if none found.
     */
    public String getMimeByExtension(String filename)
    {
        String ext = FileID.getExtension(filename);
        return getMimeForExtension(Objects.requireNonNullElse(ext, "*"));
    }

    public String getMimeForExtension(String extension)
    {
        return _mimeMap.get(extension);
    }

    public Charset getInferredCharset(String contentType)
    {
        return _inferredEncodings.get(contentType);
    }

    public Charset getAssumedCharset(String contentType)
    {
        return _assumedEncodings.get(contentType);
    }

    public String getCharsetInferredFromContentType(String contentType)
    {
        return nameOf(_inferredEncodings.get(contentType));
    }

    public String getCharsetAssumedFromContentType(String contentType)
    {
        return nameOf(_assumedEncodings.get(contentType));
    }

    public Map<String, String> getMimeMap()
    {
        return Collections.unmodifiableMap(_mimeMap);
    }

    public Map<String, String> getInferredMap()
    {
        return _inferredEncodings.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().name()));
    }

    public Map<String, String> getAssumedMap()
    {
        return _assumedEncodings.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().name()));
    }

    public static class Mutable extends MimeTypes
    {
        public Mutable()
        {
            this(DEFAULTS);
        }

        public Mutable(MimeTypes defaults)
        {
            super(defaults);
        }

        /**
         * Set a mime mapping
         *
         * @param extension the extension
         * @param type the mime type
         * @return previous value
         */
        public String addMimeMapping(String extension, String type)
        {
            if (extension.contains("."))
                throw new IllegalArgumentException("extensions cannot contain '.'");
            return _mimeMap.put(StringUtil.asciiToLowerCase(extension), normalizeMimeType(type));
        }

        public String addInferred(String contentType, String encoding)
        {
            return nameOf(_inferredEncodings.put(contentType, Charset.forName(encoding)));
        }

        public String addAssumed(String contentType, String encoding)
        {
            return nameOf(_assumedEncodings.put(contentType, Charset.forName(encoding)));
        }
    }

    public static class Wrapper extends Mutable
    {
        private MimeTypes _wrapped;

        public Wrapper()
        {
            super(null);
        }

        public MimeTypes getWrapped()
        {
            return _wrapped;
        }

        public void setWrapped(MimeTypes wrapped)
        {
            _wrapped = wrapped;
        }

        @Override
        public String getMimeForExtension(String extension)
        {
            String mime = super.getMimeForExtension(extension);
            return mime == null && _wrapped != null ? _wrapped.getMimeForExtension(extension) : mime;
        }

        @Override
        public String getCharsetInferredFromContentType(String contentType)
        {
            String charset = super.getCharsetInferredFromContentType(contentType);
            return charset == null && _wrapped != null ? _wrapped.getCharsetInferredFromContentType(contentType) : charset;
        }

        @Override
        public String getCharsetAssumedFromContentType(String contentType)
        {
            String charset = super.getCharsetAssumedFromContentType(contentType);
            return charset == null && _wrapped != null ? _wrapped.getCharsetAssumedFromContentType(contentType) : charset;
        }

        @Override
        public Map<String, String> getMimeMap()
        {
            Map<String, String> map = super.getMimeMap();
            if (_wrapped == null || map.isEmpty())
                return map;
            map = new HashMap<>(map);
            map.putAll(_wrapped.getMimeMap());
            return Collections.unmodifiableMap(map);
        }

        @Override
        public Map<String, String> getInferredMap()
        {
            Map<String, String> map = super.getInferredMap();
            if (_wrapped == null || map.isEmpty())
                return map;
            map = new HashMap<>(map);
            map.putAll(_wrapped.getInferredMap());
            return Collections.unmodifiableMap(map);
        }

        @Override
        public Map<String, String> getAssumedMap()
        {
            Map<String, String> map = super.getAssumedMap();
            if (_wrapped == null || map.isEmpty())
                return map;
            map = new HashMap<>(map);
            map.putAll(_wrapped.getAssumedMap());
            return Collections.unmodifiableMap(map);
        }
    }

    public static final MimeTypes DEFAULTS = new MimeTypes(null)
    {
        {
            for (Type type : Type.values())
            {
                if (type.isCharsetAssumed())
                    _assumedEncodings.put(type.asString(), type.getCharset());
            }

            String resourceName = "mime.properties";
            try (InputStream stream = MimeTypes.class.getResourceAsStream(resourceName))
            {
                if (stream == null)
                {
                    LOG.warn("Missing mime-type resource: {}", resourceName);
                }
                else
                {
                    try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8))
                    {
                        Properties props = new Properties();
                        props.load(reader);
                        props.stringPropertyNames().stream()
                            .filter(Objects::nonNull)
                            .forEach(x ->
                            {
                                if (x.contains("."))
                                    LOG.warn("ignoring invalid extension {} from mime.properties", x);
                                else
                                    _mimeMap.put(StringUtil.asciiToLowerCase(x), normalizeMimeType(props.getProperty(x)));
                            });

                        if (_mimeMap.isEmpty())
                        {
                            LOG.warn("Empty mime types at {}", resourceName);
                        }
                        else if (_mimeMap.size() < props.keySet().size())
                        {
                            LOG.warn("Duplicate or null mime-type extension in resource: {}", resourceName);
                        }
                    }
                    catch (IOException e)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.warn("Unable to read mime-type resource: {}", resourceName, e);
                        else
                            LOG.warn("Unable to read mime-type resource: {} - {}", resourceName, e.toString());
                    }
                }
            }
            catch (IOException e)
            {
                if (LOG.isDebugEnabled())
                    LOG.warn("Unable to load mime-type resource: {}", resourceName, e);
                else
                    LOG.warn("Unable to load mime-type resource: {} - {}", resourceName, e.toString());
            }

            resourceName = "encoding.properties";
            try (InputStream stream = MimeTypes.class.getResourceAsStream(resourceName))
            {
                if (stream == null)
                    LOG.warn("Missing encoding resource: {}", resourceName);
                else
                {
                    try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8))
                    {
                        Properties props = new Properties();
                        props.load(reader);
                        props.stringPropertyNames().stream()
                            .filter(Objects::nonNull)
                            .forEach(t ->
                            {
                                String charset = props.getProperty(t);
                                if (charset.startsWith("-"))
                                    _assumedEncodings.put(t, Charset.forName(charset.substring(1)));
                                else
                                    _inferredEncodings.put(t, Charset.forName(props.getProperty(t)));
                            });

                        if (_inferredEncodings.isEmpty())
                        {
                            LOG.warn("Empty encodings at {}", resourceName);
                        }
                        else if ((_inferredEncodings.size() + _assumedEncodings.size()) < props.keySet().size())
                        {
                            LOG.warn("Null or duplicate encodings in resource: {}", resourceName);
                        }
                    }
                    catch (IOException e)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.warn("Unable to read encoding resource: {}", resourceName, e);
                        else
                            LOG.warn("Unable to read encoding resource: {} - {}", resourceName, e.toString());
                    }
                }
            }
            catch (IOException e)
            {
                if (LOG.isDebugEnabled())
                    LOG.warn("Unable to load encoding resource: {}", resourceName, e);
                else
                    LOG.warn("Unable to load encoding resource: {} - {}", resourceName, e.toString());
            }
        }
    };

    private static String normalizeMimeType(String type)
    {
        Type t = CACHE.get(type);
        if (t != null)
            return t.asString();

        return StringUtil.asciiToLowerCase(type);
    }

    public static MimeTypes.Type getMimeTypeFromContentType(HttpField field)
    {
        if (field == null)
            return null;

        assert field.getHeader() == HttpHeader.CONTENT_TYPE;

        if (field instanceof MimeTypes.ContentTypeField contentTypeField)
            return contentTypeField.getMimeType();

        return MimeTypes.CACHE.get(field.getValue());
    }

    /**
     * Efficiently extract the charset value from a {@code Content-Type} {@link HttpField}.
     * @param field A {@code Content-Type} field.
     * @return The {@link Charset}
     */
    public static Charset getCharsetFromContentType(HttpField field)
    {
        if (field == null)
            return null;

        assert field.getHeader() == HttpHeader.CONTENT_TYPE;

        if (field instanceof ContentTypeField contentTypeField)
            return contentTypeField._type.getCharset();

        String charset = getCharsetFromContentType(field.getValue());
        if (charset == null)
            return null;

        return Charset.forName(charset);
    }

    /**
     * Efficiently extract the charset value from a {@code Content-Type} string
     * @param value A content-type value (e.g. {@code text/plain; charset=utf8}).
     * @return The charset value (e.g. {@code utf-8}).
     */
    public static String getCharsetFromContentType(String value)
    {
        if (value == null)
            return null;
        int end = value.length();
        int state = 0;
        int start = 0;
        boolean quote = false;
        int i = 0;
        for (; i < end; i++)
        {
            char b = value.charAt(i);

            if (quote && state != 10)
            {
                if ('"' == b)
                    quote = false;
                continue;
            }

            if (';' == b && state <= 8)
            {
                state = 1;
                continue;
            }

            switch (state)
            {
                case 0:
                    if ('"' == b)
                    {
                        quote = true;
                        break;
                    }
                    break;

                case 1:
                    if ('c' == b)
                        state = 2;
                    else if (' ' != b)
                        state = 0;
                    break;
                case 2:
                    if ('h' == b)
                        state = 3;
                    else
                        state = 0;
                    break;
                case 3:
                    if ('a' == b)
                        state = 4;
                    else
                        state = 0;
                    break;
                case 4:
                    if ('r' == b)
                        state = 5;
                    else
                        state = 0;
                    break;
                case 5:
                    if ('s' == b)
                        state = 6;
                    else
                        state = 0;
                    break;
                case 6:
                    if ('e' == b)
                        state = 7;
                    else
                        state = 0;
                    break;
                case 7:
                    if ('t' == b)
                        state = 8;
                    else
                        state = 0;
                    break;
                case 8:
                    if ('=' == b)
                        state = 9;
                    else if (' ' != b)
                        state = 0;
                    break;
                case 9:
                    if (' ' == b)
                        break;
                    if ('"' == b)
                    {
                        quote = true;
                        start = i + 1;
                        state = 10;
                        break;
                    }
                    start = i;
                    state = 10;
                    break;
                case 10:
                    if (!quote && (';' == b || ' ' == b) ||
                        (quote && '"' == b))
                        return normalizeCharset(value, start, i - start);
                    break;
                default:
                    throw new IllegalStateException();
            }
        }

        if (state == 10)
            return normalizeCharset(value, start, i - start);

        return null;
    }

    /**
     * Efficiently extract the base mime-type from a content-type value
     * @param value A content-type value (e.g. {@code text/plain; charset=utf8}).
     * @return The base mime-type value (e.g. {@code text/plain}).
     */
    public static String getContentTypeWithoutCharset(String value)
    {
        int end = value.length();
        int state = 0;
        int start = 0;
        boolean quote = false;
        int i = 0;
        StringBuilder builder = null;
        for (; i < end; i++)
        {
            char b = value.charAt(i);

            if ('"' == b)
            {
                quote = !quote;

                switch (state)
                {
                    case 11:
                        builder.append(b);
                        break;
                    case 10:
                        break;
                    case 9:
                        builder = new StringBuilder();
                        builder.append(value, 0, start + 1);
                        state = 10;
                        break;
                    default:
                        start = i;
                        state = 0;
                }
                continue;
            }

            if (quote)
            {
                if (builder != null && state != 10)
                    builder.append(b);
                continue;
            }

            switch (state)
            {
                case 0:
                    if (';' == b)
                        state = 1;
                    else if (' ' != b)
                        start = i;
                    break;

                case 1:
                    if ('c' == b)
                        state = 2;
                    else if (' ' != b)
                        state = 0;
                    break;
                case 2:
                    if ('h' == b)
                        state = 3;
                    else
                        state = 0;
                    break;
                case 3:
                    if ('a' == b)
                        state = 4;
                    else
                        state = 0;
                    break;
                case 4:
                    if ('r' == b)
                        state = 5;
                    else
                        state = 0;
                    break;
                case 5:
                    if ('s' == b)
                        state = 6;
                    else
                        state = 0;
                    break;
                case 6:
                    if ('e' == b)
                        state = 7;
                    else
                        state = 0;
                    break;
                case 7:
                    if ('t' == b)
                        state = 8;
                    else
                        state = 0;
                    break;
                case 8:
                    if ('=' == b)
                        state = 9;
                    else if (' ' != b)
                        state = 0;
                    break;
                case 9:
                    if (' ' == b)
                        break;
                    builder = new StringBuilder();
                    builder.append(value, 0, start + 1);
                    state = 10;
                    break;
                case 10:
                    if (';' == b)
                    {
                        builder.append(b);
                        state = 11;
                    }
                    break;
                case 11:
                    if (' ' != b)
                        builder.append(b);
                    break;
                default:
                    throw new IllegalStateException();
            }
        }
        if (builder == null)
            return value;
        return builder.toString();
    }

    /**
     * A {@link PreEncodedHttpField} for `Content-Type` that can hold a {@link MimeTypes.Type} field
     * for later recovery.
     */
    static class ContentTypeField extends PreEncodedHttpField
    {
        private final Type _type;

        public ContentTypeField(MimeTypes.Type type)
        {
            this(type, type.toString());
        }

        public ContentTypeField(MimeTypes.Type type, String value)
        {
            super(HttpHeader.CONTENT_TYPE, value);
            _type = type;
        }

        public Type getMimeType()
        {
            return _type;
        }
    }
}
