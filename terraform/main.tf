provider "aws" {
  region = var.aws_region
}

locals {
  lambda_zip = "${path.module}/../build/distributions/mcp-server-lambda-${var.app_version}.zip"
}

# Latest AWS Parameters and Secrets Lambda Extension layer for this region,
# published by AWS as a public parameter (x86_64 variant).
data "aws_ssm_parameter" "secrets_extension" {
  name = "/aws/service/aws-parameters-and-secrets-lambda-extension/x86/latest"
}

# SecureString parameters use the account's AWS-managed SSM key.
data "aws_kms_alias" "ssm" {
  name = "alias/aws/ssm"
}

# Terraform owns this parameter's existence, not its value: the real client
# secret is set out-of-band so it never enters terraform state (see README).
#   aws ssm put-parameter --name <name> --type SecureString --overwrite --value <secret>
resource "aws_ssm_parameter" "okta_web_client_secret" {
  name  = "/${var.aws_lambda_function_name}/okta-web-client-secret"
  type  = "SecureString"
  value = "placeholder - set the real value with aws ssm put-parameter"

  lifecycle {
    ignore_changes = [value]
  }
}

# Same out-of-band pattern as the Okta secret: terraform owns the parameter's
# existence, the real Jira API token is pushed by ./deploy_secrets.sh.
resource "aws_ssm_parameter" "jira_client_token" {
  name  = "/${var.aws_lambda_function_name}/jira-client-token"
  type  = "SecureString"
  value = "placeholder - set the real value with aws ssm put-parameter"

  lifecycle {
    ignore_changes = [value]
  }
}

resource "aws_iam_role_policy" "read_secrets" {
  name = "${var.aws_lambda_function_name}-read-secrets"
  role = aws_iam_role.lambda.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = "ssm:GetParameter"
        Resource = [
          aws_ssm_parameter.okta_web_client_secret.arn,
          aws_ssm_parameter.jira_client_token.arn
        ]
      },
      {
        Effect   = "Allow"
        Action   = "kms:Decrypt"
        Resource = data.aws_kms_alias.ssm.target_key_arn
      }
    ]
  })
}

resource "aws_iam_role" "lambda" {
  name = "${var.aws_lambda_function_name}-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "basic_execution" {
  role       = aws_iam_role.lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_lambda_function" "this" {
  function_name = var.aws_lambda_function_name
  role          = aws_iam_role.lambda.arn
  runtime       = "java21"
  handler       = "com.mgaray.oktaapp.McpServerLambda::handleRequest"

  filename         = local.lambda_zip
  source_code_hash = filebase64sha256(local.lambda_zip)

  memory_size = 512
  timeout     = 30

  # Serves SSM parameters to the function over localhost HTTP (with caching),
  # so the code needs no AWS SDK to read the client secret.
  layers = [data.aws_ssm_parameter.secrets_extension.insecure_value]

  environment {
    variables = {
      OKTA_ISSUER                              = var.okta_issuer
      OKTA_AUDIENCE                            = var.okta_audience
      OKTA_WEB_CLIENT_ID                       = var.okta_web_client_id
      OKTA_WEB_CLIENT_SECRET_SSM_PARAMETER_KEY = aws_ssm_parameter.okta_web_client_secret.name
      OKTA_SCOPES                              = var.okta_scopes
      JIRA_CLIENT_EMAIL                        = var.jira_client_email
      JIRA_CLOUD_ID                            = var.jira_cloud_id
      JIRA_CLIENT_TOKEN_SSM_PARAMETER_KEY      = aws_ssm_parameter.jira_client_token.name
      # Empty => DCR shim off; the /register endpoint then returns 404.
      OKTA_MCP_CLIENT_ID = var.okta_mcp_client_id
    }
  }
}

resource "aws_lambda_function_url" "this" {
  function_name      = aws_lambda_function.this.function_name
  authorization_type = "NONE"

  cors {
    allow_origins = var.aws_lambda_cors_allow_origins
    allow_methods = ["*"]
    allow_headers = ["authorization", "content-type"]
    max_age       = 3600
  }
}

resource "aws_lambda_permission" "public_url" {
  statement_id           = "AllowPublicFunctionUrl"
  action                 = "lambda:InvokeFunctionUrl"
  function_name          = aws_lambda_function.this.function_name
  principal              = "*"
  function_url_auth_type = "NONE"
}
