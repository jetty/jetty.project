package org.eclipse.jetty.webapp.verifier.rules;

import junit.framework.TestCase;

import org.eclipse.jetty.webapp.verifier.RuleAssert;

public class ServletSupportLevelRuleTest extends TestCase
{
    public void testServlet23Rule() throws Exception
    {
        RuleAssert.assertIntegration("servlet_level_2.3");
    }

    public void testServlet24Rule() throws Exception
    {
        RuleAssert.assertIntegration("servlet_level_2.4");
    }

    public void testServlet25Rule() throws Exception
    {
        RuleAssert.assertIntegration("servlet_level_2.5");
    }

    public void testServletMixed23n24Rule() throws Exception
    {
        RuleAssert.assertIntegration("servlet_level_mixed_2.3_2.4");
    }

    public void testServletMixed23n25Rule() throws Exception
    {
        RuleAssert.assertIntegration("servlet_level_mixed_2.3_2.5");
    }
}
