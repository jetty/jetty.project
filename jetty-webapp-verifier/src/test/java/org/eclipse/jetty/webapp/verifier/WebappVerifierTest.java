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

import java.util.Collection;

public class WebappVerifierTest extends AbstractTestWebappVerifier
{
    public void testVerifierVisitation() throws Exception
    {
        CountingRule counts = new CountingRule();

        // Create Webapp Specific Verifier from Verifier Suite
        WebappVerifier verifier = new WebappVerifier(MavenTestingUtils.toTargetFile("war-files/test-webapp-logging-java.war").toURI());
        verifier.addRule(counts);
        verifier.setWorkDir(getTestWorkDir());

        // Run the verification.
        verifier.visitAll();

        // Collect the violations
        Collection<Violation> violations = verifier.getViolations();
        assertNotNull("Should never have a null set of Violations",violations);
        assertEquals("No violations caused",0,violations.size());

        // Ensure each visitor was visited according to real contents of WAR
        assertEquals("Counts.webappStart",1,counts.countWebappStart);
        assertEquals("Counts.countWebappEnd",1,counts.countWebappEnd);

        // Visits in Directory
        assertEquals("Counts.countDirStart",12,counts.countDirStart);
        assertEquals("Counts.countFile",6,counts.countFile);
        assertEquals("Counts.countDirEnd",12,counts.countDirEnd);
        assertEquals("Counts.countDir (Start == End)",counts.countDirStart,counts.countDirEnd);

        // Visits in WEB-INF/classes
        assertEquals("Counts.countWebInfClassesStart",1,counts.countWebInfClassesStart);
        assertEquals("Counts.countWebInfClass",1,counts.countWebInfClass);
        assertEquals("Counts.countWebInfClassResource",1,counts.countWebInfClassResource);
        assertEquals("Counts.countWebInfClassesEnd",1,counts.countWebInfClassesEnd);
        assertEquals("Counts.countWebInfClasses (Start == End)",counts.countWebInfClassesStart,counts.countWebInfClassesEnd);

        // Visits in WEB-INF/lib
        assertEquals("Counts.countWebInfLibStart",0,counts.countWebInfLibStart);
        assertEquals("Counts.countWebInfLibJar",0,counts.countWebInfLibJar);
        assertEquals("Counts.countWebInfLibZip",0,counts.countWebInfLibZip);
        assertEquals("Counts.countWebInfLibEnd",0,counts.countWebInfLibEnd);
        assertEquals("Counts.countWebInfLib (Start == End)",counts.countWebInfLibStart,counts.countWebInfLibEnd);
    }
}
