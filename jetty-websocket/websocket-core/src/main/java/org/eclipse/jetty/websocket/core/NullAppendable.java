//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

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