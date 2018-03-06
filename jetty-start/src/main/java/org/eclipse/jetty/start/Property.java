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

package org.eclipse.jetty.start;

public class Property
{
    public String key;
    public String value;
    public String source;

    public Property(String key, String value, String source)
    {
        this.key = key;
        this.value = value;
        this.source = source;
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("Prop [key=");
        builder.append(key);
        builder.append(", value=");
        builder.append(value);
        builder.append(", source=");
        builder.append(source);
        builder.append("]");
        return builder.toString();
    }
}