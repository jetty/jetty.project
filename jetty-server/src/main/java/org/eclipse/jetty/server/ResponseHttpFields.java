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

package org.eclipse.jetty.server;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.function.BiFunction;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;

class ResponseHttpFields extends HttpFields.Mutable
{
    private boolean _readOnly;

    HttpFields toReadOnly()
    {
        _readOnly = true;
        return this;
    }

    public boolean isReadOnly()
    {
        return _readOnly;
    }

    public void recycle()
    {
        _readOnly = false;
        super.clear();
    }

    @Override
    public Mutable add(String name, String value)
    {
        return _readOnly ? this : super.add(name, value);
    }

    @Override
    public Mutable add(HttpHeader header, HttpHeaderValue value)
    {
        return super.add(header, value);
    }

    @Override
    public Mutable add(HttpHeader header, String value)
    {
        return _readOnly ? this : super.add(header, value);
    }

    @Override
    public Mutable add(HttpField field)
    {
        return _readOnly ? this : super.add(field);
    }

    @Override
    public Mutable add(HttpFields fields)
    {
        return _readOnly ? this : super.add(fields);
    }

    @Override
    public Mutable addCSV(HttpHeader header, String... values)
    {
        return _readOnly ? this : super.addCSV(header, values);
    }

    @Override
    public Mutable addCSV(String name, String... values)
    {
        return _readOnly ? this : super.addCSV(name, values);
    }

    @Override
    public Mutable addDateField(String name, long date)
    {
        return _readOnly ? this : super.addDateField(name, date);
    }

    @Override
    public HttpFields asImmutable()
    {
        return _readOnly ? this : super.asImmutable();
    }

    @Override
    public Mutable clear()
    {
        return _readOnly ? this : super.clear();
    }

    @Override
    public void ensureField(HttpField field)
    {
        if (!_readOnly)
            super.ensureField(field);
    }

    @Override
    public Iterator<HttpField> iterator()
    {
        Iterator<HttpField> i = super.iterator();
        return new Iterator<HttpField>()
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
                if (_readOnly)
                    throw new UnsupportedOperationException("Read Only");
                i.remove();
            }
        };
    }

    @Override
    public ListIterator<HttpField> listIterator()
    {
        ListIterator<HttpField> i = super.listIterator();
        return new ListIterator<HttpField>()
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
                if (_readOnly)
                    throw new UnsupportedOperationException("Read Only");
                i.remove();
            }

            @Override
            public void set(HttpField httpField)
            {
                if (_readOnly)
                    throw new UnsupportedOperationException("Read Only");
                i.set(httpField);
            }

            @Override
            public void add(HttpField httpField)
            {
                if (_readOnly)
                    throw new UnsupportedOperationException("Read Only");
                i.add(httpField);
            }
        };
    }

    @Override
    public Mutable put(HttpField field)
    {
        return _readOnly ? this : super.put(field);
    }

    @Override
    public Mutable put(String name, String value)
    {
        return _readOnly ? this : super.put(name, value);
    }

    @Override
    public Mutable put(HttpHeader header, HttpHeaderValue value)
    {
        return _readOnly ? this : super.put(header, value);
    }

    @Override
    public Mutable put(HttpHeader header, String value)
    {
        return _readOnly ? this : super.put(header, value);
    }

    @Override
    public Mutable put(String name, List<String> list)
    {
        return _readOnly ? this : super.put(name, list);
    }

    @Override
    public Mutable putDateField(HttpHeader name, long date)
    {
        return _readOnly ? this : super.putDateField(name, date);
    }

    @Override
    public Mutable putDateField(String name, long date)
    {
        return _readOnly ? this : super.putDateField(name, date);
    }

    @Override
    public Mutable putLongField(HttpHeader name, long value)
    {
        return _readOnly ? this : super.putLongField(name, value);
    }

    @Override
    public Mutable putLongField(String name, long value)
    {
        return _readOnly ? this : super.putLongField(name, value);
    }

    @Override
    public void computeField(HttpHeader header, BiFunction<HttpHeader, List<HttpField>, HttpField> computeFn)
    {
        if (_readOnly)
            super.computeField(header, computeFn);
    }

    @Override
    public void computeField(String name, BiFunction<String, List<HttpField>, HttpField> computeFn)
    {
        if (_readOnly)
            super.computeField(name, computeFn);
    }

    @Override
    public Mutable remove(HttpHeader name)
    {
        return _readOnly ? this : super.remove(name);
    }

    @Override
    public Mutable remove(EnumSet<HttpHeader> fields)
    {
        return _readOnly ? this : super.remove(fields);
    }

    @Override
    public Mutable remove(String name)
    {
        return _readOnly ? this : super.remove(name);
    }
}
