# AWS Lambda with Okta MCP Authentication

An SSO Okta-authenticated MCP server, running on AWS lambda.

The goal of this project is to provide a template for a MCP-server with SSO integration deployed as an AWS lambda. It uses Java + Gradle for the server, Terraform for infrastructure, and bash scripts for deployment. The real value-add is the SSO authentication negotiation flow with Okta. It follows browser based authentication. After registering the mcp with an agent, the authentication process will open a browser at Okta to verify the user. It is a great starting point for an SSO authenticated MCP server when Server-Sent Events are not required. It provides a cost-effective way to deploy a serverless cloud MCP server.

This project has been tested with Anthropic's Claude, OpenAI's Codex, and is intended to work with any MCP client.

It also includes one Jira tool suitable for verifying MCP server functionality. 
This Jira tool is only an example. It acts as one user authenticating with a fixed api token configured at deployment. This Jira tool is not a suitable long term Jira tool.


# Setup
1) If not already available, create an S3 bucket to hold terraform state [create-tfstate-bucket.sh](https://github.com/martinstevengaray/bootstrap-utilities/blob/main/infra/create-tfstate-bucket.sh)
2) In Okta admin dashboard create new app using: OIDC Native Application with PKCE, and assign app to user.
3) Create new configuration script at: ./local/deployment-config.sh
```bash
# Okta
export OKTA_URL_PREFIX="<your okta url prefix>"
export OKTA_MCP_CLIENT_ID="<your okta mcp client id>"
export SYMMETRIC_SIGNING_KEY="<your symmetric signing key>" # openssl rand -base64 32
export OKTA_SCOPES="<your okta scopes>"
# Deployment
export TERRAFORM_TFSTATE_S3_BUCKET="<your terraform tfstate s3 bucket>"
export TERRAFORM_TFSTATE_S3_REGION="<your terraform tfstate s3 region>"
export DEPLOYMENT_REGION="<your deployment region>"
export LAMBDA_FUNCTION_NAME="<your lambda function name>"
# Jira user token for example tool
export JIRA_CLIENT_TOKEN="<your jira client token"
export JIRA_CLIENT_EMAIL="<your client email>"
export JIRA_CLOUDID="<your jira cloudId>" # curl -s "https://<jir-site-url>/_edge/tenant_info"
```
4) Deploy lambda and associated infrastructure with [deploy.sh](deploy.sh) -auto-approve
5) Deploy secrets with [deploy-secrets.sh](deploy-secrets.sh)
6) In Okta admin dashboard add `<function_url>/oath/callback` as the callback uri for the native app created in step 2. (function_url can be found in the output of deploy.sh)
7) Register MCP server with agent. `claude mcp add --transport http mcp-server-lambda <function_url>/mcp`
8) Ask agent to connect to mcp server `/mcp`, authenticate, and test

### optional setup for api-curl.sh and api-rpc-curl.sh
1) In Okta admin dashboard create new machine to machine application with id+secret.
2) Create new configuration script at: ./local/api-curl-config.sh
```bash
export OKTA_URL_PREFIX="<your okta url prefix>"
export OKTA_SCOPES="<your okta scopes>"
export OKTA_API_CLIENT_ID="<your okta api client id>"
export OKTA_API_CLIENT_SECRET="<your okta api client secret>"
export AWS_LAMBDA_URL="<your aws lambda url>"
```
3) use [api-curl.sh](api-curl.sh) and [api-rpc-curl.sh](api-rpc-curl.sh) for example access.
