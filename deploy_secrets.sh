#!/usr/bin/env bash
# Push secrets from local/config.sh to SSM Parameter Store: the Okta web app
# client secret and the Jira API token. Run after the first ./deploy.sh
# (terraform creates the parameter shells) and again whenever a secret rotates.
set -euo pipefail
cd "$(dirname "$0")"

source local/config.sh

# Push a secret value into an existing SSM SecureString parameter, only writing
# when the value changed so parameter versions stay meaningful. Terraform owns
# the parameter's existence; creating it here would make the first apply fail
# with ParameterAlreadyExists.
push_secret() {
  local param_name="$1" value="$2"
  local current
  if ! current=$(aws ssm get-parameter --name "$param_name" --with-decryption \
    --query Parameter.Value --output text 2>/dev/null); then
    echo "Parameter $param_name not found — run ./deploy.sh first (terraform creates it)." >&2
    exit 1
  fi
  if [ "$current" = "$value" ]; then
    echo "$param_name already up to date."
    return
  fi
  aws ssm put-parameter --name "$param_name" --type SecureString --overwrite \
    --value "$value" > /dev/null
  echo "$param_name updated."
}

# Must match terraform: /<aws_lambda_function_name>/<name>
if [ -n "${OKTA_WEB_CLIENT_SECRET:-}" ]; then
  push_secret "/mcp-server-lambda/okta-web-client-secret" "$OKTA_WEB_CLIENT_SECRET"
else
  echo "OKTA_WEB_CLIENT_SECRET is empty — skipping (browser flow disabled)."
fi

if [ -n "${JIRA_CLIENT_TOKEN:-}" ]; then
  push_secret "/mcp-server-lambda/jira-client-token" "$JIRA_CLIENT_TOKEN"
else
  echo "JIRA_CLIENT_TOKEN is empty — skipping (Jira MCP tools will fail without it)."
fi

# Symmetric (HMAC) key for signing round-trip values (MCP OAuth proxy `state`). Any
# high-entropy random string, e.g.: export SYMMETRIC_SIGNING_KEY="$(openssl rand -base64 32)"
push_secret "/mcp-server-lambda/symmetric-signing-key" "$SYMMETRIC_SIGNING_KEY"
