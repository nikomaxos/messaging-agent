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

# Bump pom.xml versions
for pom_xml in services/routing-engine/pom.xml services/core-service/pom.xml services/smpp-edge/pom.xml services/rcs-mautrix/pom.xml services/device-gateway/pom.xml; do
  if [ -f "$pom_xml" ]; then
    # We only want to replace the first <version> tag which is the project version, not dependencies
    sed -i "0,/<version>.*<\/version>/s/<version>.*<\/version>/<version>$NEW_VERSION<\/version>/" "$pom_xml"
    echo "Updated $pom_xml"
  fi
done

echo "Version bump complete."
echo "Please commit these changes before deploying, and ensure you rebuild the admin-panel container if deploying to staging."
