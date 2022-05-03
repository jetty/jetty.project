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

package org.eclipse.jetty.util.ajax;

public class Bar
{
    private String _title;
    private String _nullTest;
    private Baz _baz;
    private boolean _boolean1;
    private Baz[] _bazs;
    private Color _color;

    public Bar()
    {
        // Required by the POJO convertor.
    }

    public Bar(String title, boolean boolean1, Baz baz)
    {
        this(title, boolean1, baz, null);
    }

    public Bar(String title, boolean boolean1, Baz baz, Baz[] bazs)
    {
        setTitle(title);
        setBoolean1(boolean1);
        setBaz(baz);
        setBazs(bazs);
    }

    // Getters and setters required by the POJO convertor.

    public void setTitle(String title)
    {
        _title = title;
    }

    public String getTitle()
    {
        return _title;
    }

    public void setNullTest(String nullTest)
    {
        assert (nullTest == null);
        _nullTest = nullTest;
    }

    public String getNullTest()
    {
        return _nullTest;
    }

    public void setBaz(Baz baz)
    {
        _baz = baz;
    }

    public Baz getBaz()
    {
        return _baz;
    }

    public void setBoolean1(boolean boolean1)
    {
        _boolean1 = boolean1;
    }

    public boolean isBoolean1()
    {
        return _boolean1;
    }

    public void setBazs(Baz[] bazs)
    {
        _bazs = bazs;
    }

    public Baz[] getBazs()
    {
        return _bazs;
    }

    public Color getColor()
    {
        return _color;
    }

    public void setColor(Color color)
    {
        _color = color;
    }

    @Override
    public String toString()
    {
        return "\n=== " + getClass().getSimpleName() + " ===" +
            "\ntitle: " + getTitle() +
            "\nboolean1: " + isBoolean1() +
            "\nnullTest: " + getNullTest() +
            "\nbaz: " + getBaz() +
            "\ncolor: " + getColor();
    }
}
