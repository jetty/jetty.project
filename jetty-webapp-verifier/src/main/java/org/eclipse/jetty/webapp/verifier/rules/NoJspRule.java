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
package org.eclipse.jetty.webapp.verifier.rules;

import java.io.File;

import org.eclipse.jetty.webapp.verifier.AbstractRule;

/**
 * Prevent inclusion of *.jsp in webapp.
 */
public class NoJspRule extends AbstractRule
{
    public String getDescription()
    {
        return "Prevent inclusion of JSPs in webapp";
    }

    public String getName()
    {
        return "no-jsp";
    }

    @Override
    public void visitFile(String path, File dir, File file)
    {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".jsp") || name.endsWith(".jspf"))
        {
            error(path,"No JSP's are allowed");
        }
    }
}
