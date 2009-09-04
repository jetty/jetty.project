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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.webapp.verifier.AbstractRule;
import org.eclipse.jetty.webapp.verifier.support.PathGlob;

/**
 * ForbiddenContentsVerifier ensures that content matching a pattern does not exist in the webapp.
 */
public class ForbiddenContentsRule extends AbstractRule
{
    private List<String> _patterns = new ArrayList<String>();

    public void addPattern(String pattern)
    {
        _patterns.add(pattern);
    }

    public String getDescription()
    {
        return "Checks for forbidden content";
    }

    public String getName()
    {
        return "forbidden-content";
    }

    @Override
    public void visitFile(String path, File dir, File file)
    {
        for (String pattern : _patterns)
        {
            if (PathGlob.match(pattern,path))
            {
                error(path,"Forbidden content detected");
            }
        }
    }
}
