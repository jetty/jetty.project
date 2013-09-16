//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.http.HttpFields;
import org.junit.Assert;

public final class HttpAsserts
{
    public static void assertContainsHeaderKey(String expectedKey, HttpFields headers)
    {
        if (headers.containsKey(expectedKey))
        {
            return;
        }
        List<String> names = Collections.list(headers.getFieldNames());
        StringBuilder err = new StringBuilder();
        err.append("Missing expected header key [").append(expectedKey);
        err.append("] (of ").append(names.size()).append(" header fields)");
        for (int i = 0; i < names.size(); i++)
        {
            String value = headers.getStringField(names.get(i));
            err.append("\n").append(i).append("] ").append(names.get(i));
            err.append(": ").append(value);
        }
        Assert.fail(err.toString());
    }
}
