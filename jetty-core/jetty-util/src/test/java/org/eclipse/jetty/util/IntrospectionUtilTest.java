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
