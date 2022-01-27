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

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test to convert POJOs to JSON and vice versa with automatic convertor creation.
 */
public class JSONPojoConvertorFactoryTest
{
    @Test
    public void testFoo()
    {
        JSON jsonOut = new JSON();
        JSON jsonIn = new JSON();

        jsonOut.addConvertor(Object.class, new JSONPojoConvertorFactory(jsonOut));
        jsonOut.addConvertor(Enum.class, new JSONEnumConvertor());
        jsonIn.addConvertor(Object.class, new JSONPojoConvertorFactory(jsonIn));
        jsonIn.addConvertor(Enum.class, new JSONEnumConvertor());

        Foo foo = new Foo();
        foo.setName("Foo @ " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
        foo.setInt1(1);
        foo.setInt2(2);
        foo.setLong1(1000001L);
        foo.setLong2(1000002L);
        foo.setFloat1(10.11f);
        foo.setFloat2(10.22f);
        foo.setDouble1(10000.11111d);
        foo.setDouble2(10000.22222d);

        Bar bar = new Bar("Hello", true, new Baz("World", Boolean.FALSE, foo), new Baz[]{
            new Baz("baz0", Boolean.TRUE, null), new Baz("baz1", Boolean.FALSE, null)
        });
        bar.setColor(Color.Green);

        String s = jsonOut.toJSON(bar);
        Object obj = jsonIn.fromJSON(s);

        assertTrue(obj instanceof Bar);

        Bar br = (Bar)obj;
        Baz bz = br.getBaz();
        Foo f = bz.getFoo();

        assertEquals(f, foo);
        assertEquals(2, br.getBazs().length);
        assertEquals(br.getBazs()[0].getMessage(), "baz0");
        assertEquals(br.getBazs()[1].getMessage(), "baz1");
        assertEquals(Color.Green, br.getColor());
    }

    @Test
    public void testFoo2Map()
    {
        JSON jsonOut = new JSON();
        JSON jsonIn = new JSON();

        jsonOut.addConvertor(Object.class, new JSONPojoConvertorFactory(jsonOut, false));
        jsonOut.addConvertor(Enum.class, new JSONEnumConvertor());
        jsonIn.addConvertor(Object.class, new JSONPojoConvertorFactory(jsonIn, false));
        jsonIn.addConvertor(Enum.class, new JSONEnumConvertor());

        Foo foo = new Foo();
        foo.setName("Foo @ " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
        foo.setInt1(1);
        foo.setInt2(2);
        foo.setLong1(1000001L);
        foo.setLong2(1000002L);
        foo.setFloat1(10.11f);
        foo.setFloat2(10.22f);
        foo.setDouble1(10000.11111d);
        foo.setDouble2(10000.22222d);

        Bar bar = new Bar("Hello", true, new Baz("World", Boolean.FALSE, foo), new Baz[]{
            new Baz("baz0", Boolean.TRUE, null), new Baz("baz1", Boolean.FALSE, null)
        });
        bar.setColor(Color.Green);

        String s = jsonOut.toJSON(bar);

        assertFalse(s.contains("class"));

        Object obj = jsonIn.parse(new JSON.StringSource(s));

        assertTrue(obj instanceof Map);

        @SuppressWarnings("unchecked")
        Map<String, Object> br = (Map<String, Object>)obj;
        @SuppressWarnings("unchecked")
        Map<String, Object> bz = (Map<String, Object>)br.get("baz");
        @SuppressWarnings("unchecked")
        Map<String, Object> f = (Map<String, Object>)bz.get("foo");

        assertNotNull(f);
        Object[] bazs = (Object[])br.get("bazs");
        assertEquals(2, bazs.length);
        @SuppressWarnings("unchecked")
        Map<String, Object> map1 = (Map<String, Object>)bazs[0];
        assertEquals(map1.get("message"), "baz0");
        @SuppressWarnings("unchecked")
        Map<String, Object> map2 = (Map<String, Object>)bazs[1];
        assertEquals(map2.get("message"), "baz1");
        assertEquals("Green", br.get("color"));
    }
}
