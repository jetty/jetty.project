// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.util.ajax;

import junit.framework.TestCase;
/**
 * Test to converts POJOs to JSON and vice versa.
 * 
 * 
 *
 */
public class JSONPojoConvertorTest extends TestCase
{
    
    public void testFoo()
    {
        JSON json = new JSON();
        json.addConvertor(Foo.class, new JSONPojoConvertor(Foo.class));
        json.addConvertor(Bar.class, new JSONPojoConvertor(Bar.class));
        json.addConvertor(Baz.class, new JSONPojoConvertor(Baz.class));
        
        Foo foo = new Foo();
        foo._name = "Foo @ " + System.currentTimeMillis();
        foo._int1 = 1;
        foo._int2 = new Integer(2);
        foo._long1 = 1000001l;
        foo._long2 = new Long(1000002l);
        foo._float1 = 10.11f;
        foo._float2 = new Float(10.22f);
        foo._double1 = 10000.11111d;
        foo._double2 = new Double(10000.22222d);
        
        Bar bar = new Bar("Hello", true, new Baz("World", Boolean.FALSE, foo), new Baz[]{
            new Baz("baz0", Boolean.TRUE, null), new Baz("baz1", Boolean.FALSE, null)
        });
        
        String s = json.toJSON(bar);
        
        Object obj = json.parse(new JSON.StringSource(s));
        
        assertTrue(obj instanceof Bar);
        
        Bar br = (Bar)obj;        
        
        Baz bz = br.getBaz();
        
        Foo f = bz.getFoo();
        
        assertEquals(f, foo);
        assertTrue(br.getBazs().length==2);
        assertEquals(br.getBazs()[0].getMessage(), "baz0");
        assertEquals(br.getBazs()[1].getMessage(), "baz1");
    }
    
    public void testExclude()
    {
        JSON json = new JSON();
        json.addConvertor(Foo.class, new JSONPojoConvertor(Foo.class, 
                new String[]{"name", "long1", "int2"}));
        json.addConvertor(Bar.class, new JSONPojoConvertor(Bar.class, 
                new String[]{"title", "boolean1"}));
        json.addConvertor(Baz.class, new JSONPojoConvertor(Baz.class, 
                new String[]{"boolean2"}));
        
        Foo foo = new Foo();
        foo._name = "Foo @ " + System.currentTimeMillis();
        foo._int1 = 1;
        foo._int2 = new Integer(2);
        foo._long1 = 1000001l;
        foo._long2 = new Long(1000002l);
        foo._float1 = 10.11f;
        foo._float2 = new Float(10.22f);
        foo._double1 = 10000.11111d;
        foo._double2 = new Double(10000.22222d);
        
        Bar bar = new Bar("Hello", true, new Baz("World", Boolean.FALSE, foo));
        
        String s = json.toJSON(bar);
        
        Object obj = json.parse(new JSON.StringSource(s));
        
        assertTrue(obj instanceof Bar);
        
        Bar br = (Bar)obj;        
        
        Baz bz = br.getBaz();
        
        Foo f = bz.getFoo();
        
        assertNull(br.getTitle());
        assertFalse(bar.getTitle().equals(br.getTitle()));
        assertFalse(br.isBoolean1()==bar.isBoolean1());
        assertNull(bz.isBoolean2());
        assertFalse(bar.getBaz().isBoolean2().equals(bz.isBoolean2()));
        assertFalse(f.getLong1()==foo.getLong1());
        assertNull(f.getInt2());
        assertFalse(foo.getInt2().equals(f.getInt2()));
        assertNull(f.getName());        
    }
    
    public static class Bar
    {
        private String _title, _nullTest;
        private Baz _baz;
        private boolean _boolean1;
        private Baz[] _bazs;
        
        public Bar()
        {
            
        }
        
        public Bar(String title, boolean boolean1, Baz baz)
        {
            setTitle(title);
            setBoolean1(boolean1);
            setBaz(baz);
        }
        
        public Bar(String title, boolean boolean1, Baz baz, Baz[] bazs)
        {
            this(title, boolean1, baz);
            setBazs(bazs);
        }
        
        @Override
        public String toString()
        {
            return new StringBuffer().append("\n=== ").append(getClass().getSimpleName()).append(" ===")
                .append("\ntitle: ").append(getTitle())
                .append("\nboolean1: ").append(isBoolean1())
                .append("\nnullTest: ").append(getNullTest())
                .append("\nbaz: ").append(getBaz()).toString();
        }
        
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
            assert(nullTest==null);            
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
    }
    
    public static class Baz
    {
        private String _message;
        private Foo _foo;
        private Boolean _boolean2;
        
        public Baz()
        {
            
        }
        
        public Baz(String message, Boolean boolean2, Foo foo)
        {
            setMessage(message);
            setBoolean2(boolean2);
            setFoo(foo);
        }
        
        @Override
        public String toString()
        {
            return new StringBuffer().append("\n=== ").append(getClass().getSimpleName()).append(" ===")
                .append("\nmessage: ").append(getMessage())
                .append("\nboolean2: ").append(isBoolean2())
                .append("\nfoo: ").append(getFoo()).toString();
        }
        
        public void setMessage(String message)
        {
            _message = message;            
        }
        
        public String getMessage()
        {
            return _message;
        }
        
        public void setFoo(Foo foo)
        {
            _foo = foo;
        }
        
        public Foo getFoo()
        {
            return _foo;
        }
        
        public void setBoolean2(Boolean boolean2)
        {
            _boolean2 = boolean2;
        }
        
        public Boolean isBoolean2()
        {
            return _boolean2;
        }
        
    }
    
    public static class Foo
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
            
        }
        
        @Override
        public String toString()
        {
            return new StringBuffer().append("\n=== ").append(getClass().getSimpleName()).append(" ===")
                .append("\nname: ").append(_name)
                .append("\nint1: ").append(_int1)
                .append("\nint2: ").append(_int2)
                .append("\nlong1: ").append(_long1)
                .append("\nlong2: ").append(_long2)
                .append("\nfloat1: ").append(_float1)
                .append("\nfloat2: ").append(_float2)
                .append("\ndouble1: ").append(_double1)
                .append("\ndouble2: ").append(_double2)                
                .toString();                
        }
        
        @Override
        public boolean equals(Object another)
        {
            if(another instanceof Foo)
            {
                Foo foo = (Foo)another;                
                return getName().equals(foo.getName()) 
                    && getInt1()==foo.getInt1()
                    && getInt2().equals(foo.getInt2())
                    && getLong1()==foo.getLong1()
                    && getLong2().equals(foo.getLong2())
                    && getFloat1()==foo.getFloat1()
                    && getFloat2().equals(foo.getFloat2())
                    && getDouble1()==foo.getDouble1()                    
                    && getDouble2().equals(foo.getDouble2());
            }
            
            return false;
        }
        
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
       
    }    

}
