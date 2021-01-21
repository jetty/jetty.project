//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

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
    private static final Logger LOG = Log.getLogger(PreEncodedHttpField.class);
    private static final HttpFieldPreEncoder[] __encoders;

    static
    {
        List<HttpFieldPreEncoder> encoders = new ArrayList<>();
        Iterator<HttpFieldPreEncoder> iter = ServiceLoader.load(HttpFieldPreEncoder.class).iterator();
        while (iter.hasNext())
        {
            try
            {
                HttpFieldPreEncoder encoder = iter.next();
                if (index(encoder.getHttpVersion()) >= 0)
                    encoders.add(encoder);
            }
            catch (Error | RuntimeException e)
            {
                LOG.debug(e);
            }
        }
        LOG.debug("HttpField encoders loaded: {}", encoders);
        int size = encoders.size();

        __encoders = new HttpFieldPreEncoder[size == 0 ? 1 : size];
        for (HttpFieldPreEncoder e : encoders)
        {
            int i = index(e.getHttpVersion());
            if (__encoders[i] == null)
                __encoders[i] = e;
            else
                LOG.warn("multiple PreEncoders for " + e.getHttpVersion());
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
}
