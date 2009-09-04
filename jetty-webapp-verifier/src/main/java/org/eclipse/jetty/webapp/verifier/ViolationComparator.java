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
package org.eclipse.jetty.webapp.verifier;

import java.text.CollationKey;
import java.text.Collator;
import java.util.Comparator;

public class ViolationComparator implements Comparator<Violation>
{
    private static ViolationComparator INSTANCE = new ViolationComparator();

    public static ViolationComparator getInstance()
    {
        return INSTANCE;
    }

    private Collator collator = Collator.getInstance();

    public int compare(Violation o1, Violation o2)
    {
        CollationKey pathKey1 = collator.getCollationKey(o1.getPath());
        CollationKey pathKey2 = collator.getCollationKey(o2.getPath());

        int diff = pathKey1.compareTo(pathKey2);
        if (diff != 0)
        {
            // different paths.
            return diff;
        }

        CollationKey detailKey1 = collator.getCollationKey(o1.getDetail());
        CollationKey detailKey2 = collator.getCollationKey(o2.getDetail());
        return detailKey1.compareTo(detailKey2);
    }
}
