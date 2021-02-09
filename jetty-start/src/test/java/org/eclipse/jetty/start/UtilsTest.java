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

package org.eclipse.jetty.start;

import java.util.Collection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for class {@link Utils}.
 *
 * @see Utils
 */
public class UtilsTest
{
    @Test
    public void testIsNotBlankReturningTrue()
    {
        assertTrue(Utils.isNotBlank(" @t;3/|O)t"));
    }

    @Test
    public void testIsNotBlankReturningFalse()
    {
        assertFalse(Utils.isNotBlank(null));
    }

    @Test
    public void testIsBlank()
    {
        assertFalse(Utils.isBlank(" i3o0e!#4u%QW"));
    }

    @Test
    public void testIsBlankUsingNull()
    {
        assertTrue(Utils.isBlank(null));
    }

    @Test
    public void testJoin()
    {
        assertEquals("", Utils.join((Collection<?>)null, "V9ewe2K"));
    }

    @Test
    public void testJoinTaking4ArgumentsAndReturningEmptyString()
    {
        assertEquals("", Utils.join((Object[])null, (-3563), 1051, ""));
    }

    @Test
    public void testJoinUsingObjectArrayOne()
    {
        assertEquals("", Utils.join((Object[])null, ""));
    }

    @Test
    public void testJoinUsingObjectArrayTwo()
    {
        Object[] objectArray = new Object[8];
        String joinedString = Utils.join(objectArray, "%.g[{2G<1");

        assertEquals(8, objectArray.length);
        assertEquals("null%.g[{2G<1null%.g[{2G<1null%.g[{2G<1null%.g[{2G<1null%.g[{2G<1null%.g[{2G<1null%.g[{2G<1null", joinedString);
    }
}
