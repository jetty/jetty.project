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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MIME Type enum and utilities
 */
public class MimeTypes
{
    static Logger LOG = LoggerFactory.getLogger(MimeTypes.class);

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
        private final HttpField _field;

        Type(String s)
        {
            _string = s;
            _base = this;
            _charset = null;
            _charsetString = null;
            _assumedCharset = false;
            _field = new PreEncodedHttpField(HttpHeader.CONTENT_TYPE, _string);
        }

        Type(String s, Type base)
        {
            _string = s;
            _base = base;
            int i = s.indexOf(";charset=");
            _charset = Charset.forName(s.substring(i + 9));
            _charsetString = _charset.toString().toLowerCase(Locale.ENGLISH);
            _assumedCharset = false;
            _field = new PreEncodedHttpField(HttpHeader.CONTENT_TYPE, _string);
        }

        Type(String s, Charset cs)
        {
            _string = s;
            _base = this;
            _charset = cs;
            _charsetString = _charset == null ? null : _charset.toString().toLowerCase(Locale.ENGLISH);
            _assumedCharset = true;
            _field = new PreEncodedHttpField(HttpHeader.CONTENT_TYPE, _string);
        }

        public Charset getCharset()
        {
            return _charset;
        }

        public String getCharsetString()
        {
            return _charsetString;
        }

        public boolean is(String s)
        {
            return _string.equalsIgnoreCase(s);
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

        public Type getBaseType()
        {
            return _base;
        }
    }

    public static Index<Type> CACHE = new Index.Builder<Type>()
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

    protected final Map<String, String> _mimeMap = new HashMap<>();
    protected final Map<String, String> _inferredEncodings = new HashMap<>();
    protected final Map<String, String> _assumedEncodings = new HashMap<>();

    public MimeTypes()
    {
        this(DEFAULTS);
    }

    public MimeTypes(MimeTypes defaults)
    {
        if (defaults != null)
        {
            _mimeMap.putAll(defaults.getMimeMap());
            _assumedEncodings.putAll(defaults.getAssumedMap());
            _inferredEncodings.putAll(defaults.getInferredMap());
        }
    }

    /**
     * Get the MIME type by filename extension.
     *
     * @param filename A file name
     * @return MIME type matching the longest dot extension of the
     * file name.
     */
    public String getMimeByExtension(String filename)
    {
        String type = null;

        if (filename != null)
        {
            int i = -1;
            while (type == null)
            {
                i = filename.indexOf(".", i + 1);

                if (i < 0 || i >= filename.length())
                    break;

                String ext = StringUtil.asciiToLowerCase(filename.substring(i + 1));
                type = getMimeForExtension(ext);
            }
        }

        if (type == null)
        {
            type = getMimeForExtension("*");
        }

        return type;
    }

    public String getMimeForExtension(String extension)
    {
        return _mimeMap.get(extension);
    }

    public String getCharsetInferredFromContentType(String contentType)
    {
        return _inferredEncodings.get(contentType);
    }

    public String getCharsetAssumedFromContentType(String contentType)
    {
        return _assumedEncodings.get(contentType);
    }

    public Map<String, String> getMimeMap()
    {
        return Collections.unmodifiableMap(_mimeMap);
    }

    public Map<String, String> getInferredMap()
    {
        return Collections.unmodifiableMap(_inferredEncodings);
    }

    public Map<String, String> getAssumedMap()
    {
        return Collections.unmodifiableMap(_assumedEncodings);
    }

    public static class Mutable extends MimeTypes
    {
        public Mutable()
        {
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
            return _mimeMap.put(StringUtil.asciiToLowerCase(extension), normalizeMimeType(type));
        }

        public String addInferred(String contentType, String encoding)
        {
            return _inferredEncodings.put(contentType, encoding);
        }

        public String addAssumed(String contentType, String encoding)
        {
            return _assumedEncodings.put(contentType, encoding);
        }
    }

    public static MimeTypes DEFAULTS = new MimeTypes(null)
    {
        {
            for (Type type : Type.values())
            {
                if (type.isCharsetAssumed())
                    _assumedEncodings.put(type.asString(), type.getCharsetString());
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
                                _mimeMap.put(StringUtil.asciiToLowerCase(x), normalizeMimeType(props.getProperty(x))));

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
                                    _assumedEncodings.put(t, charset.substring(1));
                                else
                                    _inferredEncodings.put(t, props.getProperty(t));
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
                        return StringUtil.normalizeCharset(value, start, i - start);
                    break;
                default:
                    throw new IllegalStateException();
            }
        }

        if (state == 10)
            return StringUtil.normalizeCharset(value, start, i - start);

        return null;
    }

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
}
