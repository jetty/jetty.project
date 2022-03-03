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

package org.example;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.StringTokenizer;

import jakarta.servlet.jsp.JspContext;
import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.tagext.JspFragment;
import jakarta.servlet.jsp.tagext.SimpleTagSupport;

public class Date2Tag extends SimpleTagSupport
{
    String format;

    public void setFormat(String value)
    {
        this.format = value;
    }

    @Override
    public void doTag() throws JspException, IOException
    {
        String formatted =
            new SimpleDateFormat("long".equals(format) ? "EEE 'the' d:MMM:yyyy" : "d:MM:yy")
                .format(new Date());
        StringTokenizer tok = new StringTokenizer(formatted, ":");
        JspContext context = getJspContext();
        context.setAttribute("day", tok.nextToken());
        context.setAttribute("month", tok.nextToken());
        context.setAttribute("year", tok.nextToken());

        JspFragment fragment = getJspBody();
        fragment.invoke(null);
    }
}

