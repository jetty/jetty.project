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
package org.eclipse.jetty.webapp.deploy;

import java.io.File;
import java.net.URI;
import java.util.Collection;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.verifier.RuleSet;
import org.eclipse.jetty.webapp.verifier.Severity;
import org.eclipse.jetty.webapp.verifier.Violation;
import org.eclipse.jetty.webapp.verifier.WebappVerifier;

/**
 * Deploy Binding for the Webapp Verifier.
 * 
 * Will verify the webapp being deployed, to ensure that it satisfies the ruleset configured.
 */
public class WebappVerifierBinding implements AppLifeCycle.Binding
{
    private String rulesetPath;

    public String getRulesetPath()
    {
        return rulesetPath;
    }

    public void setRulesetPath(String rulesetPath)
    {
        this.rulesetPath = rulesetPath;
    }

    public String[] getBindingTargets()
    {
        return new String[]
        { "pre-deploying" };
    }

    public void processBinding(Node node, App app, DeploymentManager deploymentManager) throws Exception
    {
        File rulesetFile = new File(this.rulesetPath);

        RuleSet ruleset = RuleSet.load(rulesetFile);

        URI warURI = app.getArchivePath().toURI();

        WebappVerifier verifier = ruleset.createWebappVerifier(warURI);

        verifier.visitAll();

        Collection<Violation> violations = verifier.getViolations();
        if (violations.size() <= 0)
        {
            // Nothing to report.
            Log.info("Webapp Verifier - All Rules Passed - No Violations");
            return;
        }

        boolean haltWebapp = false;
        Log.info("Webapp Verifier Found " + violations.size() + " violations.");

        for (Violation violation : violations)
        {
            if (violation.getSeverity() == Severity.ERROR)
            {
                haltWebapp = true;
            }

            Log.info(violation.toString());
        }

        if (haltWebapp)
        {
            throw new IllegalStateException("Webapp Failed Webapp Verification");
        }
    }
}
