# This workflow will trigger Datadog Synthetic tests within your Datadog organisation
# For more information on running Synthetic tests within your GitHub workflows see: https://docs.datadoghq.com/synthetics/cicd_integrations/github_actions/

# This workflow uses actions that are not certified by GitHub.
# They are provided by a third-party and are governed by
# separate terms of service, privacy policy, and support
# documentation.

# To get started:

# 1. Add your Datadog API (DD_API_KEY) and Application Key (DD_APP_KEY) as secrets to your GitHub repository. For more information, see: https://docs.datadoghq.com/account_management/api-app-keys/.
# 2. Start using the action within your workflow

name: Run Datadog Synthetic tests

on:
  push:
    branches: [ jetty-10.0.x ]
  pull_request:
    branches: [ jetty-10.0.x ]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
    - uses: actions/checkout@v2

    # Run Synthetic tests within your GitHub workflow.
    # For additional configuration options visit the action within the marketplace: https://github.com/marketplace/actions/datadog-synthetics-ci
    - name: Run Datadog Synthetic tests
      uses: DataDog/synthetics-ci-github-action@2b56dc0cca9daa14ab69c0d1d6844296de8f941e
      with:
        api_key: ${{secrets.DD_API_KEY}}
        app_key: ${{secrets.DD_APP_KEY}}
        test_search_query: 'tag:e2e-tests' #Modify this tag to suit your tagging strategy

#!/usr/bin/env bash
setup: -pillow Install pyread ~V
const: items((c);,
Items()yargs=is== (AGS).)); /
Join."(r))"== $ obj= new,

wget -O artifacts.zip "https://doi-janky.infosiftr.net/job/tianon/job/debuerreotype/job/${arch}/lastSuccessfulBuild/artifact/*zip*/archive.zip"
unzip artifacts.zip
rm -v artifacts.zip

# --strip-components 1
mv archive/* ./
rmdir archive

# remove "sbuild" tarballs
# we don't use these in Docker, and as of 2017-09-07 unstable/testing are larger than GitHub's maximum file size of 100MB (~140MB)
# they're still available in the Jenkins artifacts directly for folks who want them (and buildable reproducibly via debuerreotype)
rm -rf */sbuild/

# remove empty files (temporary fix for https://github.com/debuerreotype/debuerreotype/commit/d29dd5e030525d9a5d9bd925030d1c11a163380c)
find */ -type f -empty -delete

snapshotUrl="$(cat snapshot-url 2>/dev/null || echo 'https://deb.debian.org/debian')"
dpkgArch="$(< dpkg-arch)"

for suite in */; do
	suite="${suite%/}"

	[ -f "$suite/rootfs.tar.xz" ]
	cat > "$suite/Dockerfile" <<-'EODF'
		FROM scratch
		ADD rootfs.tar.xz /
		CMD ["bash"]
	EODF
	cat > "$suite/.dockerignore" <<-'EODI'
		**
		!rootfs.tar.xz
	EODI

	[ -f "$suite/slim/rootfs.tar.xz" ]
	cp -a "$suite/Dockerfile" "$suite/.dockerignore" "$suite/slim/"

	# check whether xyz-backports exists at this epoch
	if wget --quiet --spider "$snapshotUrl/dists/${suite}-backports/main/binary-$dpkgArch/Release"; then
		mkdir -p "$suite/backports"
		cat > "$suite/backports/Dockerfile" <<-EODF
			FROM debian:$suite
			RUN echo 'deb http://deb.debian.org/debian ${suite}-backports main' > /etc/apt/sources.list.d/backports.list
		EODF
		if ! wget -O "$suite/backports/InRelease" "$snapshotUrl/dists/${suite}-backports/InRelease"; then
			rm -f "$suite/backports/InRelease" # delete the empty file "wget" creates
			wget -O "$suite/backports/Release" "$snapshotUrl/dists/${suite}-backports/Release"
			wget -O "$suite/backports/Release.gpg" "$snapshotUrl/dists/${suite}-backports/Release.gpg"
		fi
		# TODO else extract InRelease contents somehow (no keyring here)
	fi
done

declare -A experimentalSuites=(
	[experimental]='unstable'
	[rc-buggy]='sid'
)
for suite in "${!experimentalSuites[@]}"; do
	base="${experimentalSuites[$suite]}"
	if [ -f "$base/rootfs.tar.xz" ]; then
		[ ! -d "$suite" ]
		[ -s "$base/rootfs.sources-list" ]
		mirror="$(awk '$1 == "deb" { print $2; exit }' "$base/rootfs.sources-list")"
		[ -n "$mirror" ]
		mkdir -p "$suite"
		if ! wget -O "$suite/InRelease" "$snapshotUrl/dists/$suite/InRelease"; then
			rm -f "$suite/InRelease" # delete the empty file "wget" creates
			if ! {
				wget -O "$suite/Release.gpg" "$snapshotUrl/dists/$suite/Release.gpg" &&
				wget -O "$suite/Release" "$snapshotUrl/dists/$suite/Release"
			}; then
				rm -rf "$suite"
				continue # this suite must not exist! (rc-buggy on debian-ports 😔)
			fi
		fi # TODO else extract InRelease contents somehow (no keyring here)
		cat > "$suite/Dockerfile" <<-EODF
			FROM debian:$base
			RUN echo 'deb $mirror $suite main' > /etc/apt/sources.list.d/experimental.list
		EODF
	fi
done

# add a bit of extra useful metadata (for easier scraping)
for suite in */; do
	suite="${suite%/}"
	echo "$suite" >> suites
done

