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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test to converts POJOs to JSON and vice versa.
 */
public class JSONPojoConvertorTest
{
    @Test
    public void testFoo()
    {
        JSON json = new JSON();
        json.addConvertor(Foo.class, new JSONPojoConvertor(Foo.class));
        json.addConvertor(Bar.class, new JSONPojoConvertor(Bar.class));
        json.addConvertor(Baz.class, new JSONPojoConvertor(Baz.class));

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

        String s = json.toJSON(bar);
        Object obj = json.parse(new JSON.StringSource(s));

        assertTrue(obj instanceof Bar);

        Bar br = (Bar)obj;
        Baz bz = br.getBaz();
        Foo f = bz.getFoo();

        assertEquals(foo, f);
        assertEquals(2, br.getBazs().length);
        assertEquals(br.getBazs()[0].getMessage(), "baz0");
        assertEquals(br.getBazs()[1].getMessage(), "baz1");
        assertEquals(Color.Green, br.getColor());
    }

    @Test
    public void testExclude()
    {
        JSON json = new JSON();
        json.addConvertor(Foo.class, new JSONPojoConvertor(Foo.class, new String[]{"name", "long1", "int2"}));
        json.addConvertor(Bar.class, new JSONPojoConvertor(Bar.class, new String[]{"title", "boolean1"}));
        json.addConvertor(Baz.class, new JSONPojoConvertor(Baz.class, new String[]{"boolean2"}));

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

        Bar bar = new Bar("Hello", true, new Baz("World", Boolean.FALSE, foo));

        String s = json.toJSON(bar);
        Object obj = json.parse(new JSON.StringSource(s));

        assertTrue(obj instanceof Bar);

        Bar br = (Bar)obj;
        Baz bz = br.getBaz();
        Foo f = bz.getFoo();

        assertNull(br.getTitle());
        assertNotEquals(bar.getTitle(), br.getTitle());
        assertNotEquals(br.isBoolean1(), bar.isBoolean1());
        assertNull(bz.isBoolean2());
        assertNotEquals(bar.getBaz().isBoolean2(), bz.isBoolean2());
        assertNotEquals(f.getLong1(), foo.getLong1());
        assertNull(f.getInt2());
        assertNotEquals(foo.getInt2(), f.getInt2());
        assertNull(f.getName());
        assertNull(br.getColor());
    }
}
