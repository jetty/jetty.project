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

package org.eclipse.jetty.http3.qpack.internal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.eclipse.jetty.http3.qpack.internal.table.Entry;

public class StreamInfo implements Iterable<StreamInfo.SectionInfo>
{
    private final long _streamId;
    private final Queue<SectionInfo> _sectionInfos = new LinkedList<>();

    public StreamInfo(long streamId)
    {
        _streamId = streamId;
    }

    public long getStreamId()
    {
        return _streamId;
    }

    public void add(SectionInfo sectionInfo)
    {
        _sectionInfos.add(sectionInfo);
    }

    public void remove(SectionInfo sectionInfo)
    {
        _sectionInfos.remove(sectionInfo);
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

    @Override
    public Iterator<SectionInfo> iterator()
    {
        return _sectionInfos.iterator();
    }

    public static class SectionInfo
    {
        private final List<Entry> _entries = new ArrayList<>();
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

        public void reference(Entry entry)
        {
            entry.reference();
            _entries.add(entry);
        }

        public void release()
        {
            for (Entry entry : _entries)
            {
                entry.release();
            }
            _entries.clear();
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
