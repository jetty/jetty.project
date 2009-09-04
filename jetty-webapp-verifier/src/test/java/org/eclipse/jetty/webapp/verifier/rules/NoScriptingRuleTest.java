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

import org.eclipse.jetty.webapp.verifier.AbstractTestWebappVerifier;
import org.eclipse.jetty.webapp.verifier.RuleAssert;

public class NoScriptingRuleTest extends AbstractTestWebappVerifier
{
    public void testJRubyConfiguration() throws Exception
    {
        RuleAssert.assertIntegration("no_scripting_jruby");
    }

    public void testJythonConfiguration() throws Exception
    {
        RuleAssert.assertIntegration("no_scripting_jython");
    }

    public void testGroovyConfiguration() throws Exception
    {
        RuleAssert.assertIntegration("no_scripting_groovy");
    }

    public void testShellConfiguration() throws Exception
    {
        RuleAssert.assertIntegration("no_scripting_shell");
    }
}
