#!/bin/bash
set -euo pipefail

# Only run in Claude Code remote environment
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# Check if https_proxy is set
if [ -z "${https_proxy:-}" ]; then
  echo "No https_proxy set, skipping Maven proxy configuration"
  exit 0
fi

echo "Configuring Maven proxy for Claude Code on the web..."

# Parse proxy URL: http://username:password@host:port
# Example: http://container_xxx:jwt_yyy@21.0.0.189:15004
PROXY_URL="${https_proxy}"

# Remove http:// prefix
PROXY_URL="${PROXY_URL#http://}"
PROXY_URL="${PROXY_URL#https://}"

# Extract credentials (username:password) and host:port
if [[ "$PROXY_URL" == *"@"* ]]; then
  CREDENTIALS="${PROXY_URL%@*}"
  HOST_PORT="${PROXY_URL##*@}"

  # Split credentials into username and password
  PROXY_USER="${CREDENTIALS%%:*}"
  PROXY_PASS="${CREDENTIALS#*:}"
else
  HOST_PORT="$PROXY_URL"
  PROXY_USER=""
  PROXY_PASS=""
fi

# Split host and port
PROXY_HOST="${HOST_PORT%:*}"
PROXY_PORT="${HOST_PORT##*:}"

echo "Proxy host: $PROXY_HOST"
echo "Proxy port: $PROXY_PORT"
echo "Proxy user: ${PROXY_USER:0:20}..."

# Create Maven settings directory
mkdir -p /root/.m2

# Generate Maven settings.xml with proxy configuration
cat > /root/.m2/settings.xml << EOF
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <proxies>
    <proxy>
      <id>https-proxy</id>
      <active>true</active>
      <protocol>https</protocol>
      <host>${PROXY_HOST}</host>
      <port>${PROXY_PORT}</port>
      <username>${PROXY_USER}</username>
      <password>${PROXY_PASS}</password>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
    <proxy>
      <id>http-proxy</id>
      <active>true</active>
      <protocol>http</protocol>
      <host>${PROXY_HOST}</host>
      <port>${PROXY_PORT}</port>
      <username>${PROXY_USER}</username>
      <password>${PROXY_PASS}</password>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
  </proxies>
</settings>
EOF

echo "Created /root/.m2/settings.xml with proxy configuration"

# Set MAVEN_OPTS to use wagon transport (handles proxy auth correctly)
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo 'export MAVEN_OPTS="-Dmaven.resolver.transport=wagon"' >> "$CLAUDE_ENV_FILE"
  echo "Added MAVEN_OPTS to environment file"
fi

echo "Maven proxy configuration complete!"
