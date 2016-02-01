//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.jsr356.utils;

import static org.hamcrest.Matchers.nullValue;

import org.eclipse.jetty.websocket.common.util.ReflectUtils;
import org.junit.Assert;
import org.junit.Test;

public class ReflectUtilsTest
{
    public static interface Fruit<T>
    {
    }

    public static interface Color<T>
    {
    }

    public static interface Food<T> extends Fruit<T>
    {
    }

    public static abstract class Apple<T extends Object> implements Fruit<T>, Color<String>
    {
    }

    public static abstract class Cherry<A extends Object, B extends Number> implements Fruit<A>, Color<B>
    {
    }

    public static abstract class Banana implements Fruit<String>, Color<String>
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
        assertFindGenericClass(Pear.class,Fruit.class,String.class);
    }

    @Test
    public void testFindGeneric_PizzaFruit()
    {
        assertFindGenericClass(Pizza.class,Fruit.class,Integer.class);
    }

    @Test
    public void testFindGeneric_KiwiFruit()
    {
        assertFindGenericClass(Kiwi.class,Fruit.class,Character.class);
    }

    @Test
    public void testFindGeneric_PearColor()
    {
        assertFindGenericClass(Pear.class,Color.class,Double.class);
    }

    @Test
    public void testFindGeneric_GrannySmithFruit()
    {
        assertFindGenericClass(GrannySmith.class,Fruit.class,Long.class);
    }

    @Test
    public void testFindGeneric_CavendishFruit()
    {
        assertFindGenericClass(Cavendish.class,Fruit.class,String.class);
    }

    @Test
    public void testFindGeneric_RainierFruit()
    {
        assertFindGenericClass(Rainier.class,Fruit.class,Short.class);
    }

    @Test
    public void testFindGeneric_WashingtonFruit()
    {
        // Washington does not have a concrete implementation
        // of the Fruit interface, this should return null
        Class<?> impl = ReflectUtils.findGenericClassFor(Washington.class,Fruit.class);
        Assert.assertThat("Washington -> Fruit implementation",impl,nullValue());
    }

    private void assertFindGenericClass(Class<?> baseClass, Class<?> ifaceClass, Class<?> expectedClass)
    {
        Class<?> foundClass = ReflectUtils.findGenericClassFor(baseClass,ifaceClass);
        String msg = String.format("Expecting %s<%s> found on %s",ifaceClass.getName(),expectedClass.getName(),baseClass.getName());
        Assert.assertEquals(msg,expectedClass,foundClass);
    }
}
