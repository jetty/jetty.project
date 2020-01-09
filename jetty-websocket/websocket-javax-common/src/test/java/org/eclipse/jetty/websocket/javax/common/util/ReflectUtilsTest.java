//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.common.util;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ReflectUtilsTest
{
    public interface Fruit<T>
    {
    }

    public interface Color<T>
    {
    }

    public interface Food<T> extends Fruit<T>
    {
    }

    public abstract static class Apple<T extends Object> implements Fruit<T>, Color<String>
    {
    }

    public abstract static class Cherry<A extends Object, B extends Number> implements Fruit<A>, Color<B>
    {
    }

    public abstract static class Banana implements Fruit<String>, Color<String>
    {
    }

    public static class Washington<Z extends Number, X extends Object> extends Cherry<X, Z>
    {
    }

    public static class Rainier extends Washington<Float, Short>
    {
    }

    public static class Pizza implements Food<Integer>
    {
    }

    public static class Cavendish extends Banana
    {
    }

    public static class GrannySmith extends Apple<Long>
    {
    }

    public static class Pear implements Fruit<String>, Color<Double>
    {
    }

    public static class Kiwi implements Fruit<Character>
    {
    }

    @Test
    public void testFindGeneric_PearFruit()
    {
        assertFindGenericClass(Pear.class, Fruit.class, String.class);
    }

    @Test
    public void testFindGeneric_PizzaFruit()
    {
        assertFindGenericClass(Pizza.class, Fruit.class, Integer.class);
    }

    @Test
    public void testFindGeneric_KiwiFruit()
    {
        assertFindGenericClass(Kiwi.class, Fruit.class, Character.class);
    }

    @Test
    public void testFindGeneric_PearColor()
    {
        assertFindGenericClass(Pear.class, Color.class, Double.class);
    }

    @Test
    public void testFindGeneric_GrannySmithFruit()
    {
        assertFindGenericClass(GrannySmith.class, Fruit.class, Long.class);
    }

    @Test
    public void testFindGeneric_CavendishFruit()
    {
        assertFindGenericClass(Cavendish.class, Fruit.class, String.class);
    }

    @Test
    public void testFindGeneric_RainierFruit()
    {
        assertFindGenericClass(Rainier.class, Fruit.class, Short.class);
    }

    @Test
    public void testFindGeneric_WashingtonFruit()
    {
        // Washington does not have a concrete implementation
        // of the Fruit interface, this should return null
        Class<?> impl = ReflectUtils.findGenericClassFor(Washington.class, Fruit.class);
        assertThat("Washington -> Fruit implementation", impl, nullValue());
    }

    private void assertFindGenericClass(Class<?> baseClass, Class<?> ifaceClass, Class<?> expectedClass)
    {
        Class<?> foundClass = ReflectUtils.findGenericClassFor(baseClass, ifaceClass);
        String msg = String.format("Expecting %s<%s> found on %s", ifaceClass.getName(), expectedClass.getName(), baseClass.getName());
        assertEquals(expectedClass, foundClass, msg);
    }
}
