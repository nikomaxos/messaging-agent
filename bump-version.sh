#!/bin/bash

if [ -z "$1" ]; then
  echo "Usage: ./bump-version.sh <new-version>"
  echo "Example: ./bump-version.sh 2.5.0"
  exit 1
fi

NEW_VERSION=$1
echo "Bumping version to $NEW_VERSION across all services..."

# Bump package.json versions
for package_json in deploy-agent/package.json services/api-gateway/package.json services/prefix-updater/package.json admin-panel/package.json; do
  if [ -f "$package_json" ]; then
    sed -i -E "s/\"version\": \"[0-9]+\.[0-9]+\.[0-9]+\"/\"version\": \"$NEW_VERSION\"/g" "$package_json"
    echo "Updated $package_json"
  fi
done

# Bump pom.xml versions safely using Maven
for module_dir in services/routing-engine services/core-service services/smpp-edge services/rcs-mautrix services/device-gateway; do
  if [ -d "$module_dir" ] && [ -f "$module_dir/pom.xml" ]; then
    (cd "$module_dir" && mvn versions:set -DnewVersion=$NEW_VERSION -DgenerateBackupPoms=false)
    echo "Updated $module_dir/pom.xml"
  fi
done

echo "Version bump complete."
echo "Please commit these changes before deploying, and ensure you rebuild the admin-panel container if deploying to staging."
