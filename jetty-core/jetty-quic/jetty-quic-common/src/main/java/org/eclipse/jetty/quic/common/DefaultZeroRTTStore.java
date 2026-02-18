//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.common;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

public class DefaultZeroRTTStore implements ZeroRTTStore
{
    private final List<Entry> entries = new ArrayList<>();

    @Override
    public void put(Entry entry)
    {
        entries.add(entry);
    }

    @Override
    public Entry match(Predicate<Entry> filter)
    {
        Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext())
        {
            Entry entry = iterator.next();
            if (entry.expired())
                continue;
            if (filter.test(entry))
            {
                iterator.remove();
                return entry;
            }
        }
        return null;
    }
}
