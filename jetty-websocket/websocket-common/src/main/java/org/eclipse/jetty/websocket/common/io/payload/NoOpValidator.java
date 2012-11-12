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

package org.eclipse.jetty.websocket.common.io.payload;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.extensions.Frame;

/**
 * payload validator does no validation.
 */
public class NoOpValidator implements PayloadProcessor
{
    public static final NoOpValidator INSTANCE = new NoOpValidator();

    @Override
    public void process(ByteBuffer payload)
    {
        /* all payloads are valid in this case */
    }

    @Override
    public void reset(Frame frame)
    {
        /* do nothing */
    }
}
