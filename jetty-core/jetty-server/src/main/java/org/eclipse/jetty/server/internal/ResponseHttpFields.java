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
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.server.internal.ResponseHttpFields.PersistentField.isPersistent;

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
        if (!_committed.get())
        {
            for (ListIterator<HttpField> iterator = _fields.listIterator(_fields.size()); iterator.hasPrevious();)
            {
                HttpField field = iterator.previous();
                if (!PersistentField.isPersistent(field))
                    iterator.remove();
            }
        }
        return this;
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
            HttpField _current;

            @Override
            public boolean hasNext()
            {
                return i.hasNext();
            }

            @Override
            public HttpField next()
            {
                _current = i.next();
                return _current;
            }

            @Override
            public void remove()
            {
                if (_committed.get())
                    throw new UnsupportedOperationException("Read Only");
                if (isPersistent(_current))
                    throw new IllegalStateException("Persistent field");
                i.remove();
                _current = null;
            }
        };
    }

    @Override
    public ListIterator<HttpField> listIterator(int index)
    {
        ListIterator<HttpField> i = _fields.listIterator(index);
        return new ListIterator<>()
        {
            HttpField _current;

            @Override
            public boolean hasNext()
            {
                return i.hasNext();
            }

            @Override
            public HttpField next()
            {
                _current = i.next();
                return _current;
            }

            @Override
            public boolean hasPrevious()
            {
                return i.hasPrevious();
            }

            @Override
            public HttpField previous()
            {
                _current = i.previous();
                return _current;
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
                if (isPersistent(_current))
                    throw new IllegalStateException("Persistent field");
                i.remove();
                _current = null;
            }

            @Override
            public void set(HttpField field)
            {
                if (_committed.get())
                    throw new UnsupportedOperationException("Read Only");
                if (isPersistent(_current))
                {
                    // cannot change the field name
                    if (field == null || !field.isSameName(_current))
                        throw new IllegalStateException("Persistent field");

                    // new field must also be persistent
                    if (!isPersistent(field))
                        field = (field instanceof PreEncodedHttpField)
                            ? new PersistentPreEncodedHttpField(field.getHeader(), field.getValue())
                            : new PersistentHttpField(field);
                }
                if (field == null)
                    i.remove();
                else
                    i.set(field);
                _current = field;
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

    /**
     * A marker interface for {@link HttpField}s that cannot be {@link #remove(HttpHeader) removed} or {@link #clear() cleared}
     * from a {@link ResponseHttpFields} instance.   Persistent fields are not immutable in the {@link ResponseHttpFields}
     * and may be replaced with a different value.
     */
    public interface PersistentField
    {
        static boolean isPersistent(HttpField field)
        {
            return field instanceof PersistentField;
        }
    }

    /**
     * A {@link HttpField} that is a {@link PersistentField}.
     */
    public static class PersistentHttpField extends HttpField implements PersistentField
    {
        private final HttpField _field;

        public PersistentHttpField(HttpField field)
        {
            super(field.getHeader(), field.getName(), field.getValue());
            _field = field;
        }

        @Override
        public int getIntValue()
        {
            return _field.getIntValue();
        }

        @Override
        public long getLongValue()
        {
            return _field.getIntValue();
        }
    }

    /**
     * A {@link PreEncodedHttpField} that is a {@link PersistentField}.
     */
    public static class PersistentPreEncodedHttpField extends PreEncodedHttpField implements PersistentField
    {
        public PersistentPreEncodedHttpField(HttpHeader header, String value)
        {
            this(header, value, true);
        }

        public PersistentPreEncodedHttpField(HttpHeader header, String value, boolean immutable)
        {
            super(header, value);
        }
    }
}
