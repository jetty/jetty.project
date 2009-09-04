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

/**
 * ForbiddenContentsVerifier ensures that content matching a pattern does not exist in the webapp.
 */
public class RequiredContentsRule extends AbstractRule
{
    private List<String> _paths = new ArrayList<String>();

    public void addPath(String path)
    {
        _paths.add(path);
    }

    public String getDescription()
    {
        return "Ensures that requred content is present";
    }

    public String getName()
    {
        return "required-content";
    }

    @Override
    public void visitDirectoryEnd(String path, File dir)
    {
        if (path.equals(ROOT_PATH))
        {
            File root = new File(path);

            for (String expectedPath : _paths)
            {
                File file = new File(root,expectedPath);
                if (!file.exists())
                {
                    error(getWebappRelativePath(file),"Required content not found");
                }
            }
        }
    }

}
