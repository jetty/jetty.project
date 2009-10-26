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
package org.eclipse.jetty.webapp.verifier;

import java.io.File;

import junit.framework.TestCase;

import org.eclipse.jetty.util.IO;

public class AbstractTestWebappVerifier extends TestCase
{
    protected File getTestWorkDir()
    {
        File workDir = MavenTestingUtils.toTargetFile("test-work-dir");
        if (!workDir.exists())
        {
            workDir.mkdirs();
        }
        File testSpecificDir = new File(workDir,this.getClass().getSimpleName() + "_" + getName());
        if (testSpecificDir.exists())
        {
            IO.delete(testSpecificDir);
        }
        testSpecificDir.mkdirs();
        return testSpecificDir;
    }

    protected RuleSet loadRuleSet(String name) throws Exception
    {
        File xmlFile = MavenTestingUtils.getTestResourceFile(name);
        return RuleSet.load(xmlFile.toURL());
    }
}
