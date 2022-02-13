//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.frames;

import org.eclipse.jetty.http.MetaData;

public class HeadersFrame extends Frame
{
    private final MetaData metaData;
    private final boolean last;

    public HeadersFrame(MetaData metaData, boolean last)
    {
        super(FrameType.HEADERS);
        this.metaData = metaData;
        this.last = last;
    }

    public MetaData getMetaData()
    {
        return metaData;
    }

    public boolean isLast()
    {
        return last;
    }

    @Override
    public String toString()
    {
        return String.format("%s[last=%b,{%s}]", super.toString(), isLast(), getMetaData());
    }
}
