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

package org.eclipse.jetty.server.handler.gzip;

import java.io.FilterInputStream;
import java.io.InputStream;

/**
 * A simple pass-through input stream.
 * <p>
 * Used in some test cases where a proper resource open/close is needed for
 * some potentially optional layers of the input stream.
 */
public class PassThruInputStream extends FilterInputStream
{
    public PassThruInputStream(InputStream in)
    {
        super(in);
    }
}
