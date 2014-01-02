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

package org.eclipse.jetty.spdy.server.http;

import org.eclipse.jetty.server.QueuedHttpInput;
import org.eclipse.jetty.spdy.api.DataInfo;

public class HttpInputOverSPDY extends QueuedHttpInput<DataInfo>
{
    @Override
    protected int remaining(DataInfo item)
    {
        return item.available();
    }

    @Override
    protected int get(DataInfo item, byte[] buffer, int offset, int length)
    {
        return item.readInto(buffer, offset, length);
    }
    
    @Override
    protected void consume(DataInfo item, int length)
    {
        item.consume(length);
    }

    @Override
    protected void onContentConsumed(DataInfo dataInfo)
    {
        dataInfo.consume(dataInfo.length());
    }
}
