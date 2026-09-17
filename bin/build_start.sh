#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR=$(dirname "$SCRIPT_DIR")
cd "$PROJECT_DIR"
mvn clean package -DskipTests
java -jar target/auto-deploy.jar
