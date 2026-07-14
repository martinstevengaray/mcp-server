#!/usr/bin/env bash
# Push secrets from local/deployment-config.sh to SSM Parameter Store
set -euo pipefail
cd "$(dirname "$0")"

source local/deployment-config.sh

# Push a secret value into an existing SSM SecureString parameter, only writing when the value changes
push_secret() {
  local param_name="$1" value="$2"
  local current
  if ! current=$(aws ssm get-parameter --name "$param_name" --with-decryption \
    --region "$DEPLOYMENT_REGION" --query Parameter.Value --output text 2>/dev/null); then
    echo "Parameter $param_name not found — run ./deploy.sh first (terraform creates it)." >&2
    exit 1
  fi
  if [ "$current" = "$value" ]; then
    echo "$param_name already up to date."
    return
  fi
  aws ssm put-parameter --name "$param_name" --type SecureString --overwrite \
    --region "$DEPLOYMENT_REGION" --value "$value" > /dev/null
  echo "$param_name updated."
}

if [ -n "${OKTA_WEB_CLIENT_SECRET:-}" ]; then
  push_secret "/${LAMBDA_FUNCTION_NAME}/okta-web-client-secret" "$OKTA_WEB_CLIENT_SECRET"
else
  echo "OKTA_WEB_CLIENT_SECRET is empty — skipping (browser flow disabled)."
fi

if [ -n "${JIRA_CLIENT_TOKEN:-}" ]; then
  push_secret "/${LAMBDA_FUNCTION_NAME}/jira-client-token" "$JIRA_CLIENT_TOKEN"
else
  echo "JIRA_CLIENT_TOKEN is empty — skipping (Jira MCP tools will fail without it)."
fi

push_secret "/${LAMBDA_FUNCTION_NAME}/symmetric-signing-key" "$SYMMETRIC_SIGNING_KEY"
