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

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.ServiceLoader;

import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre encoded HttpField.
 * <p>An HttpField that will be cached and used many times can be created as
 * a {@link PreEncodedHttpField}, which will use the {@link HttpFieldPreEncoder}
 * instances discovered by the {@link ServiceLoader} to pre-encode the header
 * for each version of HTTP in use.  This will save garbage
 * and CPU each time the field is encoded into a response.
 * </p>
 */
public class PreEncodedHttpField extends HttpField
{
    private static final Logger LOG = LoggerFactory.getLogger(PreEncodedHttpField.class);
    private static final EnumMap<HttpVersion, HttpFieldPreEncoder> __encoders = new EnumMap<>(HttpVersion.class);

    static
    {
        TypeUtil.serviceProviderStream(ServiceLoader.load(HttpFieldPreEncoder.class)).forEach(provider ->
        {
            try
            {
                HttpFieldPreEncoder encoder = provider.get();
                HttpFieldPreEncoder existing = __encoders.put(encoder.getHttpVersion(), encoder);
                if (existing != null)
                    LOG.warn("multiple {} for {}", HttpFieldPreEncoder.class.getSimpleName(), encoder.getHttpVersion());
            }
            catch (Error | RuntimeException e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("unable to add {}", HttpFieldPreEncoder.class.getSimpleName(), e);
            }
        });

        if (LOG.isDebugEnabled())
            LOG.debug("loaded {} {}s", __encoders.size(), HttpFieldPreEncoder.class.getSimpleName());

        // Always support HTTP1.
        if (!__encoders.containsKey(HttpVersion.HTTP_1_0))
            __encoders.put(HttpVersion.HTTP_1_0, new Http10FieldPreEncoder());
        if (!__encoders.containsKey(HttpVersion.HTTP_1_1))
            __encoders.put(HttpVersion.HTTP_1_1, new Http11FieldPreEncoder());
    }

    private final EnumMap<HttpVersion, byte[]> _encodedFields = new EnumMap<>(HttpVersion.class);
    private final long _longValue;

    private PreEncodedHttpField(HttpHeader header, String name, String value, long longValue)
    {
        super(header, name, value);
        _longValue = longValue;
        for (HttpFieldPreEncoder encoder : __encoders.values())
        {
            HttpVersion version = encoder.getHttpVersion();
            _encodedFields.put(encoder.getHttpVersion(),
                version == HttpVersion.HTTP_1_1
                    ? _encodedFields.get(HttpVersion.HTTP_1_0)
                    : encoder.getEncodedField(header, name, value));
        }
    }

    public PreEncodedHttpField(HttpHeader header, String name, String value)
    {
        this(header, name, value, Long.MIN_VALUE);
    }

    public PreEncodedHttpField(HttpHeader header, String value)
    {
        this(header, header.asString(), value);
    }

    public PreEncodedHttpField(HttpHeader header, long value)
    {
        this(header, header.asString(), Long.toString(value), value);
    }

    public PreEncodedHttpField(String name, String value)
    {
        this(null, name, value);
    }

    public PreEncodedHttpField(String name, long value)
    {
        this(null, name, Long.toString(value), value);
    }

    public void putTo(ByteBuffer bufferInFillMode, HttpVersion version)
    {
        bufferInFillMode.put(_encodedFields.get(version));
    }

    public int getEncodedLength(HttpVersion version)
    {
        return _encodedFields.get(version).length;
    }

    @Override
    public boolean contains(String search)
    {
        return super.contains(search);
    }

    @Override
    public int getIntValue()
    {
        if (_longValue == Long.MIN_VALUE)
            return super.getIntValue();
        return (int)_longValue;
    }

    @Override
    public long getLongValue()
    {
        if (_longValue == Long.MIN_VALUE)
            return super.getIntValue();
        return _longValue;
    }
}
