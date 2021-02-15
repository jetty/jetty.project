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

package org.eclipse.jetty.plus.jndi;

import javax.naming.NamingException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for class {@link NamingEntryUtil}.
 *
 * @see NamingEntryUtil
 */
public class NamingEntryUtilTest
{

    @Test
    public void testBindToENCWithEmptyStringAndBindToENCThrowsNamingException()
    {
        assertThrows(NamingException.class, () -> NamingEntryUtil.bindToENC(new Object(), "", ""));
    }

    @Test
    public void testBindToENCWithNullAndNullThrowsNamingException()
    {
        assertThrows(NamingException.class, () -> NamingEntryUtil.bindToENC(null, null, "@=<9"));
    }
}
