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

package org.eclipse.jetty.spdy.http;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.util.BufferUtil;

public class HttpInputOverSPDY extends HttpInput<DataInfo>
{
    private static final DataInfo END_OF_CONTENT = new ByteBufferDataInfo(BufferUtil.EMPTY_BUFFER, true);

    private final Queue<DataInfo> dataInfos = new ConcurrentLinkedQueue<>();

    public void offer(DataInfo dataInfo, boolean lastContent)
    {
        dataInfos.offer(dataInfo);
//        if (lastContent)
//            dataInfos.offer(END_OF_CONTENT); // TODO: necessary ?
    }

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
    protected void onContentConsumed(DataInfo dataInfo)
    {
        boolean removed = dataInfos.remove(dataInfo);
        if (removed)
            dataInfo.consume(dataInfo.length());
    }
}
