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

package org.eclipse.jetty.websocket.jakarta.common.util;

import org.eclipse.jetty.websocket.core.internal.util.ReflectUtils;
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
    public void testFindGenericPearFruit()
    {
        assertFindGenericClass(Pear.class, Fruit.class, String.class);
    }

    @Test
    public void testFindGenericPizzaFruit()
    {
        assertFindGenericClass(Pizza.class, Fruit.class, Integer.class);
    }

    @Test
    public void testFindGenericKiwiFruit()
    {
        assertFindGenericClass(Kiwi.class, Fruit.class, Character.class);
    }

    @Test
    public void testFindGenericPearColor()
    {
        assertFindGenericClass(Pear.class, Color.class, Double.class);
    }

    @Test
    public void testFindGenericGrannySmithFruit()
    {
        assertFindGenericClass(GrannySmith.class, Fruit.class, Long.class);
    }

    @Test
    public void testFindGenericCavendishFruit()
    {
        assertFindGenericClass(Cavendish.class, Fruit.class, String.class);
    }

    @Test
    public void testFindGenericRainierFruit()
    {
        assertFindGenericClass(Rainier.class, Fruit.class, Short.class);
    }

    @Test
    public void testFindGenericWashingtonFruit()
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
