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

package org.eclipse.jetty.tests.webapp.websocket;

public class StringSequence
    implements CharSequence
{
    public String stringBuffer;

    public StringSequence(String hold)
    {
        stringBuffer = hold;
    }

    @Override
    public int length()
    {
        return stringBuffer.length();
    }

    @Override
    public char charAt(int index)
    {
        return stringBuffer.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end)
    {
        return stringBuffer.subSequence(start, end);
    }

    @Override
    public String toString()
    {
        return stringBuffer;
    }
}
