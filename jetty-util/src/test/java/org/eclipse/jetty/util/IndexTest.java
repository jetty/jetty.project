//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

public class IndexTest
{
    @Test
    public void belowMaxCapacityTest()
    {
        int size = 10_450;

        Index.Builder<Integer> builder = new Index.Builder<>();
        builder.caseSensitive(true);
        for (int i = 0; i < size; i++)
        {
            builder.with("/test/group" + i, i);
        }
        Index<Integer> index = builder.build();

        for (int i = 0; i < size; i++)
        {
            Integer integer = index.get("/test/group" + i);
            if (integer == null)
                fail("missing entry for '/test/group" + i + "'");
            else if (integer != i)
                fail("incorrect value for '/test/group" + i + "' (" + integer + ")");
        }
    }

    @Test
    public void overMaxCapacityTest()
    {
        int size = 11_000;

        Index.Builder<Integer> builder = new Index.Builder<>();
        builder.caseSensitive(true);
        for (int i = 0; i < size; i++)
        {
            builder.with("/test/group" + i, i);
        }

        assertThrows(IllegalArgumentException.class, builder::build);
    }
}
