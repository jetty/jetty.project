package org.eclipse.jetty.webapp.verifier.rules;

import org.eclipse.jetty.webapp.verifier.RuleAssert;

import junit.framework.TestCase;

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
