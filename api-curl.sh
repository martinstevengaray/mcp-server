
#from okat admin:
# add scope (security -> api -> add scopo) -get scope from here
# add application (applicationa -> applications -> Create App Integration) -get clientId and client_secret from here

#OKTA_URL_PREFIX="okta_url_prefix"
#OKTA_API_CLIENT_ID="client_id"
#OKTA_API_CLIENT_SECRET="client-secret"
#AWS_LAMBDA_URL="lambda url"
#OKTA_SCOPES="scope"
source local/api-curl-config.sh

TOKEN=$(curl -s "https://$OKTA_URL_PREFIX.okta.com/oauth2/default/v1/token" \
  -u "$OKTA_API_CLIENT_ID:$OKTA_API_CLIENT_SECRET" \
  -d "grant_type=client_credentials&scope=$OKTA_SCOPES" | jq -r .access_token)

curl -s "$AWS_LAMBDA_URL/hello?who=world" \
      -H "Authorization: Bearer $TOKEN" \
      -H "content-type: application/json" \
      -d '{"greeting": "hi from curl"}' | jq