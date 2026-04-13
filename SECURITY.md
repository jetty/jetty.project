# Security Policy

## Supported Versions

All [active versions](https://jetty.org/download.html) of Jetty are supported for security issues. 
[End-of-life (EOL) versions](https://jetty.org/download.html) of Jetty may be supported for serious security issues or on a [commercial support basis](https://jetty.org/support.html).

## Reporting a Vulnerability

Do not open a public issue to report a security vulnerability.  
Please send a message to <security@jetty.org>, and we will create a private issue in which the issue can be triaged and handled.
We are flexible in how we work with reporters of vulnerabilities; however, we reserve the right to act in the interests of the Jetty project in all circumstances.

AI-generated security reports must be human-verified and possibly contain a real code proof-of-concept.
Failing to do so, also known as [AI slop](https://en.wikipedia.org/wiki/AI_slop), may result in banning the reporter from the Jetty project.

## Handling a Vulnerability

The following checklist is used to handle security issues:

- [ ] On receipt of a security report via security@jetty.org or other channels, if it cannot be trivially dismissed (already fixed, known not a problem, etc.), then project leadership creates a GitHub security advisory.
- [ ] Copy this list as a Markdown in the security advisory for tracking the completion of various tasks.
- [ ] Jetty committers and the reporters are added to the security advisory. Individual committers can also be named in the comments for addition.
- [ ] Initial triage and discussion are performed in the comments of the advisory.
- [ ] If enough information exists to attempt reproduction or fix, then a private repository is created as part of the GitHub security advisory.
- [ ] If the vulnerability cannot be confirmed, then close the security advisory, else continue.
- [ ] Generate a CVE score and add it to the advisory description.
- [ ] Identify a CWE Definition and add it to the advisory description.
- [ ] Identify vulnerable version(s), including current and past versions that are affected (for example, 9.4.0 through 9.4.35, 10.0.0.alpha1 through 10.0.0.beta3, etc.)
- [ ] Identify and document workaround(s), if applicable, in the comments of the security advisory.
- [ ] Open a [Gitlab@Eclipse CVE Assignment](https://gitlab.eclipse.org/security/cve-assignement/-/issues/new) to have a CVE allocated.   
      The issue should be opened under the "Eclipse Foundation" > "EMO Team" > "EMO" section as a "cve" description, with the "This issue is confidential" checkbox checked.   
      Follow the template for what details are necessary to file for a CVE.
- [ ] Once the CVE is allocated, update the Security Advisory with the number
- [ ] Build and test fix(es) locally and in the CI environment.
- [ ] Merge tests and fix - ensure description does not mention vulnerability directly. Do not merge directly from the security advisory as it can be traced back before publication.
- [ ] Build and stage the release candidate.
- [ ] Notify interested parties of the pending security advisory and of the staged release:
    1. Include CVE number, CVE score, and CWE
    2. Include Workarounds
    3. Stress that it is confidential
    4. Advise the security advisory will be published in 1 month unless they indicate they need more time.
- [ ] If testing is OK, then the release is promoted.
- [ ] Interested parties are notified of the availability of the release on Maven Central.
- [ ] Publish security advisory and CVE publicly.
- [ ] Edit VERSION.txt and so that the CVE number is now recorded against merged PR.
- [ ] Edit the release(s) on GitHub to identify the CVE number that was addressed/resolved.
- [ ] Update downstream images (Docker, etc.).
- [ ] Update the project [website](https://jetty.org/security.html) with the new security entry.
- [ ] Review security processes and completion.

