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

package org.eclipse.jetty.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class HttpStatusCodeTest
{
    @Test
    public void testInvalidGetCode()
    {
        assertNull("Invalid code: 800", HttpStatus.getCode(800));
        assertNull("Invalid code: 190", HttpStatus.getCode(190));
    }

    public void testHttpMethod()
    {
        assertEquals("GET",HttpMethod.GET.toString());
    }
}
