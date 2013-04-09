//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server.pathmap;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for ServerEndpoint Path Param / URI Template Path Specs
 */
public class PathParamSpecTest
{
    @Test
    public void testDefaultPathSpec()
    {
        PathParamSpec spec = new PathParamSpec("/");
        assertEquals("Spec.pathSpec","/",spec.getPathSpec());
        assertEquals("Spec.pattern","^/$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",1,spec.getPathDepth());

        assertEquals("Spec.variableCount",0,spec.getVariableCount());
        assertEquals("Spec.variable.length",0,spec.getVariables().length);
    }

    @Test
    public void testExactOnePathSpec()
    {
        PathParamSpec spec = new PathParamSpec("/a");
        assertEquals("Spec.pathSpec","/a",spec.getPathSpec());
        assertEquals("Spec.pattern","^/a$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",1,spec.getPathDepth());

        assertEquals("Spec.variableCount",0,spec.getVariableCount());
        assertEquals("Spec.variable.length",0,spec.getVariables().length);
    }

    @Test
    public void testExactTwoPathSpec()
    {
        PathParamSpec spec = new PathParamSpec("/a/b");
        assertEquals("Spec.pathSpec","/a/b",spec.getPathSpec());
        assertEquals("Spec.pattern","^/a/b$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",2,spec.getPathDepth());

        assertEquals("Spec.variableCount",0,spec.getVariableCount());
        assertEquals("Spec.variable.length",0,spec.getVariables().length);
    }

    @Test
    public void testOneVarPathSpec()
    {
        PathParamSpec spec = new PathParamSpec("/a/{foo}");
        assertEquals("Spec.pathSpec","/a/{foo}",spec.getPathSpec());
        assertEquals("Spec.pattern","^/a/([^/]+)$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",2,spec.getPathDepth());

        assertEquals("Spec.variableCount",1,spec.getVariableCount());
        assertEquals("Spec.variable.length",1,spec.getVariables().length);
        assertEquals("Spec.variable[0]","foo",spec.getVariables()[0]);
    }
}
