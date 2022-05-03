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

import java.util.ListIterator;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;

public class HttpFieldsWrapper implements HttpFields.Mutable
{
    private final HttpFields.Mutable _fields;

    public HttpFieldsWrapper(HttpFields.Mutable fields)
    {
        _fields = fields;
    }

    // TODO a signature that took HttpField would be better.
    // TODO Do we need Put? Could it just be done as a onRemoveField then an onAddField?
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
    public ListIterator<HttpField> listIterator()
    {
        return new ListIterator<>()
        {
            final ListIterator<HttpField> _list = _fields.listIterator();
            HttpField _last;

            @Override
            public boolean hasNext()
            {
                return _list.hasNext();
            }

            @Override
            public HttpField next()
            {
                return _last = _list.next();
            }

            @Override
            public boolean hasPrevious()
            {
                return _list.hasPrevious();
            }

            @Override
            public HttpField previous()
            {
                return _last = _list.previous();
            }

            @Override
            public int nextIndex()
            {
                return _list.nextIndex();
            }

            @Override
            public int previousIndex()
            {
                return _list.previousIndex();
            }

            @Override
            public void remove()
            {
                if (_last != null && HttpFieldsWrapper.this.onRemoveField(_last.getName()))
                    _list.remove();
            }

            @Override
            public void set(HttpField httpField)
            {
                if (_last != null && HttpFieldsWrapper.this.onPutField(_last.getName(), _last.getValue()))
                    _list.set(httpField);
            }

            @Override
            public void add(HttpField httpField)
            {
                // TODO: we don't know if this resulted from a put or an add.
                // if (httpField != null && HttpFieldsWrapper.this.onAddField(httpField.getName(), httpField.getValue()))
                if (_last != null && HttpFieldsWrapper.this.onAddField(_last.getName(), _last.getValue()))
                    _list.add(httpField);
            }
        };
    }
}
