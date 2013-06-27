//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.*;

import java.io.BufferedReader;
import java.io.FilterReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.websocket.DeploymentException;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class DeploymentTypeUtils_IsAssignableTest
{
    private static class Case
    {
        Class<?> type;
        Class<?> targetType;
        boolean expectedResult;

        public Case(Class<?> type, Class<?> targetType)
        {
            this.type = type;
            this.targetType = targetType;
        }

        public Case[] expecting(boolean expect)
        {
            this.expectedResult = expect;
            return new Case[]
            { this };
        }
    }

    @Parameters
    public static Collection<Case[]> data()
    {
        List<Case[]> data = new ArrayList<>();

        // Null to anything is false
        data.add(new Case(null,Object.class).expecting(false));
        data.add(new Case(null,Integer.class).expecting(false));
        data.add(new Case(null,String.class).expecting(false));
        data.add(new Case(null,Integer.TYPE).expecting(false));
        data.add(new Case(null,Character.TYPE).expecting(false));

        // Anything to null is false
        data.add(new Case(Object.class,null).expecting(false));
        data.add(new Case(Integer.class,null).expecting(false));
        data.add(new Case(String.class,null).expecting(false));
        data.add(new Case(Integer.TYPE,null).expecting(false));
        data.add(new Case(Character.TYPE,null).expecting(false));

        // String to Object or String is ok
        data.add(new Case(String.class,Object.class).expecting(true));
        data.add(new Case(String.class,String.class).expecting(true));
        // ... but not the reverse
        data.add(new Case(Object.class,String.class).expecting(false));

        // Primitive to same Primitive
        data.add(new Case(Integer.TYPE,Integer.TYPE).expecting(true));
        data.add(new Case(Float.TYPE,Float.TYPE).expecting(true));
        data.add(new Case(Byte.TYPE,Byte.TYPE).expecting(true));

        // Primitive to Class of same Type (autoboxing)
        data.add(new Case(Integer.TYPE,Integer.class).expecting(true));
        data.add(new Case(Float.TYPE,Float.class).expecting(true));
        data.add(new Case(Byte.TYPE,Byte.class).expecting(true));

        // Class Primitive to Primitive type (autoboxing)
        data.add(new Case(Integer.class,Integer.TYPE).expecting(true));
        data.add(new Case(Float.class,Float.TYPE).expecting(true));
        data.add(new Case(Byte.class,Byte.TYPE).expecting(true));

        // byte array
        data.add(new Case(byte[].class,Object.class).expecting(true));
        data.add(new Case(byte[].class,byte[].class).expecting(true));

        // ByteBuffer
        data.add(new Case(ByteBuffer.class,ByteBuffer.class).expecting(true));
        data.add(new Case(MappedByteBuffer.class,ByteBuffer.class).expecting(true));

        // Reader
        data.add(new Case(Reader.class,Reader.class).expecting(true));
        data.add(new Case(BufferedReader.class,Reader.class).expecting(true));
        data.add(new Case(FilterReader.class,Reader.class).expecting(true));

        return data;
    }
    private Case testcase;

    public DeploymentTypeUtils_IsAssignableTest(Case testcase)
    {
        this.testcase = testcase;
    }

    @Test
    public void testIsAssignable() throws DeploymentException
    {
        boolean actual = DeploymentTypeUtils.isAssignableClass(testcase.type,testcase.targetType);
        Assert.assertThat("isAssignable(" + testcase.type + ", " + testcase.targetType + ")",actual,is(testcase.expectedResult));
    }
}
