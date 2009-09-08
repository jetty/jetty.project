// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.eclipse.jetty.logging.impl;

import junit.framework.TestCase;

public class SeverityTest extends TestCase
{
    public void testIsEnabled()
    {
        assertTrue("DEBUG.isEnabled(INFO)",Severity.DEBUG.isEnabled(Severity.INFO));
        assertTrue("DEBUG.isEnabled(DEBUG)",Severity.DEBUG.isEnabled(Severity.DEBUG));
        assertFalse("DEBUG.isEnabled(TRACE)",Severity.DEBUG.isEnabled(Severity.TRACE));
    }
}
