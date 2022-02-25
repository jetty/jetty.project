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
import static org.junit.jupiter.api.Assertions.assertNull;

public class HttpStatusCodeTest
{
    @Test
    public void testInvalidGetCode()
    {
        assertNull(HttpStatus.getCode(800), "Invalid code: 800");
        assertNull(HttpStatus.getCode(190), "Invalid code: 190");
    }

    @Test
    public void testImATeapot()
    {
        assertEquals("I'm a Teapot", HttpStatus.getMessage(418));
        assertEquals("Expectation Failed", HttpStatus.getMessage(417));
    }

    public void testHttpMethod()
    {
        assertEquals("GET", HttpMethod.GET.toString());
    }
}
