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
