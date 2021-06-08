---
name: Release Process
about: Managing the preparation, generation and publication of Jetty releases
title: 'Jetty Releases 9.4.x, 10.0.y, 11.0.y'
assignees: ''

---

**Jetty Versions:**
This release process will produce releases:

**Target Date:**

**Tasks:**
- [x] Create release issue
- [ ] Update target jetty versions.  Indicate why any are excluded.
- [ ] Update target release date
- [ ] Link this issue to the target projects
- [ ] Assign this issue to a "release manager" who will drive the process to staging.
- [ ] Review draft security advisories. Ensuring issues are created and assigned to projects to capture any advisories that will be announced.
- [ ] Review the github projects for the releases. Any PRs that are moved to subsequent releases should be commented on so their authors are informed. 
- [ ] Freeze projects by editing names to "jetty-X.Y.Z FROZEN"
   + [ ] Frozen 9.4
   + [ ] Frozen 10 & 11
- [ ] Complete and merge all PRs assigned to the projects.
- [ ] Merge 10 to 11.
- [ ] Wait 24 hours from last change to the issues/PRs included in a FROZEN project.
- [ ] Assign issue to "build manager", who will stage the releases.
   + [ ] Staged 9
   + [ ] Staged 10
   + [ ] Staged 11
- [ ] Assign issue to "test manager", who will oversee the testing of the staged releases:
   + [ ] Test cometd
   + [ ] Test docker 
   + [ ] Test commercial client integrations
   + [ ] Check TCK CI
   + [ ] Check CI for performance regressions
- [ ] Reassign issue to "release manager"
- [ ] Promote staged releases
- [ ] Prepare change list for distribution to mailing and client list
- [ ] Merge release branches back to main branches
- [ ] Publish any security advisories
- [ ] Edit VERSION.TXTs to include any actual CVE numbers for subsequent releases  
- [ ] Notify downstream maintainers:
    + [ ] p2 maintainer
    + [ ] docker
    + [ ] flex
- [ ] Create new projects for subsequent releases.
- [ ] Review schedule for subsequent releases.
