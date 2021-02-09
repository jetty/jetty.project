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

package org.eclipse.jetty.jsp;

import org.eclipse.jetty.servlet.ServletHolder;
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
