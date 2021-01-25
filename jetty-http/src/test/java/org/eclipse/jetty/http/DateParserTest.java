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

package org.eclipse.jetty.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for class {@link DateParser}.
 *
 * @see DateParser
 */
public class DateParserTest
{

    @Test
    public void testParseDateWithNonDateString()
    {
        assertEquals((-1L), DateParser.parseDate("3%~"));
    }

    @Test
    public void testParseDateWithNonDateStringEndingWithGMT()
    {
        assertEquals((-1L), DateParser.parseDate("3%~ GMT"));
    }
}
