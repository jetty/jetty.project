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
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MIME Type enum and utilities
 */
public class MimeTypes
{
    static final  Logger LOG = LoggerFactory.getLogger(MimeTypes.class);
    private static final Set<Locale> KNOWN_LOCALES = Set.copyOf(Arrays.stream(Locale.getAvailableLocales()).filter(l -> !StringUtil.isBlank(l.getLanguage())).toList());
    public static final String ISO_8859_1 = StandardCharsets.ISO_8859_1.name().toLowerCase(Locale.ENGLISH);
    public static final String UTF8 = StandardCharsets.UTF_8.name().toLowerCase(Locale.ENGLISH);
    public static final String UTF16 = StandardCharsets.UTF_16.name().toLowerCase(Locale.ENGLISH);
    private static final Index<String> CHARSETS = new Index.Builder<String>()
        .caseSensitive(false)
        .with("utf-8", UTF8)
        .with("utf8", UTF8)
        .with("utf-16", UTF16)
        .with("utf16", UTF16)
        .with("iso-8859-1", ISO_8859_1)
        .with("iso_8859_1", ISO_8859_1)
        .build();
    private static final Index<String> WILDS = new Index.Builder<String>()
        .caseSensitive(false)
        .with("text/", "text/*")
        .with("image/", "image/*")
        .with("application/xml", "application/*")
        .with("multipart/", "multipart/*")
        .build();

    /** Enumeration of predefined MimeTypes. This is not exhaustive */
    public enum Type
    {
        FORM_ENCODED("application/x-www-form-urlencoded"),
        FORM_ENCODED_UTF_8("application/x-www-form-urlencoded;charset=utf-8", FORM_ENCODED),
        FORM_ENCODED_8859_1("application/x-www-form-urlencoded;charset=iso-8859-1", FORM_ENCODED),

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

        TEXT_HTML_8859_1("text/html;charset=iso-8859-1", TEXT_HTML),
        TEXT_HTML_UTF_8("text/html;charset=utf-8", TEXT_HTML),

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
        TEXT_PLAIN_8859_1("text/plain;charset=iso-8859-1", TEXT_PLAIN),
        TEXT_PLAIN_UTF_8("text/plain;charset=utf-8", TEXT_PLAIN),

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
        TEXT_XML_8859_1("text/xml;charset=iso-8859-1", TEXT_XML),
        TEXT_XML_UTF_8("text/xml;charset=utf-8", TEXT_XML),

        TEXT_JSON("text/json", StandardCharsets.UTF_8),
        TEXT_JSON_8859_1("text/json;charset=iso-8859-1", TEXT_JSON),
        TEXT_JSON_UTF_8("text/json;charset=utf-8", TEXT_JSON),

