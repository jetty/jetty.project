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

package org.eclipse.jetty.http2.hpack.internal;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;

public class StaticTableHttpField extends HttpField
{
    private final Object _value;

    public StaticTableHttpField(HttpHeader header, String name, String valueString, Object value)
    {
        super(header, name, valueString);
        if (value == null)
            throw new IllegalArgumentException();
        _value = value;
    }

    public StaticTableHttpField(HttpHeader header, String valueString, Object value)
    {
        this(header, header.asString(), valueString, value);
    }

    public StaticTableHttpField(String name, String valueString, Object value)
    {
        super(name, valueString);
        if (value == null)
            throw new IllegalArgumentException();
        _value = value;
    }

    public Object getStaticValue()
    {
        return _value;
    }

    @Override
    public String toString()
    {
        return super.toString() + "(evaluated)";
    }
}
