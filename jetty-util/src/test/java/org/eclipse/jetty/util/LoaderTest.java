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

import java.util.Locale;
import java.util.MissingResourceException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for class {@link Loader}.
 *
 * @see Loader
 */
public class LoaderTest
{

    @Test
    public void testGetResourceBundleThrowsMissingResourceException()
    {
        assertThrows(MissingResourceException.class, () -> Loader.getResourceBundle("nothing", true, Locale.ITALIAN));
    }

    @Test
    public void testLoadClassThrowsClassNotFoundException()
    {
        assertThrows(ClassNotFoundException.class, () -> Loader.loadClass(Object.class, "String"));
    }

    @Test
    public void testLoadClassSucceeds() throws ClassNotFoundException
    {
        assertEquals(LazyList.class, Loader.loadClass(Object.class, "org.eclipse.jetty.util.LazyList"));
    }
}
