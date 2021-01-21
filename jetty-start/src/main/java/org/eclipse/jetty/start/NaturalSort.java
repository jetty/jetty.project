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

package org.eclipse.jetty.start;

import java.io.File;
import java.nio.file.Path;
import java.text.CollationKey;
import java.text.Collator;
import java.util.Comparator;

/**
 * Natural Language Sorting
 */
public class NaturalSort
{
    public static class Paths implements Comparator<Path>
    {
        private final Collator collator = Collator.getInstance();

        @Override
        public int compare(Path o1, Path o2)
        {
            CollationKey key1 = collator.getCollationKey(o1.toString());
            CollationKey key2 = collator.getCollationKey(o2.toString());
            return key1.compareTo(key2);
        }
    }

    public static class Files implements Comparator<File>
    {
        private final Collator collator = Collator.getInstance();

        @Override
        public int compare(File o1, File o2)
        {
            CollationKey key1 = collator.getCollationKey(o1.getAbsolutePath());
            CollationKey key2 = collator.getCollationKey(o2.getAbsolutePath());
            return key1.compareTo(key2);
        }
    }

    public static class Strings implements Comparator<String>
    {
        private final Collator collator = Collator.getInstance();

        @Override
        public int compare(String o1, String o2)
        {
            CollationKey key1 = collator.getCollationKey(o1);
            CollationKey key2 = collator.getCollationKey(o2);
            return key1.compareTo(key2);
        }
    }
}
