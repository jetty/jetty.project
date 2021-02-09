//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util.resource;

import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

public class ResourceCollators
{
    private static Comparator<? super Resource> BY_NAME_ASCENDING =
        new Comparator<Resource>()
        {
            private final Collator collator = Collator.getInstance(Locale.ENGLISH);

            @Override
            public int compare(Resource o1, Resource o2)
            {
                return collator.compare(o1.getName(), o2.getName());
            }
        };

    private static Comparator<? super Resource> BY_NAME_DESCENDING =
        Collections.reverseOrder(BY_NAME_ASCENDING);

    private static Comparator<? super Resource> BY_LAST_MODIFIED_ASCENDING =
        new Comparator<Resource>()
        {
            @Override
            public int compare(Resource o1, Resource o2)
            {
                return Long.compare(o1.lastModified(), o2.lastModified());
            }
        };

    private static Comparator<? super Resource> BY_LAST_MODIFIED_DESCENDING =
        Collections.reverseOrder(BY_LAST_MODIFIED_ASCENDING);

    private static Comparator<? super Resource> BY_SIZE_ASCENDING =
        new Comparator<Resource>()
        {
            @Override
            public int compare(Resource o1, Resource o2)
            {
                return Long.compare(o1.length(), o2.length());
            }
        };

    private static Comparator<? super Resource> BY_SIZE_DESCENDING =
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
