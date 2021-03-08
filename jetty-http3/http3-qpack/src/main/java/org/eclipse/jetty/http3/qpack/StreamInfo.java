//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.qpack;

import java.util.LinkedList;
import java.util.Queue;

class StreamInfo
{
    private final int _streamId;
    private final Queue<SectionInfo> _sectionInfos = new LinkedList<>();

    public StreamInfo(int streamId)
    {
        _streamId = streamId;
    }

    public int getStreamId()
    {
        return _streamId;
    }

    public void add(SectionInfo sectionInfo)
    {
        _sectionInfos.add(sectionInfo);
    }

    public SectionInfo getCurrentSectionInfo()
    {
        return _sectionInfos.peek();
    }

    public SectionInfo acknowledge()
    {
        return _sectionInfos.poll();
    }

    public boolean isEmpty()
    {
        return _sectionInfos.isEmpty();
    }

    public boolean isBlocked()
    {
        for (SectionInfo info : _sectionInfos)
        {
            if (info.isBlocking())
                return true;
        }

        return false;
    }

    public static class SectionInfo
    {
        private int _requiredInsertCount;
        private boolean _block = false;

        public void block()
        {
            _block = true;
        }

        public boolean isBlocking()
        {
            return _block;
        }

        public void setRequiredInsertCount(int requiredInsertCount)
        {
            _requiredInsertCount = requiredInsertCount;
        }

        public int getRequiredInsertCount()
        {
            return _requiredInsertCount;
        }
    }
}
