//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.api;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * <p>Specialized {@link DataInfo} for {@link String} content.</p>
 */
public class StringDataInfo extends BytesDataInfo
{
    public StringDataInfo(String string, boolean close)
    {
        super(string.getBytes(StandardCharsets.UTF_8), close);
    }

    public StringDataInfo(long timeout, TimeUnit unit, String string, boolean close)
    {
        super(timeout, unit, string.getBytes(StandardCharsets.UTF_8), close);
    }
}
