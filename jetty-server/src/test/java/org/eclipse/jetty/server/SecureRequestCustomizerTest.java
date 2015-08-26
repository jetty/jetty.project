//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SecureRequestCustomizerTest
{
    @Test
    public void testDomain()
    {
        assertTrue(SecureRequestCustomizer.isDomainOrSubDomain("foo.com", "foo.com"));
        assertTrue(SecureRequestCustomizer.isDomainOrSubDomain("www.foo.com", "foo.com"));
        assertFalse(SecureRequestCustomizer.isDomainOrSubDomain("bad_foo.com", "foo.com"));
        assertFalse(SecureRequestCustomizer.isDomainOrSubDomain("foo.com", "bar.com"));
        assertFalse(SecureRequestCustomizer.isDomainOrSubDomain("www.foo.com","bar.com"));
    }
}
