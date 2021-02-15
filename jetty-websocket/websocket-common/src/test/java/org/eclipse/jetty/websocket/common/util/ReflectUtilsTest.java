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

package org.eclipse.jetty.websocket.common.util;

import org.eclipse.jetty.io.AbstractConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for class {@link ReflectUtils}.
 *
 * @see ReflectUtils
 */
public class ReflectUtilsTest
{

    @Test
    public void testTrimClassName()
    {
        assertEquals("+~Z", ReflectUtils.trimClassName("NbcbeUUm$+~Z"));
    }

    @Test
    public void testToShortNameWithNull()
    {
        assertEquals("<null>", ReflectUtils.toShortName(null));
    }

    @Test
    public void testToShortNameWithIntegerClass()
    {
        assertEquals("Integer", ReflectUtils.toShortName(Integer.class));
    }

    @Test
    public void testFindGenericClassForObjectReturningNull()
    {
        assertNull(ReflectUtils.findGenericClassFor(Object.class, Object.class));
    }

    @Test
    public void testFindGenericClassForWithNullParameters()
    {
        assertNull(ReflectUtils.findGenericClassFor(null, null));
    }

    @Test
    public void testIsDefaultConstructableWithIntegerClass()
    {
        assertFalse(ReflectUtils.isDefaultConstructable(Integer.class));
    }

    @Test
    public void testIsDefaultConstructableWithAbstractClass()
    {
        assertFalse(ReflectUtils.isDefaultConstructable(AbstractConnection.class));
    }

    @Test
    public void testIsDefaultConstructableWithObjectClass()
    {
        assertTrue(ReflectUtils.isDefaultConstructable(Object.class));
    }

    @Test
    public void testFindGenericClassForStringClassTwice()
    {
        assertNull(ReflectUtils.findGenericClassFor(String.class, String.class));
    }
}
