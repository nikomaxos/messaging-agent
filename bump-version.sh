#!/bin/bash
set -e

if [ -z "$1" ]; then
    echo "Usage: ./bump-version.sh <new-version>"
    echo "Example: ./bump-version.sh 2.3.0"
    exit 1
fi

NEW_VERSION=$1

echo "========================================="
echo " Bumping all microservices to v$NEW_VERSION"
echo "========================================="

# Node.js Projects
NODE_PROJECTS=(
    "admin-panel"
    "deploy-agent"
    "services/api-gateway"
    "services/prefix-updater"
)

echo ""
echo "--- Bumping Node.js Projects ---"
for proj in "${NODE_PROJECTS[@]}"; do
    if [ -d "$proj" ]; then
        echo "Bumping $proj..."
        cd "$proj"
        npm version "$NEW_VERSION" --no-git-tag-version --allow-same-version
        cd - > /dev/null
    else
        echo "Warning: Directory $proj not found, skipping."
    fi
done

# Java Spring Boot Projects
JAVA_PROJECTS=(
    "services/core-service"
    "services/routing-engine"
    "services/smpp-edge"
    "services/rcs-mautrix"
    "services/device-gateway"
)

echo ""
echo "--- Bumping Java Spring Boot Projects ---"
for proj in "${JAVA_PROJECTS[@]}"; do
    if [ -d "$proj" ]; then
        echo "Bumping $proj..."
        cd "$proj"
        sed -i "s/^    <version>.*<\/version>/    <version>$NEW_VERSION<\/version>/" pom.xml
        cd - > /dev/null
    else
        echo "Warning: Directory $proj not found, skipping."
    fi
done

echo ""
echo "--- Bumping Android App ---"
if [ -d "android-app" ]; then
    echo "Bumping android-app/app..."
    sed -i "s/versionName = \".*\"/versionName = \"$NEW_VERSION\"/" android-app/app/build.gradle.kts
    echo "Bumping android-app/guardian..."
    sed -i "s/versionName = \".*\"/versionName = \"$NEW_VERSION\"/" android-app/guardian/build.gradle.kts
else
    echo "Warning: Directory android-app not found, skipping."
fi

echo ""
echo "========================================="
echo " Successfully bumped system to v$NEW_VERSION!"
echo " Please commit the changes:"
echo " git add -A && git commit -m \"chore: bump version to $NEW_VERSION\""
echo "========================================="
