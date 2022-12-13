# Security Policy

## Supported Versions

All [stable versions](https://www.eclipse.org/jetty/download.php) of jetty are actively supported for security issues. [Deprecated versions](https://www.eclipse.org/jetty/download.php) may be supported for serious security issues or on a commercial support basis.

## Reporting a Vulnerability

Do not open a public issue to report a security vulnerability.  Please send a message to security@eclipse.org and we will create a private issue in which the issue can be triaged and handled.

## Handling a Vulnerability

The [following checklist](https://www.eclipse.org/jetty/security_processes.php) is used to handle security issues:

- [ ] On receipt of a security report via security@webtide.com or other channels, if it cannot be trivially dismissed (already fixed, known not a problem, etc.), then a Github security advisory is created by project leadership.
- [ ] Copy this list as a markdown in the security advisory for tracking the completion of various tasks.
- [ ] Jetty committers and the reporters are added to the security advisory. Individual committers can also be named in the comments for addition.
- [ ] Initial triage and discussion are performed in the comments of the advisory.
- [ ] If enough information exists to attempt reproduction or fix, then a private repository is created as part of the GitHub security advisory.
- [ ] If the vulnerability cannot be confirmed then close the security advisory, else continue.
- [ ] Generate a CVE score and add it to the advisory description.
- [ ] Identify a CWE Definition and add it to the advisory description.
- [ ] Identify vulnerable version(s), including current and past versions that are affected (e.g. 9.4.0 through 9.4.35, and 10.0.0.alpha1 through 10.0.0.beta3…​etc.)
- [ ] Identify and document workaround(s), if applicable, in the comments of the security advisory.
- [ ] Open an [Gitlab@Eclipse EMO CVE issue](https://gitlab.eclipse.org/eclipsefdn/emo-team/emo/-/issues/new?issuable_template=cve) to have a CVE allocated.   
      The issue should be opened under the "Eclipse Foundation" > "EMO Team" > "EMO" section as a "cve" description, with the "This issue is confidential" checkbox checked.   
      Follow the template for what details are necessary to file for a CVE.
- [ ] Once the CVE is allocated update the Security Advisory with the number
- [ ] Build and test fix(es) locally and in CI environment.
- [ ] Merge tests and fix  - ensure description does not mention vulnerability directly. Do not merge directly from the security advisory as it can be traced back before publication.
- [ ] Build and stage release candidate.
- [ ] Notify interested parties of pending security advisory and staged release:
    1. Include CVE number, CVE score, and CWE
    2. Include Workarounds
    3. Stress that it is confidential
    4. Advise the security advisory will be published in 2 days unless they indicate they need more time.
- [ ] If testing is OK, then the release is promoted.
- [ ] Interested parties are notified of the availability of release on Maven Central.
- [ ] Publish security advisory and CVE publicly.
- [ ] Edit VERSION.txt and so that the CVE number is now recorded against merged PR.
- [ ] Edit the release(s) on Github to identify CVE number that was addressed/resolved.
- [ ] Update downstream images (Docker, etc.).
- [ ] Update project website with new security entry.
- [ ] Review security processes & completion.
       
