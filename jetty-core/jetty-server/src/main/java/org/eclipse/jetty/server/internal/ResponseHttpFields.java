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

package org.eclipse.jetty.server.internal;

import java.util.Iterator;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResponseHttpFields implements HttpFields.Mutable
{
    private static final Logger LOG = LoggerFactory.getLogger(ResponseHttpFields.class);
    private final Mutable _fields = HttpFields.build();
    private final AtomicBoolean _committed = new AtomicBoolean();

    public HttpFields.Mutable getMutableHttpFields()
    {
        return _fields;
    }

    public boolean commit()
    {
        boolean committed = _committed.compareAndSet(false, true);
        if (committed && LOG.isDebugEnabled())
            LOG.debug("{} committed", this);
        return committed;
    }

    public boolean isCommitted()
    {
        return _committed.get();
    }

    public void reset()
    {
        _committed.set(false);
        _fields.clear();
    }

    @Override
    public HttpField getField(int index)
    {
        return _fields.getField(index);
    }

    @Override
    public int size()
    {
        return _fields.size();
    }

    @Override
    public Stream<HttpField> stream()
    {
        return _fields.stream();
    }

    @Override
    public Mutable add(HttpField field)
    {
        if (field != null && !_committed.get())
            _fields.add(field);
        return this;
    }

    @Override
    public HttpFields asImmutable()
    {
        return _committed.get() ? this : _fields.asImmutable();
    }

    @Override
    public Mutable clear()
    {
        return _committed.get() ? this : _fields.clear();
    }

    @Override
    public void ensureField(HttpField field)
    {
        if (!_committed.get())
            _fields.ensureField(field);
    }

    @Override
    public Iterator<HttpField> iterator()
    {
        Iterator<HttpField> i = _fields.iterator();
        return new Iterator<>()
        {
            @Override
            public boolean hasNext()
            {
                return i.hasNext();
            }

            @Override
            public HttpField next()
            {
                return i.next();
            }

            @Override
            public void remove()
            {
                if (_committed.get())
                    throw new UnsupportedOperationException("Read Only");
                i.remove();
            }
        };
    }

    @Override
    public ListIterator<HttpField> listIterator()
    {
        ListIterator<HttpField> i = _fields.listIterator();
        return new ListIterator<>()
        {
            @Override
            public boolean hasNext()
            {
                return i.hasNext();
            }

            @Override
            public HttpField next()
            {
                return i.next();
            }

            @Override
            public boolean hasPrevious()
            {
                return i.hasPrevious();
            }

            @Override
            public HttpField previous()
            {
                return i.previous();
            }

            @Override
            public int nextIndex()
            {
                return i.nextIndex();
            }

            @Override
            public int previousIndex()
            {
                return i.previousIndex();
            }

            @Override
            public void remove()
            {
                if (_committed.get())
                    throw new UnsupportedOperationException("Read Only");
                i.remove();
            }

            @Override
            public void set(HttpField field)
            {
                if (_committed.get())
                    throw new UnsupportedOperationException("Read Only");
                if (field == null)
                    i.remove();
                else
                    i.set(field);
            }

            @Override
            public void add(HttpField field)
            {
                if (_committed.get())
                    throw new UnsupportedOperationException("Read Only");
                if (field != null)
                    i.add(field);
            }
        };
    }

    @Override
    public String toString()
    {
        return _fields.toString();
    }
}
