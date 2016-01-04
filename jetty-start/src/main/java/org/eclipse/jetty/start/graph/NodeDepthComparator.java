//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.start.graph;

import java.text.CollationKey;
import java.text.Collator;
import java.util.Comparator;

public class NodeDepthComparator implements Comparator<Node<?>>
{
    private Collator collator = Collator.getInstance();

    @Override
    public int compare(Node<?> o1, Node<?> o2)
    {
        // order by depth first.
        int diff = o1.getDepth() - o2.getDepth();
        if (diff != 0)
        {
            return diff;
        }
        // then by name (not really needed, but makes for predictable test cases)
        CollationKey k1 = collator.getCollationKey(o1.getName());
        CollationKey k2 = collator.getCollationKey(o2.getName());
        return k1.compareTo(k2);
    }
}