#!/usr/bin/env bash
# Publishes the parent pom and every library module of ZorroBPM CE to Maven Central.
# The central-publishing-maven-plugin picks the target by version: -SNAPSHOT goes to
# the Central snapshots repository, anything else is uploaded as a release bundle.
#
# Env:
#   PUBLISH_DRY_RUN=1  only check the module list against the root pom, do not run Maven
#   ROOT_POM           root pom to check against (default: pom.xml)
set -euo pipefail

cd "$(dirname "$0")/../.."

# Single source of truth for the published modules, in publishing order.
MODULES=(
  zorrobpm-contract
  zorrobpm-event
  zorrobpm-client
  zorrobpm-engine
  zorrobpm-rest
  zorrobpm-job-handler-spring-boot-starter
  zorrobpm-test
  zorrobpm-exchange
  zorrobpm-rabbitmq
  zorrobpm-grpc
)

# Reactor modules that are never published (the executable application).
EXCLUDED=(
  zorrobpm-ce
)

ROOT_POM="${ROOT_POM:-pom.xml}"

declared=$(grep -o '<module>[^<]*</module>' "$ROOT_POM" | sed -e 's:<module>::' -e 's:</module>::' | sort)
expected=$(printf '%s\n' "${MODULES[@]}" "${EXCLUDED[@]}" | sort)

missing=$(comm -23 <(echo "$declared") <(echo "$expected") | xargs)
extra=$(comm -13 <(echo "$declared") <(echo "$expected") | xargs)
if [[ -n "$missing" || -n "$extra" ]]; then
  echo "::error::Published module list does not match <modules> of $ROOT_POM." \
    "Not in the list: ${missing:-none}. Not in the pom: ${extra:-none}." \
    "Update MODULES or EXCLUDED in .github/scripts/publish-modules.sh."
  exit 1
fi
echo "Module list matches $ROOT_POM: ${MODULES[*]}"

if [[ "${PUBLISH_DRY_RUN:-}" == "1" ]]; then
  exit 0
fi

echo "::group::Publishing parent pom"
mvn -B -N -Prelease deploy -DskipTests
echo "::endgroup::"

for m in "${MODULES[@]}"; do
  echo "::group::Publishing $m"
  (cd "$m" && mvn -B -Prelease deploy -DskipTests)
  echo "::endgroup::"
done
