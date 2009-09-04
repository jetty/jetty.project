package org.eclipse.jetty.webapp.verifier.rules;

import org.eclipse.jetty.webapp.verifier.RuleAssert;

import junit.framework.TestCase;

public class NoNativeRuleTest extends TestCase
{
    public void testNoNative() throws Exception
    {
        RuleAssert.assertIntegration("no_native");
    }
}
