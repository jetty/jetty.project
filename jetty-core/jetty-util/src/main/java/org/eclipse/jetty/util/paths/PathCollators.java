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

package org.eclipse.jetty.util.paths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

public class PathCollators
{
    private static Comparator<? super Path> BY_URI_ASCENDING = Comparator.comparing(Path::toUri);

    private static Comparator<? super Path> BY_NAME_ASCENDING =
        new Comparator<>()
        {
            private final Collator collator = Collator.getInstance(Locale.ENGLISH);

            @Override
            public int compare(Path o1, Path o2)
            {
                return collator.compare(o1.getFileName().toString(), o2.getFileName().toString());
            }
        };

    private static Comparator<? super Path> BY_NAME_DESCENDING =
        Collections.reverseOrder(BY_NAME_ASCENDING);

    private static Comparator<? super Path> BY_LAST_MODIFIED_ASCENDING =
        Comparator.comparing(PathCollators::getLastModifiedTime);

    private static Comparator<? super Path> BY_LAST_MODIFIED_DESCENDING =
        Collections.reverseOrder(BY_LAST_MODIFIED_ASCENDING);

    private static Comparator<? super Path> BY_SIZE_ASCENDING =
        Comparator.comparingLong(PathCollators::size);

    private static Comparator<? super Path> BY_SIZE_DESCENDING =
        Collections.reverseOrder(BY_SIZE_ASCENDING);

    public static Comparator<? super Path> byLastModified(boolean sortOrderAscending)
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

    public static Comparator<? super Path> byName(boolean sortOrderAscending)
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

    public static Comparator<? super Path> bySize(boolean sortOrderAscending)
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

    private static FileTime getLastModifiedTime(Path path)
    {
        try
        {
            return Files.getLastModifiedTime(path);
        }
        catch (IOException e)
        {
            return FileTime.fromMillis(0L);
        }
    }

    private static long size(Path path)
    {
        try
        {
            return Files.size(path);
        }
        catch (IOException e)
        {
            return 0L;
        }
    }
}
