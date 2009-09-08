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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.framework.Assert;

import org.eclipse.jetty.util.IO;

/**
 * Rule based assertions.
 */
public class RuleAssert
{
    private static Pattern expectedViolationPattern;

    /**
     * <p>
     * Perform a static Rule assertion.
     * </p>
     * 
     * <p>
     * You will need the following files.
     * </p>
     * 
     * <ol>
     * <li><code>src/test/resources/${prefix}.setup.txt</code> - contains 1 line, which is the src/test/resources
     * relative path to the webapp you want to test, (it can be a WAR file, or an exploded webapp directory</li>
     * 
     * <li><code>src/test/resources/${prefix}.config.xml</code> - contains an XMLConfiguration suitable XML file for
     * loading into a RuleSet. Represents the configuration that will be used for this test.</li>
     * 
     * <li><code>src/test/resources/${prefix}.expectations.txt</code> - contains a list of expected Violations (1
     * violation per line), with the expected values pipe <code>"|"</code> delimited. Example: pattern is
     * <code>"${severity}|${path}|${detail}"</code>, where a representative line
     * <code>"ERROR|/WEB-INF|Missing required web.xml"</code> would mean a violation of type {@link Severity#ERROR},
     * with path <code>"/WEB-INF"</code>, and detail <code>"Missing required web.xml"</code></li>
     * </ol>
     * 
     * @param prefix
     *            the prefix of the integration files in src/test/resources that you want to use.
     * @throws Exception
     */
    public static void assertIntegration(String prefix) throws Exception
    {
        // Load the 3 integration files.
        File setupFile = MavenTestingUtils.getTestResourceFile(prefix + ".setup.txt");
        File configFile = MavenTestingUtils.getTestResourceFile(prefix + ".config.xml");
        File expectationFile = MavenTestingUtils.getTestResourceFile(prefix + ".expectations.txt");

        // Establish Webapp to use
        String webappName = MavenTestingUtils.readToString(setupFile).trim();
        File webappPath = MavenTestingUtils.getTestResourcePath(webappName);
        URI webappURI = webappPath.toURI();

        // Load RuleSet configuration
        RuleSet ruleset = RuleSet.load(configFile);

        // Run Verification
        WebappVerifier verifier = ruleset.createWebappVerifier(webappURI);
        verifier.setWorkDir(MavenTestingUtils.toTargetTestingDir());
        verifier.visitAll();

        // Compare violations
        assertViolations(loadExpectedViolations(expectationFile),verifier.getViolations());
    }

    public static void assertViolations(Collection<Violation> expectedColl, Collection<Violation> actualColl)
    {
        List<Violation> actualViolations = new ArrayList<Violation>(actualColl);
        List<Violation> expectedViolations = new ArrayList<Violation>(expectedColl);

        Collections.sort(actualViolations,ViolationComparator.getInstance());
        Collections.sort(expectedViolations,ViolationComparator.getInstance());

        // Compare expected vs actual
        if (expectedViolations.size() != actualViolations.size())
        {
            dumpViolations("Expected",expectedViolations);
            dumpViolations("Actual",actualViolations);
            Assert.assertEquals("Violation count",expectedViolations.size(),actualViolations.size());
        }

        for (int i = 0, n = expectedViolations.size(); i < n; i++)
        {
            Violation expected = expectedViolations.get(i);
            Violation actual = actualViolations.get(i);

            Assert.assertEquals("Violation[" + i + "].path",expected.getPath(),actual.getPath());
            Assert.assertEquals("Violation[" + i + "].detail",expected.getDetail(),actual.getDetail());
            Assert.assertEquals("Violation[" + i + "].severity",expected.getSeverity(),actual.getSeverity());
            // TODO: add check on Violation.throwable
            // TODO: add check on Violation.verifierId
            // TODO: add check on Violation.verifierClass
        }
    }

    public static void dumpViolations(String msg, Collection<Violation> violations)
    {
        System.out.println();
        System.out.printf("/* Violations Dump: %s */%n",msg);
        for (Violation violation : violations)
        {
            System.out.println(violation.toDelimString());
        }
    }

    public static Collection<Violation> loadExpectedViolations(File expectationFile) throws IOException
    {
        FileReader reader = null;
        BufferedReader buf = null;
        try
        {
            List<Violation> ret = new ArrayList<Violation>();
            reader = new FileReader(expectationFile);
            buf = new BufferedReader(reader);

            String line;

            while ((line = buf.readLine()) != null)
            {
                if (line.charAt(0) == '#')
                {
                    // a comment.
                    continue;
                }

                // Parse line
                Violation violation = parseViolation(line);

                Assert.assertNotNull("Unable to parse expected violation line: " + line,violation);

                // Add to list
                ret.add(violation);
            }

            return ret;
        }
        finally
        {
            IO.close(buf);
            IO.close(reader);
        }
    }

    protected static Violation parseViolation(String line)
    {
        expectedViolationPattern = Pattern.compile("^([^|]*)\\|([^|]*)\\|(.*)$");
        Matcher mat = expectedViolationPattern.matcher(line);
        if (mat.matches())
        {
            Severity severity = Severity.parse(mat.group(1));
            String path = mat.group(2);
            String detail = mat.group(3);
            return new Violation(severity,path,detail);
        }
        else
            return null;
    }
}
