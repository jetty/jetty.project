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

package org.eclipse.jetty.http3.qpack.table;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.util.StringUtil;

public class Entry
{
    private final HttpField _field;
    private int _absoluteIndex;
    private final AtomicInteger _referenceCount = new AtomicInteger(0);

    public Entry()
    {
        this(-1, null);
    }

    public Entry(HttpField field)
    {
        this(-1, field);
    }

    public Entry(int index, HttpField field)
    {
        _field = field;
        _absoluteIndex = index;
    }

    public int getSize()
    {
        return 32 + StringUtil.getLength(_field.getName()) + StringUtil.getLength(_field.getValue());
    }

    public void setIndex(int index)
    {
        _absoluteIndex = index;
    }

    public int getIndex()
    {
        return _absoluteIndex;
    }

    public HttpField getHttpField()
    {
        return _field;
    }

    public void reference()
    {
        _referenceCount.incrementAndGet();
    }

    public int getReferenceCount()
    {
        return _referenceCount.get();
    }

    public boolean isStatic()
    {
        return false;
    }

    public byte[] getStaticHuffmanValue()
    {
        return null;
    }

    @Override
    public String toString()
    {
        return String.format("{%s,%d,%s,%x}", isStatic() ? "S" : "D", _absoluteIndex, _field, hashCode());
    }
}
