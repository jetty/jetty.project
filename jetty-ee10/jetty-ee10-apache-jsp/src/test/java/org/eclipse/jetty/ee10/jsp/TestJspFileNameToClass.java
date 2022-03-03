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

package org.eclipse.jetty.ee10.jsp;

import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestJspFileNameToClass
{

    @Test
    public void testJspFileNameToClassName() throws Exception
    {
        ServletHolder h = new ServletHolder();
        h.setName("test");

        assertEquals(null, h.getClassNameForJsp(null));

        assertEquals(null, h.getClassNameForJsp(""));

        assertEquals(null, h.getClassNameForJsp("/blah/"));

        assertEquals(null, h.getClassNameForJsp("//blah///"));

        assertEquals(null, h.getClassNameForJsp("/a/b/c/blah/"));

        assertEquals("org.apache.jsp.a.b.c.blah", h.getClassNameForJsp("/a/b/c/blah"));

        assertEquals("org.apache.jsp.blah_jsp", h.getClassNameForJsp("/blah.jsp"));

        assertEquals("org.apache.jsp.blah_jsp", h.getClassNameForJsp("//blah.jsp"));

        assertEquals("org.apache.jsp.blah_jsp", h.getClassNameForJsp("blah.jsp"));

        assertEquals("org.apache.jsp.a.b.c.blah_jsp", h.getClassNameForJsp("/a/b/c/blah.jsp"));

        assertEquals("org.apache.jsp.a.b.c.blah_jsp", h.getClassNameForJsp("a/b/c/blah.jsp"));
    }
}
