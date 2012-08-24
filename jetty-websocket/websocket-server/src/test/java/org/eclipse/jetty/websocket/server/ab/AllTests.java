//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server.ab;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
// @formatter:off
@Suite.SuiteClasses( { 
    TestABCase1.class, 
    TestABCase2.class, 
    TestABCase3.class, 
    TestABCase4.class, 
    TestABCase5.class, 
    TestABCase6.class, 
    TestABCase6_GoodUTF.class,
    TestABCase6_BadUTF.class,
    TestABCase7.class,
    TestABCase7_GoodStatusCodes.class,
    TestABCase7_BadStatusCodes.class,
    TestABCase9.class
})
// @formatter:on
public class AllTests
{
    /* let junit do the rest */
}
