# Release helpers; see CONTRIBUTING.md, "Releasing".
#
#   make release-dry-run              build the Central bundle without signing or uploading
#   make release VERSION=X.Y.Z        publish X.Y.Z to Maven Central, then push the vX.Y.Z tag
#
# Recipes are single shell commands because macOS ships GNU make 3.81 (no .ONESHELL).

SHELL := /bin/bash
MVNW := ./mvnw -B
RELEASE_BRANCH := master
TAG = v$(VERSION)
BUNDLE := target/central-publishing/central-bundle.zip

.PHONY: help release release-check release-dry-run

help:
	@sed -n 's/^#   //p' $(MAKEFILE_LIST)

# Fails before anything is changed: bad version, dirty tree, wrong or stale branch, existing tag,
# or no Central credentials.
release-check:
	@set -euo pipefail; \
	[[ -n "$(VERSION)" ]] || { echo "usage: make release VERSION=X.Y.Z"; exit 1; }; \
	[[ "$(VERSION)" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$$ ]] \
		|| { echo "VERSION must look like 1.2.3 or 1.2.3-rc1, got '$(VERSION)'"; exit 1; }; \
	[[ -z "$$(git status --porcelain)" ]] || { echo "working tree is not clean"; exit 1; }; \
	branch="$$(git branch --show-current)"; \
	[[ "$$branch" == "$(RELEASE_BRANCH)" ]] \
		|| { echo "release from $(RELEASE_BRANCH), not '$$branch'"; exit 1; }; \
	git fetch -q origin "$(RELEASE_BRANCH)" --tags; \
	[[ "$$(git rev-parse HEAD)" == "$$(git rev-parse "origin/$(RELEASE_BRANCH)")" ]] \
		|| { echo "$(RELEASE_BRANCH) is not in sync with origin/$(RELEASE_BRANCH)"; exit 1; }; \
	if git rev-parse -q --verify "refs/tags/$(TAG)" >/dev/null; then echo "tag $(TAG) already exists"; exit 1; fi; \
	grep -q '<id>central</id>' "$$HOME/.m2/settings.xml" 2>/dev/null \
		|| { echo "no 'central' server in ~/.m2/settings.xml"; exit 1; }; \
	echo "ready to release $(VERSION) from $(RELEASE_BRANCH) @ $$(git rev-parse --short HEAD)"

# The release version is set only for the deploy and never committed; the poms are restored even
# when the deploy fails. Central releases are permanent, so the tag is pushed only after the deploy
# reports the release as published.
release: release-check
	@set -euo pipefail; \
	restore() { git checkout -q -- '*pom.xml'; }; \
	trap restore EXIT; \
	$(MVNW) -q versions:set -DnewVersion="$(VERSION)" -DgenerateBackupPoms=false -DprocessAllModules=true; \
	$(MVNW) -Prelease clean deploy; \
	restore; trap - EXIT; \
	git tag "$(TAG)"; \
	git push origin "$(TAG)"; \
	echo "released $(VERSION); release.yml now creates the GitHub release for $(TAG)"

# Builds, tests and bundles exactly what `release` would upload, for the current pom version.
release-dry-run:
	@set -euo pipefail; \
	$(MVNW) -Prelease clean deploy -Dgpg.skip -DskipPublishing=true; \
	echo "bundle contents ($(BUNDLE)):"; \
	unzip -Z1 "$(BUNDLE)" | grep -E '\.(pom|jar)$$' | sort
