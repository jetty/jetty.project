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

import org.eclipse.jetty.webapp.verifier.rules.ForbiddenContentsRule;
import org.eclipse.jetty.webapp.verifier.rules.RequiredContentsRule;

public class RuleSetTest extends AbstractTestWebappVerifier
{
    public void testLoad() throws Exception
    {
        RuleSet suite = loadRuleSet("basic-ruleset.xml");
        assertNotNull("Should have a valid RuleSet.",suite);

        assertNotNull("verifier list should not be null",suite.getRules());
        assertEquals("Should have 2 verifier",2,suite.getRules().size());

        Rule verifier = suite.getRules().get(0);
        assertEquals("Verifier[0]",ForbiddenContentsRule.class.getName(),verifier.getClass().getName());
        verifier = suite.getRules().get(1);
        assertEquals("Verifier[1]",RequiredContentsRule.class.getName(),verifier.getClass().getName());
    }
}
