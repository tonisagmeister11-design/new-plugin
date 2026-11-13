#!/bin/sh
# Baut das Plugin ohne Maven (Fallback). Benötigt JAVA_HOME mit Java 25 und die Paper-API im lokalen Maven-Repo.
set -e
cd "$(dirname "$0")"
CP=$(find ~/.m2/repository -name "*.jar" | grep -vE "maven|plexus|sisu|guice" | tr '\n' ':')
rm -rf ./build/classes
mkdir -p ./build/classes
"$JAVA_HOME/bin/javac" --release 25 -nowarn -d ./build/classes -cp "$CP" $(find src/main/java -name "*.java")
cp -r src/main/resources/* ./build/classes/
"$JAVA_HOME/bin/jar" cf ./build/WeltKarte-1.0.0.jar -C ./build/classes .
echo "OK: build/WeltKarte-1.0.0.jar"
