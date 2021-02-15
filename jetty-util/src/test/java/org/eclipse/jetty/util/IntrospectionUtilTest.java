//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util;

import java.lang.reflect.Array;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for class {@link IntrospectionUtil}.
 *
 * @see IntrospectionUtil
 */
public class IntrospectionUtilTest
{

    @Test
    public void testIsTypeCompatibleWithTwoTimesString()
    {
        assertTrue(IntrospectionUtil.isTypeCompatible(String.class, String.class, true));
    }

    @Test
    public void testIsSameSignatureWithNull()
    {
        assertFalse(IntrospectionUtil.isSameSignature(null, null));
    }

    @Test
    public void testFindMethodWithEmptyString()
    {
        assertThrows(NoSuchMethodException.class,
            () -> IntrospectionUtil.findMethod(Integer.class, "", null, false, false));
    }

    @Test
    public void testFindMethodWithNullMethodParameter()
    {
        assertThrows(NoSuchMethodException.class,
            () -> IntrospectionUtil.findMethod(String.class, null, (Class<Integer>[])Array.newInstance(Class.class, 3), true, true));
    }

    @Test
    public void testFindMethodWithNullClassParameter() throws NoSuchMethodException
    {
        assertThrows(NoSuchMethodException.class,
            () -> IntrospectionUtil.findMethod(null, "subSequence", (Class<Object>[])Array.newInstance(Class.class, 9), false, false));
    }

    @Test
    public void testIsJavaBeanCompliantSetterWithNull()
    {
        assertFalse(IntrospectionUtil.isJavaBeanCompliantSetter(null));
    }
}
