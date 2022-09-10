---
name: 'Release Process'
about: 'COMMITTER ONLY: Managing the Jetty release process'
title: 'Jetty Releases 9.4.x, 10.0.y, 11.0.y'
assignees: ''
labels: Build

---

**Jetty Versions:**
This release process will produce releases:

**Target Date:**

**Tasks:**
- [x] Create the release(s) issue.
- [ ] Update the target Jetty version(s) in the issue.  
- [ ] Update the target release date in the issue.
- [ ] Link this issue to the target [GitHub Project(s)](https://github.com/eclipse/jetty.project/projects).
- [ ] Assign this issue to a "release manager".
- [ ] Review [draft security advisories](https://github.com/eclipse/jetty.project/security/advisories). Ensure that issues are created and assigned to GitHub Projects to capture any advisories that will be announced.
- [ ] Create the [GitHub Project(s)](https://github.com/eclipse/jetty.project/projects) for the next releases.
- [ ] Review dependabot status. If there has not been a recent run, run [manually](https://github.com/eclipse/jetty.project/network/updates) and review resulting PRs for inclusion.
- [ ] Review the issues/PRs assigned to the target [GitHub Project(s)](https://github.com/eclipse/jetty.project/projects).  Any PRs that are moved to next releases should be commented on so their authors are informed.
- [ ] Freeze the target [GitHub Project(s)](https://github.com/eclipse/jetty.project/projects) by editing their names to "Jetty X.Y.Z FROZEN"
- [ ] Wait 24 hours from last change to the issues/PRs included in FROZEN GitHub Project(s).
- [ ] Verify target [project(s)](https://github.com/eclipse/jetty.project/projects) are complete.
- [ ] Verify that branch `jetty-10.0.x` is merged to branch `jetty-11.0.x`.
- [ ] Assign issue to "build manager", who will stage the releases.
  + [ ] Create and use branches `release/<ver>` to perform version specific release work from.
  + [ ] Ensure `VERSION.txt` additions for each release will be meaningful, descriptive, correct text.
  + [ ] Stage 9.4 release with Java 11.
  + [ ] Stage 10 release with Java 17.
  + [ ] Stage 11 release with Java 17.
  + [ ] Edit a draft release (for each Jetty release) in GitHub (https://github.com/eclipse/jetty.project/releases). Content is generated with the "changelog tool".
  + [ ] Push tag to the fork https://github.com/jetty-project/jetty.project
  + [ ] Push the branch release/target_jetty_version to the fork https://github.com/jetty-project/jetty.project
- [ ] Assign issue to "test manager", who will oversee the testing of the staged releases.
  + [ ] Test [CometD](https://github.com/cometd/cometd).
  + [ ] Test [Reactive HttpClient](https://github.com/jetty-project/jetty-reactive-httpclient).
  + [ ] Test [Load Generator](https://github.com/jetty-project/jetty-load-generator).
  + [ ] Test [Jetty Docker images](https://github.com/eclipse/jetty.docker).
  + [ ] Test other [Jetty OSS integrations](https://jenkins.webtide.net/job/external_oss).
  + [ ] Check [TCK CI](https://jenkins.webtide.net/job/tck).
  + [ ] Test sponsored integrations.
  + [ ] Check CI for performance regressions.
  + [ ] Notify interested parties and invite testing of the staged release(s).
  + [ ] Assign issue back to "release manager".
- [ ] Collect release votes from committers.
- [ ] Promote staged releases.
- [ ] Merge release branches back to main branches and delete release branches.
- [ ] Verify release existence in Maven Central by triggering the Jenkins builds of CometD.
- [ ] Update Jetty versions on the web sites.
  + [ ] Update (or check) [Download](https://www.eclipse.org/jetty/download.php) page is updated.
  + [ ] Update (or check) documentation page(s) are updated.
- [ ] Publish GitHub Releases.
- [ ] Prepare release announcement for mailing lists.
- [ ] Publish any [security advisories](https://github.com/eclipse/jetty.project/security/advisories).
  + [ ] Edit `VERSION.txt` to include any actual CVE number next to correspondent issue.
  + [ ] Edit any issues for CVEs in github with their CVE number
- [ ] Notify downstream maintainers.
  + [ ] Eclipse p2 maintainer.
  + [ ] Docker maintainer.
  + [ ] Jenkins maintainer.
  + [ ] Other maintainers.
