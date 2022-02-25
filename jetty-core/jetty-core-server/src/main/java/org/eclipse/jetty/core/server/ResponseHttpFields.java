//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.core.server;

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

class ResponseHttpFields implements HttpFields.Builder
{
    private final Builder _fields;
    private final AtomicBoolean _committed = new AtomicBoolean();

    ResponseHttpFields(Builder fields)
    {
        _fields = fields;
    }

    boolean commit()
    {
        return _committed.compareAndSet(false, true);
    }

    public boolean isCommitted()
    {
        return _committed.get();
    }

    public void recycle()
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
        return _fields.takeAsImmutable();
    }

    @Override
    public Builder add(String name, String value)
    {
        return _committed.get() ? this : _fields.add(name, value);
    }

    @Override
    public Builder add(HttpHeader header, HttpHeaderValue value)
    {
        return _fields.add(header, value);
    }

    @Override
    public Builder add(HttpHeader header, String value)
    {
        return _committed.get() ? this : _fields.add(header, value);
    }

    @Override
    public Builder add(HttpField field)
    {
        return _committed.get() ? this : _fields.add(field);
    }

    @Override
    public Builder add(HttpFields fields)
    {
        return _committed.get() ? this : _fields.add(fields);
    }

    @Override
    public Builder addCSV(HttpHeader header, String... values)
    {
        return _committed.get() ? this : _fields.addCSV(header, values);
    }

    @Override
    public Builder addCSV(String name, String... values)
    {
        return _committed.get() ? this : _fields.addCSV(name, values);
    }

    @Override
    public Builder addDateField(String name, long date)
    {
        return _committed.get() ? this : _fields.addDateField(name, date);
    }

    @Override
    public HttpFields asImmutable()
    {
        return _committed.get() ? this : _fields.asImmutable();
    }

    @Override
    public Builder clear()
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
    public Builder put(HttpField field)
    {
        return _committed.get() ? this : _fields.put(field);
    }

    @Override
    public Builder put(String name, String value)
    {
        return _committed.get() ? this : _fields.put(name, value);
    }

    @Override
    public Builder put(HttpHeader header, HttpHeaderValue value)
    {
        return _committed.get() ? this : _fields.put(header, value);
    }

    @Override
    public Builder put(HttpHeader header, String value)
    {
        return _committed.get() ? this : _fields.put(header, value);
    }

    @Override
    public Builder put(String name, List<String> list)
    {
        return _committed.get() ? this : _fields.put(name, list);
    }

    @Override
    public Builder putDateField(HttpHeader name, long date)
    {
        return _committed.get() ? this : _fields.putDateField(name, date);
    }

    @Override
    public Builder putDateField(String name, long date)
    {
        return _committed.get() ? this : _fields.putDateField(name, date);
    }

    @Override
    public Builder putLongField(HttpHeader name, long value)
    {
        return _committed.get() ? this : _fields.putLongField(name, value);
    }

    @Override
    public Builder putLongField(String name, long value)
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
    public Builder remove(HttpHeader name)
    {
        return _committed.get() ? this : _fields.remove(name);
    }

    @Override
    public Builder remove(EnumSet<HttpHeader> fields)
    {
        return _committed.get() ? this : _fields.remove(fields);
    }

    @Override
    public Builder remove(String name)
    {
        return _committed.get() ? this : _fields.remove(name);
    }
}
