variable "app_version" {
  description = "App version from the Gradle build (single source of truth in build.gradle). Pass it through: terraform apply -var \"app_version=$(cd .. && ./gradlew -q printVersion)\""
  type        = string
}

variable "aws_region" {
  description = "AWS region to deploy into"
  type        = string
}

variable "aws_lambda_function_name" {
  description = "Name of the Lambda function"
  type        = string
}

variable "okta_issuer" {
  description = "Okta custom authorization server issuer, e.g. https://<org>.okta.com/oauth2/default (org-server tokens are opaque and cannot be verified). No default — set TF_VAR_okta_issuer or terraform.tfvars."
  type        = string
}

variable "okta_audience" {
  description = "Expected aud claim of Okta access tokens"
  type        = string
  default     = "api://default"
}

variable "aws_lambda_cors_allow_origins" {
  description = "Origins allowed to call the function URL from a browser"
  type        = list(string)
  default     = ["http://localhost:8080"]
}

variable "okta_web_client_id" {
  description = "Client ID of the Okta Web Application app used for the browser OIDC flow (empty disables it)"
  type        = string
  default     = ""
}

variable "okta_scopes" {
  description = "Space-separated scopes the browser OIDC flow requests (must exist as custom scopes on the authorization server; empty disables the flow)"
  type        = string
  default     = ""
}

variable "okta_mcp_client_id" {
  description = "Client ID of a pre-registered Okta Native app (public client, PKCE). When set, the DCR shim (/register) hands this id to MCP clients so they can connect without knowing a client_id, and discovery advertises this server as the authorization server. Empty disables the shim. Not a secret."
  type        = string
  default     = ""
}

variable "jira_client_email" {
  description = "Atlassian account email used as the Basic-auth username for the Jira REST API. Set TF_VAR_jira_client_email or terraform.tfvars."
  type        = string
}

variable "jira_cloud_id" {
  description = "Atlassian Cloud id embedded in the Jira API base URL (https://api.atlassian.com/ex/jira/<cloud_id>/rest/api/3). Discover it via the jira skill's get-cloud-id.sh. Set TF_VAR_jira_cloud_id or terraform.tfvars."
  type        = string
}
