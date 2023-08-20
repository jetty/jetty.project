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

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * HTTP Fields. A collection of HTTP header and or Trailer fields.
 *
 * <p>This class is not synchronized as it is expected that modifications will only be performed by a
 * single thread.
 *
 * <p>The cookie handling provided by this class is guided by the Servlet specification and RFC6265.
 */
class ImmutableHttpFields implements HttpFields
{
    final HttpField[] _fields;
    final int _size;

    /**
     * Initialize HttpFields from copy.
     *
     * @param fields the fields to copy data from
     */
    protected ImmutableHttpFields(HttpField[] fields)
    {
        this(fields, fields.length);
    }

    protected ImmutableHttpFields(HttpField[] fields, int size)
    {
        Objects.requireNonNull(fields);
        _fields = fields;
        _size = size;
    }

    @Override
    public HttpFields asImmutable()
    {
        return this;
    }

    @Override
    public int hashCode()
    {
        int hash = 0;
        for (int i = _fields.length; i-- > 0; )
        {
            hash ^= _fields[i].hashCode();
        }
        return hash;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (!(o instanceof org.eclipse.jetty.http.ImmutableHttpFields))
            return false;

        return isEqualTo((HttpFields)o);
    }

    @Override
    public String get(String name)
    {
        // default impl overridden for efficiency
        for (HttpField f : _fields)
        {
            if (f != null && f.is(name))
                return f.getValue();
        }
        return null;
    }

    @Override
    public String get(HttpHeader header)
    {
        // default impl overridden for efficiency
        for (HttpField f : _fields)
        {
            if (f != null && f.getHeader() == header)
                return f.getValue();
        }
        return null;
    }

    @Override
    public HttpField getField(HttpHeader header)
    {
        // default impl overridden for efficiency
        for (HttpField f : _fields)
        {
            if (f != null && f.getHeader() == header)
                return f;
        }
        return null;
    }

    @Override
    public HttpField getField(String name)
    {
        // default impl overridden for efficiency
        for (HttpField f : _fields)
        {
            if (f != null && f.is(name))
                return f;
        }
        return null;
    }

    @Override
    public HttpField getField(int index)
    {
        if (index >= _fields.length)
            throw new NoSuchElementException();
        return _fields[index];
    }

    @Override
    public Iterator<HttpField> iterator()
    {
        return new Iterator<>()
        {
            int _index = 0;

            @Override
            public boolean hasNext()
            {
                return _index < _size;
            }

            @Override
            public HttpField next()
            {
                return _fields[_index++];
            }
        };
    }

    @Override
    public int size()
    {
        return _size;
    }

    @Override
    public Stream<HttpField> stream()
    {
        return Arrays.stream(_fields).filter(Objects::nonNull);
    }

    @Override
    public String toString()
    {
        return asString();
    }
}
