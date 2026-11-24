terraform {

  required_version = ">= 1.10, < 2.0"
  required_providers {
    aws = {
      source = "hashicorp/aws", version = "~> 6.0"
    }
  }
  backend "local" {

  }

}
provider "aws" {

  region = var.region
  default_tags {
    tags = {
      Project = "company-rag", Customer = var.customer, ManagedBy = "terraform"
    }
  }

}
variable "region" {
  type = string
}
variable "customer" {

  type = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,24}$", var.customer))
    error_message = "Use a customer slug of 3–25 lowercase letters, numbers and hyphens."
  }

}
variable "availability_zone" {
  type = string
}

variable "data_size_gb" {
  type    = number
  default = 50
}
resource "aws_ebs_volume" "data" {

  availability_zone = var.availability_zone
  type              = "gp3"
  size              = var.data_size_gb
  encrypted         = true
  tags = {
    Name = "company-rag-${var.customer}-data"
  }

}
resource "aws_s3_bucket" "owned" {

  bucket_prefix = "company-rag-${var.customer}-"
  force_destroy = false

}
resource "aws_s3_bucket_public_access_block" "owned" {

  bucket                  = aws_s3_bucket.owned.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true

}
resource "aws_s3_bucket_versioning" "owned" {

  bucket = aws_s3_bucket.owned.id
  versioning_configuration {
    status = "Enabled"
  }

}
resource "aws_s3_bucket_server_side_encryption_configuration" "owned" {

  bucket = aws_s3_bucket.owned.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }

}
resource "aws_s3_bucket_policy" "tls" {

  bucket = aws_s3_bucket.owned.id
  policy = jsonencode({
    Version = "2012-10-17", Statement = [{
      Effect = "Deny", Principal = "*", Action = "s3:*", Resource = [aws_s3_bucket.owned.arn, "${aws_s3_bucket.owned.arn}/*"], Condition = {
        Bool = {
          "aws:SecureTransport" = "false"
        }
      }
    }]
  })

}
resource "aws_secretsmanager_secret" "runtime" {

  name_prefix             = "company-rag/${var.customer}/"
  recovery_window_in_days = 7

}
resource "aws_ecr_repository" "backend" {

  name                 = "company-rag/${var.customer}/backend"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }

}
resource "aws_ecr_repository" "web" {

  name                 = "company-rag/${var.customer}/web"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }

}
output "volume_id" {
  value = aws_ebs_volume.data.id
}
output "bucket" {
  value = aws_s3_bucket.owned.id
}
output "secret_arn" {
  value = aws_secretsmanager_secret.runtime.arn
}
output "backend_repository" {
  value = aws_ecr_repository.backend.repository_url
}
output "web_repository" {
  value = aws_ecr_repository.web.repository_url
}

output "customer" { value = var.customer }