        APPLICATION_JSON("application/json", StandardCharsets.UTF_8),
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
    protected final Set<String> _assumedNoEncodings = new HashSet<>();

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
            _assumedNoEncodings.addAll(defaults._assumedNoEncodings);
        }
    }

    protected void loadMimeProperties(InputStream stream, String resourceName) throws IOException
    {
        if (stream == null)
            throw new IOException("Missing mime-type resource: " + resourceName);
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8))
        {
            Properties props = new Properties();
            props.load(reader);
            props.stringPropertyNames().stream()
                .filter(Objects::nonNull)
                .forEach(x ->
                {
                    if (x.contains("."))
                        LOG.warn("ignoring invalid extension {} from mime.properties {}", x, resourceName);
                    else
                        _mimeMap.put(StringUtil.asciiToLowerCase(x), normalizeMimeType(props.getProperty(x)));
                });
        }
    }

    protected void loadEncodings(InputStream stream, String resourceName) throws IOException
    {
        if (stream == null)
            throw new IOException("Missing encoding resource: " + resourceName);
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8))
        {
            Properties props = new Properties();
            props.load(reader);
            props.stringPropertyNames().stream()
                .filter(Objects::nonNull)
                .forEach(type ->
                {
                    String charset = props.getProperty(type);
                    if ("-".equals(charset))
                        _assumedNoEncodings.add(type);
                    else if (charset.startsWith("-"))
                        _assumedEncodings.put(type, Charset.forName(charset.substring(1)));
                    else
                        _inferredEncodings.put(type, Charset.forName(props.getProperty(type)));
                });
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

    /**
     * @param contentType The content type to obtain a charset for.
     * @return A Charset is returned if it can be inferred from content-type.  This is essentially a default charset
     *         determined for the contentType.  For example, the content-type "text/html" may be configured to have 
     *         an inferred charset of "utf-8", in which case setting that content-type should result in a value
     *         of "text/html;charset=utf8".
     * @see #getAssumedCharset(String) 
     */
    public Charset getInferredCharset(String contentType)
    {
        if (contentType == null)
            return null;
        Charset charset = _inferredEncodings.get(contentType);
        if (charset == null)
            charset = _inferredEncodings.get(toWild(contentType));
        return charset;
    }

    /**
     * @param contentType The content type to obtain a charset for.
     * @return A Charset is returned if it can be assumed from content-type.  This is essentially a known charset
     *         for the specific contentType.  For example, the content-type "application/json" is specified to use utf-8, 
     *         so it has an assumed charset of "utf-8". As this is universally known, there is no need to modify the 
     *         the content-type which will just have a value of "application/json".   Note that some content-types may be
     *         assumed to have no charset, in which case {@link #isCharsetAssumed(String)} must be used.
     * @see #isCharsetAssumed(String) 
     * @see #getAssumedCharset(String)
     */
    public Charset getAssumedCharset(String contentType)
    {
        if (contentType == null)
            return null;
        Charset charset = _assumedEncodings.get(contentType);
        if (charset == null)
            charset = _assumedEncodings.get(toWild(contentType));
        return charset;
    }

    /**
     * @param contentType The content-type to obtain a charset for
     * @return {@code True} if the content-type is assumed to have a specific charset (include assumed to 
     *         have no charset.  For example "application/json" is assumed as it has a specified charset of "utf-8".
     *         Another example is "image/jpeg", which is assumed to have no charset, so it would also return true.
     */
    public boolean isCharsetAssumed(String contentType)
    {
        Charset charset = _assumedEncodings.get(contentType);
        if (charset == null)
        {
            if (_assumedNoEncodings.contains(contentType))
                return true;

            String wild = toWild(contentType);
            charset = _assumedEncodings.get(wild);
            return charset != null || _assumedNoEncodings.contains(wild);
        }
        return false;
    }

    /**
     * @param contentType The content type to obtain a charset for.
     * @return The string value of {@link #getInferredCharset(String)}
     * @see #getInferredCharset(String)
     */
    public String getInferredCharsetName(String contentType)
    {
        return nameOf(getInferredCharset(contentType));
    }

    /**
     * @param contentType The content type to obtain a charset for.
     * @return The string value of {@link #getAssumedCharset(String)};
     *         or the empty string if the type is assumed to not have a charset;
     *         or {@code null} of the type has no assumed charset.
     * @see #getAssumedCharset(String)
     */
    public String getAssumedCharsetName(String contentType)
    {
        if (contentType == null)
            return null;
        Charset charset = getAssumedCharset(contentType);
        if (charset == null)
            return isCharsetAssumed(contentType) ? "" : null;
        return charset.name();
    }

    public Map<String, String> getMimeMap()
    {
        return Collections.unmodifiableMap(_mimeMap);
    }

    public static class Mutable extends MimeTypes
    {
        boolean isDefault;

        public Mutable()
        {
            this(DEFAULTS);
        }

        public Mutable(MimeTypes defaults)
        {
            super(defaults);
            isDefault = defaults == DEFAULTS;
        }

        public boolean isDefault()
        {
            return isDefault;
        }

        /**
         * <p>
         * Clear the MimeTypes references.
         * </p>
         * 
         * <p>
         * Once you have cleared out the MimeTypes object, make sure to properly
         * set it up with extension to mime-type maps, along with inferred and
         * assumed charsets for the relevant mime-types (eg: html, text, json, etc).
         * </p>
         *
         * @see #addMimeMapping(String, String) 
         * @see #addAssumed(String, String) 
         * @see #addInferred(String, String)
         */
        public void clear()
        {
            _mimeMap.clear();
            _assumedEncodings.clear();
            _assumedNoEncodings.clear();
            _inferredEncodings.clear();
        }

        /**
         * Set a mime mapping
         *
         * @param extension the extension
         * @param type the mime type or {@code null} to remove a mapping
         * @return previous value
         */
        public String addMimeMapping(String extension, String type)
        {
            if (extension.contains("."))
                throw new IllegalArgumentException("extensions cannot contain '.'");
            isDefault = false;
            if (type == null)
                return _mimeMap.remove(StringUtil.asciiToLowerCase(extension));
            return _mimeMap.put(StringUtil.asciiToLowerCase(extension), normalizeMimeType(type));
        }

        /**
         * Add an inferred encoding for a mimeType
         * @param mimeType The mimeType to infer an encoding for
         * @param encoding The encoding or {@code null} to remove
         * @return {@code true} if the encoding was added
         */
        public String addInferred(String mimeType, String encoding)
        {
            isDefault = false;
            if (encoding == null)
                return nameOf(_inferredEncodings.remove(normalizeMimeType(mimeType)));
            return nameOf(_inferredEncodings.put(normalizeMimeType(mimeType), Charset.forName(encoding)));
        }

        /**
         * Add an assumed charset encoding for a content-type. Default values for this
         * mapping are set by the "encoding.properties" class path resource.
         * @param mimeType The mimeType to map to an encoding,
         *                 either in absolute form (e.g. "text/html")
         *                 or wildcard form (e.g. "image/*").
         * @param encoding The assumed encoding;
         *                 or the empty string if it is assumed to have no charset;
         *                 or {@code null} if there is no assumed charset.
         * @return The old value.
         */
        public String addAssumed(String mimeType, String encoding)
        {
            isDefault = false;
            if (encoding == null)
            {
                _assumedNoEncodings.remove(mimeType);
                return nameOf(_assumedEncodings.remove(mimeType));
            }

            if (encoding.isEmpty())
            {
                _assumedNoEncodings.add(mimeType);
                return nameOf(_assumedEncodings.remove(mimeType));
            }

            _assumedNoEncodings.remove(mimeType);
            return nameOf(_assumedEncodings.put(mimeType, Charset.forName(encoding)));
        }

        /**
         * Set the mime types from a properties file in the format "ext=mimeType" (e.g. "txt=text/plain")
         * @param mimeProperties The property file to use.
         * @see #addMimeTypes(Resource)
         * @see #addMimeMapping(String, String)
         * @throws UncheckedIOException if there is an {@link IOException}
         */
        public void setMimeTypes(Resource mimeProperties) throws UncheckedIOException
        {
            _mimeMap.clear();
            addMimeTypes(mimeProperties);
        }

        /**
         * Add the mime types from a properties file in the format "ext=mimeType" (e.g. "txt=text/plain")
         * @param mimeProperties The property file to use.
         * @see #addMimeMapping(String, String)
         * @throws UncheckedIOException if there is an {@link IOException}
         */
        public void addMimeTypes(Resource mimeProperties) throws UncheckedIOException
        {
            isDefault = false;
            try
            {
                loadMimeProperties(mimeProperties.newInputStream(), mimeProperties.toString());
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * Set the inferred and assumed encodings from a property file in the format of:
         * <dl>
         *     <dt>mimeType=encoding</dt><dd>An inferred encoding for the mimeType</dd>
         *     <dt>mimeType=-encoding</dt><dd>An assumed encoding for the mimeType</dd>
         *     <dt>mimeType=-</dt><dd>An assumed no encoding for the mimeType</dd>
         * </dl>
         * The mimeType may be a wildcard like "image/*"
         * @param encodingProperties The property file to use.
         * @see #addInferred(String, String)
         * @see #addAssumed(String, String)
         * @see #addEncodings(Resource)
         * @throws UncheckedIOException if there is an {@link IOException}
         */
        public void setEncodings(Resource encodingProperties) throws UncheckedIOException
        {
            _assumedNoEncodings.clear();
            _assumedEncodings.clear();
            _inferredEncodings.clear();
            addEncodings(encodingProperties);
        }

        /**
         * Add the inferred and assumed encodings from a property file in the format of:
         * <dl>
         *     <dt>mimeType=encoding</dt><dd>An inferred encoding for the mimeType</dd>
         *     <dt>mimeType=-encoding</dt><dd>An assumed encoding for the mimeType</dd>
         *     <dt>mimeType=-</dt><dd>An assumed no encoding for the mimeType</dd>
         * </dl>
         * The mimeType may be a wildcard like "image/*"
         * @param encodingProperties The property file to use.
         * @see #addInferred(String, String)
         * @see #addAssumed(String, String)
         * @see #addEncodings(Resource)
         * @throws UncheckedIOException if there is an {@link IOException}
         */
        public void addEncodings(Resource encodingProperties) throws UncheckedIOException
        {
            isDefault = false;
            try
            {
                loadEncodings(encodingProperties.newInputStream(), encodingProperties.toString());
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * Set the {@code MimeTypes} mappings and encodings from another {@code MimeTypes}
         * @param other The other {@code MimeTypes}.
         */
        public void setFrom(MimeTypes other)
        {
            clear();
            _mimeMap.putAll(other.getMimeMap());
            _assumedEncodings.putAll(other._assumedEncodings);
            _inferredEncodings.putAll(other._inferredEncodings);
            _assumedNoEncodings.addAll(other._assumedNoEncodings);
        }

        /**
         * Merge the {@code MimeTypes} mappings and encodings from another {@code MimeTypes}.
         * Any non default values in this instance are kept in preference to the values from the other.
         * @param other The other {@code MimeTypes}.
         */
        public void mergeFrom(MimeTypes other)
        {
            mergeMap(_mimeMap, other._mimeMap, DEFAULTS._mimeMap);
            mergeMap(_assumedEncodings, other._assumedEncodings, DEFAULTS._assumedEncodings);
            mergeMap(_inferredEncodings, other._inferredEncodings, DEFAULTS._inferredEncodings);

            _assumedEncodings.clear();
            _assumedEncodings.putAll(other._assumedEncodings);
            _inferredEncodings.clear();
            _inferredEncodings.putAll(other._inferredEncodings);
            _assumedNoEncodings.clear();
            _assumedNoEncodings.addAll(other._assumedNoEncodings);

            for (String encoding : other._assumedNoEncodings)
            {
                if (!DEFAULTS._assumedNoEncodings.contains(encoding))
                    _assumedNoEncodings.add(encoding);
            }
        }

        private <V> void mergeMap(Map<String, V> target, Map<String, V> other, Map<String, V> reference)
        {
            // handle all reference entries
            for (Map.Entry<String, V> entry : reference.entrySet())
            {
                String key = entry.getKey();
                V value = entry.getValue();
                if (Objects.equals(target.get(key), value))
                {
                    // The target value is default, so replace with any non default value from the other
                    V otherValue = other.get(entry.getKey());
                    if (otherValue == null)
                        target.remove(key);
                    else if (!Objects.equals(value, otherValue))
                        entry.setValue(otherValue);
                }
            }

            // handle non-reference entries
            for (Map.Entry<String, V> entry : other.entrySet())
            {
                if (!reference.containsKey(entry.getKey()))
                    target.put(entry.getKey(), entry.getValue());
            }
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
                loadMimeProperties(stream, resourceName);
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
                loadEncodings(stream, resourceName);
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

        String contentType = field.getValue();
        int semicolon = contentType.indexOf(';');
        if (semicolon >= 0)
            contentType = contentType.substring(0, semicolon).trim();

        String charset = getCharsetFromContentType(field.getValue());
        if (charset != null)
        {
            Type type = MimeTypes.CACHE.get(contentType + "; charset=" + charset);
            if (type != null)
                return type;
        }

        return MimeTypes.CACHE.get(contentType);
    }

    public static String getMimeTypeAsStringFromContentType(HttpField field)
    {
        if (field == null)
            return null;

        assert field.getHeader() == HttpHeader.CONTENT_TYPE;

        if (field instanceof MimeTypes.ContentTypeField contentTypeField)
            return contentTypeField.getMimeType().asString();

        return getBase(field.getValue());
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

    private String toWild(String contentType)
    {
        String wild = WILDS.getBest(contentType);
        if (wild != null)
            return wild;
        int slash = contentType.indexOf('/');
        if (slash > 0)
            return contentType.substring(0, slash) + "/*";
        return "*/*";
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
