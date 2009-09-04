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
 * <p>
 * Sanity check of archive (both war itself, as well as libs) to ensure that various entries within are sane.
 * </p>
 * 
 * <p>
 * Example checks:
 * </p>
 * 
 * <ol>
 * <li>Entries cannot contain references to parent "../../my_file.txt"</li>
 * <li>Cannot contain multiple entries of the same name with different case.
 * <ul>
 * <li>/index.jsp</li>
 * <li>/Index.jsp</li>
 * </ul>
 * <li>
 * </ol>
 */
public class SaneArchiveRule extends AbstractRule
{
    public String getDescription()
    {
        return "Basic archive (jar & war) sanity checks";
    }

    public String getName()
    {
        return "sane-archive";
    }

    @Override
    public void visitWebappStart(String path, File dir)
    {
        // TODO: implement rule.
        error(path,"Rule [" + getClass().getName() + "] not yet implemented");
    }
}
