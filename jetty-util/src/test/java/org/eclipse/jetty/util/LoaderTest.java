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
