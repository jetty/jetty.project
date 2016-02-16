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

package org.eclipse.jetty.cdi.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;

@Named("params")
@RequestScoped
public class RequestParamsDumper implements Dumper
{
    @Inject
    private HttpServletRequest request;

    @Override
    public void dump(PrintWriter out) throws IOException
    {
        out.printf("request is %s%n",request == null ? "NULL" : "PRESENT");

        if (request != null)
        {
            Map<String, String[]> params = request.getParameterMap();
            List<String> paramNames = new ArrayList<>();
            paramNames.addAll(params.keySet());
            Collections.sort(paramNames);

            out.printf("parameters.size = [%d]%n",params.size());

            for (String name : paramNames)
            {
                out.printf(" param[%s] = [",name);
                boolean delim = false;
                for (String val : params.get(name))
                {
                    if (delim)
                    {
                        out.print(", ");
                    }
                    out.print(val);
                    delim = true;
                }
                out.println("]");
            }
        }
    }
}
