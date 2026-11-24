mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_data "aws_ssm_parameter" {
    defaults = { value = "ami-0123456789abcdef0" }
  }
}
variables {
  region            = "us-east-1"
  customer          = "test-customer"
  availability_zone = "us-east-1a"
  domain            = "assistant.example.com"
  data_volume_id    = "vol-0123456789abcdef0"
  bucket            = "company-rag-owned-test"
  secret_arn        = "arn:aws:secretsmanager:us-east-1:123456789012:secret:company-rag/test"
  release_archive   = "tests/plan.tftest.hcl"
  release_sha256    = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  backend_image     = "123456789012.dkr.ecr.us-east-1.amazonaws.com/company-rag/test/backend:v1"
  web_image         = "123456789012.dkr.ecr.us-east-1.amazonaws.com/company-rag/test/web:v1"
  source_locations  = [{ bucket = "customer-source", prefix = "knowledge/" }]
}
run "secure_plan" {
  command = plan
  assert {
    condition     = aws_launch_template.app.metadata_options[0].http_tokens == "required"
    error_message = "IMDSv2 must be mandatory."
  }
  assert {
    condition     = aws_launch_template.app.block_device_mappings[0].ebs[0].encrypted
    error_message = "Root storage must be encrypted."
  }
  assert {
    condition     = alltrue([for rule in aws_security_group.app.ingress : contains([80, 443], rule.from_port)])
    error_message = "Only web ingress may be exposed."
  }
  assert {
    condition     = strcontains(local.deployment, "/srv/company-rag/data")
    error_message = "Persistent bind mounts must use the retained volume."
  }
  assert {
    condition     = !strcontains(aws_iam_role_policy.app.policy, "s3:DeleteObject")
    error_message = "Application must not delete source objects."
  }
}
