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
import java.util.ArrayList;
import java.util.List;
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
    private static final HttpFieldPreEncoder[] __encoders;

    static
    {
        List<HttpFieldPreEncoder> encoders = new ArrayList<>();
        TypeUtil.serviceProviderStream(ServiceLoader.load(HttpFieldPreEncoder.class)).forEach(provider ->
        {
            try
            {
                HttpFieldPreEncoder encoder = provider.get();
                if (index(encoder.getHttpVersion()) >= 0)
                    encoders.add(encoder);
            }
            catch (Error | RuntimeException e)
            {
                LOG.debug("Unable to add HttpFieldPreEncoder", e);
            }
        });

        LOG.debug("HttpField encoders loaded: {}", encoders);

        int size = 1;
        for (HttpFieldPreEncoder e : encoders)
        {
            size = Math.max(size, index(e.getHttpVersion()) + 1);
        }
        __encoders = new HttpFieldPreEncoder[size];
        for (HttpFieldPreEncoder e : encoders)
        {
            int i = index(e.getHttpVersion());
            if (__encoders[i] == null)
                __encoders[i] = e;
            else
                LOG.warn("multiple PreEncoders for {}", e.getHttpVersion());
        }

        // Always support HTTP1
        if (__encoders[0] == null)
            __encoders[0] = new Http1FieldPreEncoder();
    }

    private static int index(HttpVersion version)
    {
        switch (version)
        {
            case HTTP_1_0:
            case HTTP_1_1:
                return 0;

            case HTTP_2:
                return 1;

            case HTTP_3:
                return 2;

            default:
                return -1;
        }
    }

    private final byte[][] _encodedField = new byte[__encoders.length][];

    public PreEncodedHttpField(HttpHeader header, String name, String value)
    {
        super(header, name, value);
        for (int i = 0; i < __encoders.length; i++)
        {
            if (__encoders[i] != null)
                _encodedField[i] = __encoders[i].getEncodedField(header, name, value);
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
        bufferInFillMode.put(_encodedField[index(version)]);
    }

    public int getEncodedLength(HttpVersion version)
    {
        return _encodedField[index(version)].length;
    }
}
