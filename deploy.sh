#!/usr/bin/env bash
# Build the Lambda zip and deploy it. Extra args are passed to `terraform apply`
# (e.g. ./deploy.sh -auto-approve). One-time setup: see README "Deploy".
set -euo pipefail
cd "$(dirname "$0")"

./gradlew build
VERSION=$(./gradlew -q printVersion)

#load:
#  OKTA_URL_PREFIX
#  OKTA_WEB_CLIENT_ID  (client secret loaded via deploy.secrets.sh)
#  OKTA_MCP_CLIENT_ID
#  SYMMETRIC_SIGNING_KEY
#  OKTA_SCOPES
#  TERRAFORM_TFSTATE_S3_BUCKET
#  TERRAFORM_TFSTATE_S3_REGION
#  DEPLOYMENT_REGION
#  LAMBDA_FUNCTION_NAME
source local/deployment-config.sh

export TF_VAR_okta_issuer="https://${OKTA_URL_PREFIX}.okta.com/oauth2/default"
export TF_VAR_okta_web_client_id="${OKTA_WEB_CLIENT_ID}"
export TF_VAR_okta_scopes="${OKTA_SCOPES}"
# Pre-registered Native app id handed out by the DCR shim (empty disables it).
export TF_VAR_okta_mcp_client_id="${OKTA_MCP_CLIENT_ID}"
export TF_VAR_jira_client_email="${JIRA_CLIENT_EMAIL}"
export TF_VAR_jira_cloud_id="${JIRA_CLOUDID}"
export TF_VAR_aws_lambda_function_name="${LAMBDA_FUNCTION_NAME}"

# Skipped once initialized — if the backend or providers change, delete terraform/.terraform to re-init.
if [ ! -d terraform/.terraform ]; then
  terraform -chdir=terraform init -backend-config="bucket=${TERRAFORM_TFSTATE_S3_BUCKET}" -backend-config="region=${TERRAFORM_TFSTATE_S3_REGION}" -input=false
fi

terraform -chdir=terraform apply -var "app_version=$VERSION" -var "aws_region=$DEPLOYMENT_REGION" "$@"
