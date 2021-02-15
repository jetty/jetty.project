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

package org.eclipse.jetty.websocket.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for class {@link UpgradeResponseAdapter}.
 *
 * @see UpgradeResponseAdapter
 */
public class UpgradeResponseAdapterTest
{

    @Test
    public void testAddHeader()
    {
        UpgradeResponseAdapter upgradeResponseAdapter = new UpgradeResponseAdapter();

        assertNull(upgradeResponseAdapter.getHeader(""));

        upgradeResponseAdapter.addHeader("", "");

        assertEquals(0, upgradeResponseAdapter.getStatusCode());
        assertFalse(upgradeResponseAdapter.isSuccess());
        assertNull(upgradeResponseAdapter.getStatusReason());
        assertEquals(0, upgradeResponseAdapter.getStatusCode());
        assertFalse(upgradeResponseAdapter.isSuccess());
        assertNull(upgradeResponseAdapter.getStatusReason());
        assertEquals("", upgradeResponseAdapter.getHeader(""));

        upgradeResponseAdapter.addHeader("a", "b");

        assertEquals(0, upgradeResponseAdapter.getStatusCode());
        assertFalse(upgradeResponseAdapter.isSuccess());
        assertNull(upgradeResponseAdapter.getStatusReason());
        assertEquals(0, upgradeResponseAdapter.getStatusCode());
        assertFalse(upgradeResponseAdapter.isSuccess());
        assertNull(upgradeResponseAdapter.getStatusReason());
        assertEquals("", upgradeResponseAdapter.getHeader(""));
        assertEquals("b", upgradeResponseAdapter.getHeader("a"));
    }
}
