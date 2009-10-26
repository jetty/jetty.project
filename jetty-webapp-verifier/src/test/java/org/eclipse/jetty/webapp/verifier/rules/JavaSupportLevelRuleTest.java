package org.eclipse.jetty.webapp.verifier.rules;

import junit.framework.TestCase;

import org.eclipse.jetty.webapp.verifier.RuleAssert;

public class JavaSupportLevelRuleTest extends TestCase
{
    public void testJava15() throws Exception
    {
        RuleAssert.assertIntegration("java_level_1.5");
    }

    public void testJava14() throws Exception
    {
        RuleAssert.assertIntegration("java_level_1.4");
    }
}
