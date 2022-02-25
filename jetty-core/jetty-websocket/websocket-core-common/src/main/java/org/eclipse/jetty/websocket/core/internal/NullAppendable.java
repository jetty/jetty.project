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

package org.eclipse.jetty.websocket.core.internal;

import org.eclipse.jetty.util.Utf8Appendable;

public class NullAppendable extends Utf8Appendable
{
    public NullAppendable()
    {
        super(new Appendable()
        {
            @Override
            public Appendable append(CharSequence csq)
            {
                return null;
            }

            @Override
            public Appendable append(CharSequence csq, int start, int end)
            {
                return null;
            }

            @Override
            public Appendable append(char c)
            {
                return null;
            }
        });
    }

    @Override
    public int length()
    {
        return 0;
    }

    @Override
    public String getPartialString()
    {
        return null;
    }
}
