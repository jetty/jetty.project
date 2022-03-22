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

    public PreEncodedHttpField(HttpHeader header, String name, String value)
    {
        super(header, name, value);
        for (HttpFieldPreEncoder encoder : __encoders.values())
        {
            HttpVersion version = encoder.getHttpVersion();
            _encodedFields.put(encoder.getHttpVersion(),
                version == HttpVersion.HTTP_1_1
                    ? _encodedFields.get(HttpVersion.HTTP_1_0)
                    : encoder.getEncodedField(header, name, value));
        }
    }

    public PreEncodedHttpField(HttpHeader header, String value)
    {
        this(header, header.asString(), value);
    }

    public PreEncodedHttpField(String name, String value)
    {
        this(null, name, value);
    }

    public void putTo(ByteBuffer bufferInFillMode, HttpVersion version)
    {
        bufferInFillMode.put(_encodedFields.get(version));
    }

    public int getEncodedLength(HttpVersion version)
    {
        return _encodedFields.get(version).length;
    }
}
