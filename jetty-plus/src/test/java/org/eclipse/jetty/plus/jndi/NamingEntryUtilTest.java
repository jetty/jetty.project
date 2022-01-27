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
