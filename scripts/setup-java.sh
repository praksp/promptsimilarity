#!/usr/bin/env bash
# Install and configure Java 17 and Maven for the Prompt Similarity platform
set -e

JAVA_VERSION=17
MAVEN_VERSION=3.9.6

case "$(uname -s)" in
  Darwin)
    if command -v brew >/dev/null 2>&1; then
      echo "Installing OpenJDK $JAVA_VERSION and Maven via Homebrew..."
      brew install openjdk@${JAVA_VERSION} maven
      echo "Add to your shell profile: export PATH=\"$(brew --prefix openjdk@${JAVA_VERSION})/bin:\$PATH\""
    else
      echo "Please install Homebrew or download OpenJDK $JAVA_VERSION and Maven from Adoptium/Maven."
      exit 1
    fi
    ;;
  Linux)
    if command -v apt-get >/dev/null 2>&1; then
      sudo apt-get update
      sudo apt-get install -y openjdk-17-jdk maven
    elif command -v yum >/dev/null 2>&1; then
      sudo yum install -y java-17-openjdk-devel maven
    else
      echo "Unsupported Linux package manager. Install openjdk-17 and Maven manually."
      exit 1
    fi
    ;;
  *)
    echo "Unsupported OS. Install Java $JAVA_VERSION and Maven manually."
    exit 1
    ;;
esac

java -version
mvn -version
echo "Java and Maven setup complete."
