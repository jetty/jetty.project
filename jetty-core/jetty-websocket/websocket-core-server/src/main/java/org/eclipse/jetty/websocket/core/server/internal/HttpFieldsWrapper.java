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

package org.eclipse.jetty.websocket.core.server.internal;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.ListIterator;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;

public class HttpFieldsWrapper implements HttpFields.Mutable
{
    private final HttpFields.Mutable _fields;

    public HttpFieldsWrapper(HttpFields.Mutable fields)
    {
        _fields = fields;
    }

    public boolean onPutField(String name, String value)
    {
        return true;
    }

    public boolean onAddField(String name, String value)
    {
        return true;
    }

    public boolean onRemoveField(String name)
    {
        return true;
    }
    
    @Override
    public Mutable add(String name, String value)
    {
        if (onAddField(name, value))
            return _fields.add(name, value);
        return this;
    }

    @Override
    public Mutable add(HttpHeader header, HttpHeaderValue value)
    {
        if (onAddField(header.name(), value.asString()))
            return _fields.add(header, value);
        return this;
    }

    @Override
    public Mutable add(HttpHeader header, String value)
    {
        if (onAddField(header.name(), value))
            return _fields.add(header, value);
        return this;
    }

    @Override
    public Mutable add(HttpField field)
    {
        if (onAddField(field.getName(), field.getValue()))
            return _fields.add(field);
        return this;
    }

    @Override
    public Mutable add(HttpFields fields)
    {
        for (HttpField field : fields)
        {
            add(field);
        }
        return this;
    }

    @Override
    public Mutable clear()
    {
        return _fields.clear();
    }

    @Override
    public Iterator<HttpField> iterator()
    {
        return _fields.iterator();
    }

    @Override
    public ListIterator<HttpField> listIterator()
    {
        return _fields.listIterator();
    }

    @Override
    public Mutable put(HttpField field)
    {
        if (onPutField(field.getName(), field.getValue()))
            return _fields.put(field);
        return this;
    }

    @Override
    public Mutable put(String name, String value)
    {
        if (onPutField(name, value))
            return _fields.put(name, value);
        return this;
    }

    @Override
    public Mutable put(HttpHeader header, HttpHeaderValue value)
    {
        if (onPutField(header.name(), value.asString()))
            return _fields.put(header, value);
        return this;
    }

    @Override
    public Mutable put(HttpHeader header, String value)
    {
        if (onPutField(header.name(), value))
            return _fields.put(header, value);
        return this;
    }

    @Override
    public Mutable remove(HttpHeader header)
    {
        if (onRemoveField(header.name()))
            return _fields.remove(header);
        return this;
    }

    @Override
    public Mutable remove(EnumSet<HttpHeader> fields)
    {
        for (HttpHeader header : fields)
        {
            remove(header);
        }
        return this;
    }

    @Override
    public Mutable remove(String name)
    {
        if (onRemoveField(name))
            return _fields.remove(name);
        return this;
    }
}
