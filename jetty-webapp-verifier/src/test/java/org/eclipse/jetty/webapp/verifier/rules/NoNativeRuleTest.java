package org.eclipse.jetty.webapp.verifier.rules;

import junit.framework.TestCase;

import org.eclipse.jetty.webapp.verifier.RuleAssert;

public class NoNativeRuleTest extends TestCase
{
    public void testNoNative() throws Exception
    {
        RuleAssert.assertIntegration("no_native");
    }
}
