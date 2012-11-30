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

package org.eclipse.jetty.websocket.common.extensions;

import org.eclipse.jetty.websocket.common.extensions.compress.DeflateCompressionMethodTest;
import org.eclipse.jetty.websocket.common.extensions.compress.MessageCompressionExtensionTest;
import org.eclipse.jetty.websocket.common.extensions.compress.FrameCompressionExtensionTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
        { ExtensionStackTest.class, DeflateCompressionMethodTest.class, MessageCompressionExtensionTest.class, FragmentExtensionTest.class,
            IdentityExtensionTest.class, FrameCompressionExtensionTest.class })
public class AllTests
{
    /* nothing to do here, its all done in the annotations */
}
