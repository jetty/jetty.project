//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.eclipse.jetty.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.Set;

import javax.servlet.ServletRegistration;

import org.junit.Test;

/**
 * @version $Rev$ $Date$
 */
public class HolderTest
{

    @Test
    public void testInitParams() throws Exception
    {
        ServletHolder holder = new ServletHolder(Source.JAVAX_API);
        ServletRegistration reg = holder.getRegistration();
        try
        {
            reg.setInitParameter(null, "foo");
            fail("null name accepted");
        }
        catch (IllegalArgumentException e)
        {
        }
        try
        {
            reg.setInitParameter("foo", null);
            fail("null value accepted");
        }
        catch (IllegalArgumentException e)
        {
        }
        reg.setInitParameter("foo", "bar");
        assertFalse(reg.setInitParameter("foo", "foo"));

        Set<String> clash = reg.setInitParameters(Collections.singletonMap("foo", "bax"));
        assertTrue("should be one clash", clash != null && clash.size() == 1);

        try
        {
            reg.setInitParameters(Collections.singletonMap((String) null, "bax"));
            fail("null name in map accepted");
        }
        catch (IllegalArgumentException e)
        {
        }
        try
        {
            reg.setInitParameters(Collections.singletonMap("foo", (String) null));
            fail("null value in map accepted");
        }
        catch (IllegalArgumentException e)
        {
        }

        Set<String> clash2 = reg.setInitParameters(Collections.singletonMap("FOO", "bax"));
        assertTrue("should be no clash", clash2.isEmpty());
        assertEquals("setInitParameters should not replace existing non-clashing init parameters", 2, reg.getInitParameters().size());

    }
}
