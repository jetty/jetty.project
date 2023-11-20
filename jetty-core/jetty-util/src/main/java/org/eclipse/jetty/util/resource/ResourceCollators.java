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

package org.eclipse.jetty.util.resource;

import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

public class ResourceCollators
{
    private static final Comparator<? super Resource> BY_NAME_ASCENDING =
        new Comparator<>()
        {
            private final Collator collator = Collator.getInstance(Locale.ENGLISH);

            @Override
            public int compare(Resource o1, Resource o2)
            {
                return collator.compare(Objects.requireNonNullElse(o1.getName(), ""), Objects.requireNonNullElse(o2.getName(), ""));
            }
        };

    private static final Comparator<? super Resource> BY_FILENAME_ASCENDING =
        new Comparator<>()
        {
            private final Collator collator = Collator.getInstance(Locale.ENGLISH);

            @Override
            public int compare(Resource o1, Resource o2)
            {
                return collator.compare(o1.getFileName(), o2.getFileName());
            }
        };

    private static final Comparator<? super Resource> BY_NAME_DESCENDING =
        Collections.reverseOrder(BY_NAME_ASCENDING);

    private static final Comparator<? super Resource> BY_FILENAME_DESCENDING =
        Collections.reverseOrder(BY_FILENAME_ASCENDING);

    private static final Comparator<? super Resource> BY_LAST_MODIFIED_ASCENDING =
        Comparator.comparing(Resource::lastModified);

    private static final Comparator<? super Resource> BY_LAST_MODIFIED_DESCENDING =
        Collections.reverseOrder(BY_LAST_MODIFIED_ASCENDING);

    private static final Comparator<? super Resource> BY_SIZE_ASCENDING =
        Comparator.comparingLong(Resource::length);

    private static final Comparator<? super Resource> BY_SIZE_DESCENDING =
        Collections.reverseOrder(BY_SIZE_ASCENDING);

    public static Comparator<? super Resource> byLastModified(boolean sortOrderAscending)
    {
        if (sortOrderAscending)
        {
            return BY_LAST_MODIFIED_ASCENDING;
        }
        else
        {
            return BY_LAST_MODIFIED_DESCENDING;
        }
    }

    public static Comparator<? super Resource> byName(boolean sortOrderAscending)
    {
        if (sortOrderAscending)
        {
            return BY_NAME_ASCENDING;
        }
        else
        {
            return BY_NAME_DESCENDING;
        }
    }

    public static Comparator<? super Resource> byFileName(boolean sortOrderAscending)
    {
        if (sortOrderAscending)
        {
            return BY_FILENAME_ASCENDING;
        }
        else
        {
            return BY_FILENAME_DESCENDING;
        }
    }

    public static Comparator<? super Resource> bySize(boolean sortOrderAscending)
    {
        if (sortOrderAscending)
        {
            return BY_SIZE_ASCENDING;
        }
        else
        {
            return BY_SIZE_DESCENDING;
        }
    }
}
