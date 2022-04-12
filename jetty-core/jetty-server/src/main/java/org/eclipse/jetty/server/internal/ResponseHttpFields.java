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

package org.eclipse.jetty.server.internal;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;

// TODO: review whether it needs to override these many methods, as it may be enough to override iterator().
public class ResponseHttpFields implements HttpFields.Mutable
{
    private final Mutable _fields = HttpFields.build();
    private final AtomicBoolean _committed = new AtomicBoolean();

    public HttpFields.Mutable getMutableHttpFields()
    {
        return _fields;
    }

    public boolean commit()
    {
        return _committed.compareAndSet(false, true);
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
    public HttpFields takeAsImmutable()
    {
        if (_committed.get())
            return this;
        return _fields.asImmutable();
    }

    @Override
    public Mutable add(String name, String value)
    {
        return _committed.get() ? this : _fields.add(name, value);
    }

    @Override
    public Mutable add(HttpHeader header, HttpHeaderValue value)
    {
        return _fields.add(header, value);
    }

    @Override
    public Mutable add(HttpHeader header, String value)
    {
        return _committed.get() ? this : _fields.add(header, value);
    }

    @Override
    public Mutable add(HttpField field)
    {
        return _committed.get() ? this : _fields.add(field);
    }

    @Override
    public Mutable add(HttpFields fields)
    {
        return _committed.get() ? this : _fields.add(fields);
    }

    @Override
    public Mutable addCSV(HttpHeader header, String... values)
    {
        return _committed.get() ? this : _fields.addCSV(header, values);
    }

    @Override
    public Mutable addCSV(String name, String... values)
    {
        return _committed.get() ? this : _fields.addCSV(name, values);
    }

    @Override
    public Mutable addDateField(String name, long date)
    {
        return _committed.get() ? this : _fields.addDateField(name, date);
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
            public void set(HttpField httpField)
            {
                if (_committed.get())
                    throw new UnsupportedOperationException("Read Only");
                i.set(httpField);
            }

            @Override
            public void add(HttpField httpField)
            {
                if (_committed.get())
                    throw new UnsupportedOperationException("Read Only");
                i.add(httpField);
            }
        };
    }

    @Override
    public Mutable put(HttpField field)
    {
        return _committed.get() ? this : _fields.put(field);
    }

    @Override
    public Mutable put(String name, String value)
    {
        return _committed.get() ? this : _fields.put(name, value);
    }

    @Override
    public Mutable put(HttpHeader header, HttpHeaderValue value)
    {
        return _committed.get() ? this : _fields.put(header, value);
    }

    @Override
    public Mutable put(HttpHeader header, String value)
    {
        return _committed.get() ? this : _fields.put(header, value);
    }

    @Override
    public Mutable put(String name, List<String> list)
    {
        return _committed.get() ? this : _fields.put(name, list);
    }

    @Override
    public Mutable putDateField(HttpHeader name, long date)
    {
        return _committed.get() ? this : _fields.putDateField(name, date);
    }

    @Override
    public Mutable putDateField(String name, long date)
    {
        return _committed.get() ? this : _fields.putDateField(name, date);
    }

    @Override
    public Mutable putLongField(HttpHeader name, long value)
    {
        return _committed.get() ? this : _fields.putLongField(name, value);
    }

    @Override
    public Mutable putLongField(String name, long value)
    {
        return _committed.get() ? this : _fields.putLongField(name, value);
    }

    @Override
    public void computeField(HttpHeader header, BiFunction<HttpHeader, List<HttpField>, HttpField> computeFn)
    {
        if (_committed.get())
            _fields.computeField(header, computeFn);
    }

    @Override
    public void computeField(String name, BiFunction<String, List<HttpField>, HttpField> computeFn)
    {
        if (_committed.get())
            _fields.computeField(name, computeFn);
    }

    @Override
    public Mutable remove(HttpHeader name)
    {
        return _committed.get() ? this : _fields.remove(name);
    }

    @Override
    public Mutable remove(EnumSet<HttpHeader> fields)
    {
        return _committed.get() ? this : _fields.remove(fields);
    }

    @Override
    public Mutable remove(String name)
    {
        return _committed.get() ? this : _fields.remove(name);
    }

    @Override
    public String toString()
    {
        return _fields.toString();
    }
}
