# mcp-server

An Okta-authenticated AWS Lambda (behind a Function URL) that serves a **Jira MCP
server** — a Model Context Protocol endpoint whose tools read and write Jira issues.
Requests are gated by an Okta JWT; the MCP layer is a hand-rolled JSON-RPC / Streamable
HTTP handler (`mcp/McpHandler`) delegating to a Jira REST client (`jira/JiraDelegate`).

## MCP tools

`list_my_issues`, `search_issues`, `get_issue`, `create_issue`, `add_comment`,
`transition_issue`. They call the Atlassian Cloud REST API v3 with Basic auth
(email + API token) — the same access the `claude-skills/jira` skill uses.

## Configuration

Set these in the git-ignored `local/config.sh` (sourced by the deploy scripts):

```sh
export AWS_ACCOUNT_ID="..."                 # tfstate backend bucket: tfstate-<AWS_ACCOUNT_ID>

# Okta — deploy.sh derives the TF_VAR_* inputs from these raw values
export OKTA_URL_PREFIX="<org>"              # -> issuer https://<org>.okta.com/oauth2/default
export OKTA_WEB_CLIENT_ID="..."             # optional: enables the browser OIDC flow
export OKTA_SCOPES="..."                    # optional
export OKTA_WEB_CLIENT_SECRET="..."         # pushed to SSM by deploy_secrets.sh

# Jira — email/token come from the claude-skills/jira local config (JIRA_EMAIL / JIRA_TOKEN there)
export JIRA_CLIENT_EMAIL="you@example.com"  # -> TF_VAR_jira_client_email
export JIRA_CLOUDID="<cloud-uuid>"          # -> TF_VAR_jira_cloud_id; discover via jira skill's get-cloud-id.sh
export JIRA_CLIENT_TOKEN="<atlassian-api-token>"  # pushed to SSM by deploy_secrets.sh
```

## Quick start

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./deploy.sh                                 # gradle build + terraform apply
# add <functionUrl>/callback as a "Sign-in redirect URI" in okta-admin (browser flow only)
./deploy_secrets.sh                         # push OKTA_WEB_CLIENT_SECRET + JIRA_CLIENT_TOKEN to SSM
```

Secrets are deliberately kept out of terraform state: terraform creates the SSM
parameter shells, and `./deploy_secrets.sh` pushes the real values (re-run on rotation).

## Calling the MCP endpoint

Get an Okta access token via the `client_credentials` flow (see `client-curl.sh`), then
POST JSON-RPC to `<functionUrl>/mcp`:

```sh
curl -s "$AWS_LAMBDA_URL/mcp" \
  -H "Authorization: Bearer $TOKEN" \
  -H "content-type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq
```

`tools/call` invokes a tool, e.g.:

```sh
curl -s "$AWS_LAMBDA_URL/mcp" \
  -H "Authorization: Bearer $TOKEN" -H "content-type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
       "arguments":null,"params":{"name":"list_my_issues","arguments":{}}}' | jq
```

A missing/invalid bearer token returns a JSON `401` (not the browser redirect used by
the app's other paths).
