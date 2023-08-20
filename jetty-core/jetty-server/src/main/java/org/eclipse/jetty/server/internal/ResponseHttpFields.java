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
import java.util.concurrent.atomic.AtomicInteger;
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
    private final AtomicInteger _frozen = new AtomicInteger();

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
        clearFields();
    }

    @Override
    public HttpField getField(int index)
    {
        return _fields.getField(index);
    }

    /**
     * Freeze the headers so that existing headers cannot be removed or reset.
     */
    public void freeze()
    {
        _frozen.set(_fields.size());
    }

    /**
     * Reverse a call to {@link #freeze()}
     */
    public void thaw()
    {
        _frozen.set(0);
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
        if (!_committed.get())
            clearFields();
        return this;
    }

    private void clearFields()
    {
        int frozen = _frozen.get();
        if (frozen == 0)
            _fields.clear();
        else
        {
            for (Iterator<HttpField> iterator = _fields.iterator(); iterator.hasNext();)
            {
                iterator.next();
                if (frozen-- <= 0)
                    iterator.remove();
            }
        }
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
            int index;

            @Override
            public boolean hasNext()
            {
                return i.hasNext();
            }

            @Override
            public HttpField next()
            {
                index++;
                return i.next();
            }

            @Override
            public void remove()
            {
                if (_committed.get())
                    throw new UnsupportedOperationException("Read Only");
                int frozen = _frozen.get();
                if (frozen > 0 && index <= frozen)
                    throw new IllegalStateException("Frozen field");
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
            int index;
            boolean forward = true;

            @Override
            public boolean hasNext()
            {
                return i.hasNext();
            }

            @Override
            public HttpField next()
            {
                if (forward)
                    index++;
                forward = true;
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
                if (!forward)
                    index--;
                forward = false;
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
                int frozen = _frozen.get();
                if (frozen > 0 && index <= frozen)
                    throw new IllegalStateException("Frozen field");
                i.remove();
            }

            @Override
            public void set(HttpField field)
            {
                if (_committed.get())
                    throw new UnsupportedOperationException("Read Only");
                int frozen = _frozen.get();
                if (frozen > 0 && index <= frozen)
                    throw new IllegalStateException("Frozen field");
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
                int frozen = _frozen.get();
                if (frozen > 0 && index < frozen)
                    throw new IllegalStateException("Frozen field");
                if (field != null)
                {
                    index++;
                    i.add(field);
                }
            }
        };
    }

    @Override
    public String toString()
    {
        return _fields.toString();
    }
}
