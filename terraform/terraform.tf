terraform {
  required_version = ">= 1.11"

  backend "s3" {
    # bucket and region are supplied at init time:
    # terraform -chdir=terraform init -backend-config="bucket=${TERRAFORM_TFSTATE_S3_BUCKET}" -backend-config="region=${TERRAFORM_TFSTATE_S3_REGION}" -input=false
    key    = "mcp-server-lambda/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}
