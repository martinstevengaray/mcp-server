# mcp-server

An Okta-authenticated MCP server, running on AWS lambda (without SSE support)
Provide one tool for Jira access.

# Setup
1) Create an S3 bucket to hold terraform state [create-tfstate-bucket.sh](https://github.com/martinstevengaray/bootstrap-utilities/blob/main/infra/create-tfstate-bucket.sh) if one does not already exist.
2) In Okta admin dashboard create new app using: OIDC Native Application with PKCE, and assign app to user.
3) Create new configuration script at: ./local/deployment-config.sh
```bash
export OKTA_URL_PREFIX="<your okta url prefix>"
export OKTA_WEB_CLIENT_ID="<your okta web client id>"
export OKTA_WEB_CLIENT_SECRET="<your okta web client secret>"
export OKTA_MCP_CLIENT_ID="<your okta mcp client id>"
export SYMMETRIC_SIGNING_KEY="<your symmetric singing key"> # generate with openssl rand -base64 32
export OKTA_SCOPES="<your okta scopes>"
export TERRAFORM_TFSTATE_S3_BUCKET="tfstate-346885780490" # AWS_ACCOUNT_ID="346885780490"
export TERRAFORM_TFSTATE_S3_BUCKET="<your terraform tfstate s3 bucket>"
export TERRAFORM_TFSTATE_S3_REGION="<your terraform tfstate s3 region>"
export DEPLOYMENT_REGION="<your deployment region>"
export LAMBDA_FUNCTION_NAME="<your lambda function name>"
# JIRA (Atlassian Cloud)
export JIRA_CLIENT_TOKEN="<your jira client token"
export JIRA_CLIENT_EMAIL="<your client email>"
export JIRA_CLOUDID="<your jira cloudId>" # curl -s "https://<jir-site-url>/_edge/tenant_info"
```
4) Deploy lambda and associated infrastructure with [deploy.sh](deploy.sh) -auto-approve
5) Deploy secrets with [deploy-secrets.sh](deploy-secrets.sh)
6) In Okta admin dashboard add the `<function_url>/callback` as the callback uri for the web app created in step 2. (function_url can be found in the output of deploy.sh)
7) register mcp server with claude 
```bash
claude mcp add --transport http mcp-server-lambda <function_url>/mcp
```
8) ask agent to connect mcp server with '/mcp', authenticate, and test


# WIP:

### optional setup for api-curl.sh and api-rpc-curl.sh
1) Via okta admin dashboard create new machine to machine application with id+secret.
2) Create new configuration script at: ./local/api-curl-config.sh
```bash
export OKTA_URL_PREFIX="<your okta url prefix>"
export OKTA_SCOPES="<your okta scopes>"
export OKTA_API_CLIENT_ID="<your okta api client id>"
export OKTA_API_CLIENT_SECRET="<your okta api client secret>"
export AWS_LAMBDA_URL="<your aws lambda url>"
```
3) use [api-curl.sh](api-curl.sh) and [api-rpc-curl.sh](api-rpc-curl.sh) for example access.
