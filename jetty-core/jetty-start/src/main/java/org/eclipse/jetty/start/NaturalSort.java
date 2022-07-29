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

package org.eclipse.jetty.start;

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
