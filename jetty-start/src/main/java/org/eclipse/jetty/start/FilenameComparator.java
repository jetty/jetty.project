// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.start;

import java.io.File;
import java.text.CollationKey;
import java.text.Collator;
import java.util.Comparator;

/**
 * Smart comparator for filenames, with natural language sorting, and files sorted before sub directories.
 */
public class FilenameComparator implements Comparator<File>
{
    public static final FilenameComparator INSTANCE = new FilenameComparator();
    private Collator collator = Collator.getInstance();

    public int compare(File o1, File o2)
    {
        if (o1.isFile())
        {
            if (o2.isFile())
            {
                CollationKey key1 = toKey(o1);
                CollationKey key2 = toKey(o2);
                return key1.compareTo(key2);
            }
            else
            {
                // Push o2 directories below o1 files
                return -1;
            }
        }
        else
        {
            if (o2.isDirectory())
            {
                CollationKey key1 = toKey(o1);
                CollationKey key2 = toKey(o2);
                return key1.compareTo(key2);
            }
            else
            {
                // Push o2 files above o1 directories
                return 1;
            }
        }
    }

    private CollationKey toKey(File f)
    {
        return collator.getCollationKey(f.getAbsolutePath());
    }
}