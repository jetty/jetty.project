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

import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.server.internal.ResponseHttpFields.Persistent.isPersistent;

public class ResponseHttpFields extends HttpFields.Mutable.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(ResponseHttpFields.class);
    private final AtomicBoolean _committed = new AtomicBoolean();

    public ResponseHttpFields()
    {
        super(HttpFields.build());
    }

    public HttpFields.Mutable getMutableHttpFields()
    {
        return getWrapped();
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

    @Override
    public HttpField onAddField(HttpField field)
    {
        if (isCommitted())
            return null;
        return super.onAddField(field);
    }

    @Override
    public boolean onRemoveField(HttpField field)
    {
        if (isCommitted())
            return false;
        if (isPersistent(field))
            throw new UnsupportedOperationException("Persistent field");
        return true;
    }

    @Override
    public HttpField onReplaceField(HttpField oldField, HttpField newField)
    {
        if (isCommitted())
            return oldField;

        if (oldField instanceof Persistent persistent)
        {
            // cannot change the field name
            if (newField == null || !newField.isSameName(oldField))
                throw new UnsupportedOperationException("Persistent field");

            // new field must also be persistent and clear back to the previous value
            newField = (newField instanceof PreEncodedHttpField)
                ? new PersistentPreEncodedHttpField(oldField.getHeader(), newField.getValue(), persistent.getOriginal())
                : new PersistentHttpField(newField, persistent.getOriginal());
        }

        return newField;
    }

    public void recycle()
    {
        _committed.set(false);
        super.clear();
    }

    @Override
    public HttpFields asImmutable()
    {
        return _committed.get() ? this : getMutableHttpFields().asImmutable();
    }

    @Override
    public Mutable clear()
    {
        if (!_committed.get())
        {
            for (ListIterator<HttpField> iterator = getMutableHttpFields().listIterator(size()); iterator.hasPrevious();)
            {
                HttpField field = iterator.previous();
                if (field instanceof Persistent persistent)
                    iterator.set(persistent.getOriginal());
                else
                    iterator.remove();
            }
        }
        return this;
    }

    /**
     * A marker interface for {@link HttpField}s that cannot be {@link #remove(HttpHeader) removed} or {@link #clear() cleared}
     * from a {@link ResponseHttpFields} instance. Persistent fields are not immutable in the {@link ResponseHttpFields}
     * and may be replaced with a different value. i.e. A Persistent field cannot be removed but can be overwritten.
     */
    public interface Persistent
    {
        static boolean isPersistent(HttpField field)
        {
            return field instanceof Persistent;
        }

        /**
         * @return the original persistent field set before any mutations
         */
        HttpField getOriginal();
    }

    /**
     * A {@link HttpField} that is a {@link Persistent}.
     */
    public static class PersistentHttpField extends HttpField implements Persistent
    {
        private final HttpField _field;
        private final HttpField _original;

        public PersistentHttpField(HttpField field)
        {
            this(field, null);
        }

        PersistentHttpField(HttpField field, HttpField original)
        {
            super(field.getHeader(), field.getName(), field.getValue());
            _field = field;
            _original = original == null ? this : original;
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

        @Override
        public HttpField getOriginal()
        {
            return _original;
        }
    }

    /**
     * A {@link PreEncodedHttpField} that is a {@link Persistent}.
     */
    public static class PersistentPreEncodedHttpField extends PreEncodedHttpField implements Persistent
    {
        private final HttpField _original;

        public PersistentPreEncodedHttpField(HttpHeader header, String value)
        {
            this(header, value, null);
        }

        PersistentPreEncodedHttpField(HttpHeader header, String value, HttpField original)
        {
            super(header, value);
            _original = original == null ? this : original;
        }

        @Override
        public HttpField getOriginal()
        {
            return _original;
        }
    }
}
