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
package org.eclipse.jetty.logging.impl;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Default log output formatter.
 */
public class DefaultFormatter implements Formatter
{
    private String dateFormat = "yyyy-MM-dd HH:mm:ss.SSS";

    public String format(Date date, Severity severity, String name, String message)
    {
        StringBuffer buf = new StringBuffer();
        buf.append(new SimpleDateFormat(dateFormat).format(date));
        buf.append(':').append(severity.name()).append(':');
        buf.append(name);
        buf.append(':').append(message);
        return buf.toString();
    }

    public String getDateFormat()
    {
        return dateFormat;
    }

    public void setDateFormat(String dateFormat)
    {
        this.dateFormat = dateFormat;
    }
}
