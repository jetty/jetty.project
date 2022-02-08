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

public class Foo
{
    private String _name;
    private int _int1;
    private Integer _int2;
    private long _long1;
    private Long _long2;
    private float _float1;
    private Float _float2;
    private double _double1;
    private Double _double2;

    public Foo()
    {
        // Required by the POJO convertor.
    }

    // Getters and setters required by the POJO convertor.

    public String getName()
    {
        return _name;
    }

    public void setName(String name)
    {
        _name = name;
    }

    public int getInt1()
    {
        return _int1;
    }

    public void setInt1(int int1)
    {
        _int1 = int1;
    }

    public Integer getInt2()
    {
        return _int2;
    }

    public void setInt2(Integer int2)
    {
        _int2 = int2;
    }

    public long getLong1()
    {
        return _long1;
    }

    public void setLong1(long long1)
    {
        _long1 = long1;
    }

    public Long getLong2()
    {
        return _long2;
    }

    public void setLong2(Long long2)
    {
        _long2 = long2;
    }

    public float getFloat1()
    {
        return _float1;
    }

    public void setFloat1(float float1)
    {
        _float1 = float1;
    }

    public Float getFloat2()
    {
        return _float2;
    }

    public void setFloat2(Float float2)
    {
        _float2 = float2;
    }

    public double getDouble1()
    {
        return _double1;
    }

    public void setDouble1(double double1)
    {
        _double1 = double1;
    }

    public Double getDouble2()
    {
        return _double2;
    }

    public void setDouble2(Double double2)
    {
        _double2 = double2;
    }

    @Override
    public boolean equals(Object another)
    {
        if (another instanceof Foo)
        {
            Foo foo = (Foo)another;
            return _name.equals(foo.getName()) &&
                _int1 == foo.getInt1() &&
                _int2.equals(foo.getInt2()) &&
                _long1 == foo.getLong1() &&
                _long2.equals(foo.getLong2()) &&
                _float1 == foo.getFloat1() &&
                _float2.equals(foo.getFloat2()) &&
                _double1 == foo.getDouble1() &&
                _double2.equals(foo.getDouble2());
        }

        return false;
    }

    @Override
    public String toString()
    {
        return "\n=== " + getClass().getSimpleName() + " ===" +
            "\nname: " + _name +
            "\nint1: " + _int1 +
            "\nint2: " + _int2 +
            "\nlong1: " + _long1 +
            "\nlong2: " + _long2 +
            "\nfloat1: " + _float1 +
            "\nfloat2: " + _float2 +
            "\ndouble1: " + _double1 +
            "\ndouble2: " + _double2;
    }
}
