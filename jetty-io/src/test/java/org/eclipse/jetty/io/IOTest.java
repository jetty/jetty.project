// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.io;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.eclipse.jetty.util.IO;
import org.junit.Test;

/**
 *
 */
public class IOTest
{
    @Test
    public void testIO() throws InterruptedException
    {
        // Only a little test
        ByteArrayInputStream in = new ByteArrayInputStream
            ("The quick brown fox jumped over the lazy dog".getBytes());
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        IO.copyThread(in,out);
        Thread.sleep(1500);
        System.err.println(out);

        assertEquals( "copyThread",
                      out.toString(),
                      "The quick brown fox jumped over the lazy dog");
    }
}
