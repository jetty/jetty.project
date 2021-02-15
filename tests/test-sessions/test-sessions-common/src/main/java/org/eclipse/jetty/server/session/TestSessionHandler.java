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

package org.eclipse.jetty.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TestSessionHandler
 *
 * For testing convenience, allows access to some protected fields in SessionHandler
 */
public class TestSessionHandler extends SessionHandler
{

    /**
     * @param size the size of the expiry candidates to check
     */
    public void assertCandidatesForExpiry(int size)
    {
        assertEquals(size, _candidateSessionIdsForExpiry.size());
    }
}
