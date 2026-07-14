source local/api-curl-config.sh

# Get an Okta access token via client_credentials.
TOKEN=$(curl -s "https://$OKTA_URL_PREFIX.okta.com/oauth2/default/v1/token" \
  -u "$OKTA_API_CLIENT_ID:$OKTA_API_CLIENT_SECRET" \
  -d "grant_type=client_credentials&scope=$OKTA_SCOPES" | jq -r .access_token)

# Send a JSON-RPC 2.0 request to the MCP endpoint. This is a stateless
# Streamable-HTTP MCP server: one JSON-RPC request per call, one JSON response.
# Override the method/params by passing a full JSON-RPC body as $1.
BODY=${1:-'{"jsonrpc":"2.0","id":1,"method":"tools/list"}'}

curl -s "$AWS_LAMBDA_URL/mcp" \
      -H "Authorization: Bearer $TOKEN" \
      -H "content-type: application/json" \
      -H "accept: application/json, text/event-stream" \
      -d "$BODY" | jq