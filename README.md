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
export OKTA_MCP_CLIENT_ID="..."             # optional: Native app id for the DCR shim (see below)
export SYMMETRIC_SIGNING_KEY="..."          # optional: HMAC key for round-trip signing, used by the OAuth proxy (see C); openssl rand -base64 32

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

## Connecting an MCP client (OAuth)

MCP clients (Claude Code, Cursor, MCP Inspector, …) don't paste a bearer token — they run
the OAuth handshake. On a `401` from `/mcp` the client reads the `WWW-Authenticate` header,
fetches `/.well-known/oauth-protected-resource`, discovers the authorization server, then
does an authorization-code + PKCE browser sign-in. The discovery endpoints make this work;
the only open question is **how the client gets an OAuth client_id**, since **Okta has no
anonymous Dynamic Client Registration**. Two models:

### A. Pre-registered client_id (default, `OKTA_MCP_CLIENT_ID` unset)

Register a Native app in Okta and hand each MCP client its `client_id`. Discovery points
straight at Okta. For Claude Code, in `~/.claude.json`:

```json
"jira": {
  "type": "http",
  "url": "<functionUrl>/mcp",
  "oauth": { "clientId": "<okta-native-client-id>", "callbackPort": 3118 }
}
```

### B. DCR shim (`OKTA_MCP_CLIENT_ID` set)

Many clients only do Dynamic Client Registration and give you nowhere to type a client_id.
The shim satisfies them: the Lambda advertises itself as the authorization server and
exposes `/register`, which **ignores the request and returns the pre-registered Native app's
`client_id`** (no Okta call, no secret). The client then does PKCE against Okta as usual, so
it connects with just the URL.

Set it up:
1. Create an Okta **Native** app (public client): Authorization Code + Refresh Token grants,
   PKCE, `token_endpoint_auth_method = none`. This is the right type for MCP clients — a
   confidential *Web* app would require a client secret at the token endpoint and reject the
   secretless PKCE flow. Keep it separate from `OKTA_WEB_CLIENT_ID` (which serves the app's
   own browser SSO and stays a Web app).
2. On that Native app, register **each client's redirect URI** (e.g. Claude Code's
   `http://localhost:3118/callback`). The shim can't add redirect URIs — Okta must already
   have them.
3. Add the Native app to the default AS access-policy rule (Authorization Code grant + the
   scopes incl. `offline_access`) and assign your user.
4. `export OKTA_MCP_CLIENT_ID="<native app client id>"` in `local/config.sh`, then
   `./deploy.sh`. Remove the `clientId` line from `~/.claude.json` so the client registers
   through the shim.

Note `/register` is public (the OAuth bootstrap must be), but it only echoes a static
client_id — it holds no secret and makes no Okta calls, so there's nothing to abuse beyond
handing out an id that's useless without a valid Okta sign-in.

The shim's catch is step B.2: because every client shares one Native app, each client's
loopback redirect URI must be registered on it by hand. Model C removes that.

### C. OAuth proxy

Instead of registering each client's redirect URI in Okta, the Lambda **fronts Okta as the
authorization server**. Okta only ever sees *our* fixed redirect (`/oauth/callback`), so any
client's loopback redirect is honored without pre-registering it — and still with one shared
Native app, no per-client registration, and no Okta admin credentials.

Discovery advertises our own `/authorize` and `/token`. The flow:
1. **`/authorize`** — the client arrives with its loopback `redirect_uri` + PKCE
   `code_challenge`. We redirect to Okta using our `/oauth/callback` and smuggle the client's
   `redirect_uri`/`state` into a signed `state`.
2. **`/oauth/callback`** — Okta redirects here; we redirect to the client's loopback URI with
   Okta's `code` and the client's original `state`.
3. **`/token`** — the client posts the `code` + its `code_verifier`; we forward to Okta,
   swapping in our `/oauth/callback` (what the code was bound to), and return Okta's tokens
   verbatim. PKCE stays end-to-end — the `code_verifier` only passes through us.

Two guards on the redirect-bearing `/oauth/callback` prevent it becoming an open redirector:
the `state` is **HMAC-signed** (so the smuggled redirect can't be tampered with) and the
client redirect is **allowlisted to loopback** (`localhost` / `127.0.0.1` / `::1`, per RFC 8252).

Set it up (builds on B's Native app — no extra Okta objects):
1. Do B.1 and B.3 (create the Native app, add it to the AS access policy, assign your user).
   **Skip B.2** — you no longer register per-client redirect URIs.
2. On the Native app, register the **one** redirect URI `https://<functionUrl>/oauth/callback`.
3. `export SYMMETRIC_SIGNING_KEY="$(openssl rand -base64 32)"` (plus `OKTA_MCP_CLIENT_ID`)
   in `local/config.sh`, then `./deploy.sh` and `./deploy_secrets.sh` (pushes the key to SSM).

Trade-off vs. real per-client registration: all clients share one Okta app, so you lose
per-client audit/revocation in Okta — fine for a single team, and it needs zero admin creds.
