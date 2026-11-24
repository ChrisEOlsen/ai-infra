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

variable "domain" {
  type = string
}
variable "instance_type" {
  type    = string
  default = "t3.large"
}
variable "data_volume_id" {
  type = string
}
variable "bucket" {
  type = string
}
variable "secret_arn" {
  type = string
}
variable "release_archive" {
  type = string
}
variable "release_sha256" {
  type = string
}
variable "backend_image" {
  type = string
}
variable "web_image" {
  type = string
}
variable "source_locations" {

  type = list(object({
    bucket = string, prefix = string
  }))
  default = []

}
variable "environment" {
  type = map(string)
  default = {

  }
}
variable "ingress_cidrs" {
  type    = list(string)
  default = ["0.0.0.0/0"]
}
data "aws_caller_identity" "current" {

}
data "aws_ssm_parameter" "ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}
resource "aws_vpc" "app" {
  cidr_block           = "10.67.0.0/16"
  enable_dns_hostnames = true
}
resource "aws_subnet" "app" {

  vpc_id                  = aws_vpc.app.id
  cidr_block              = "10.67.1.0/24"
  availability_zone       = var.availability_zone
  map_public_ip_on_launch = true

}
resource "aws_internet_gateway" "app" {
  vpc_id = aws_vpc.app.id
}
resource "aws_route_table" "app" {

  vpc_id = aws_vpc.app.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.app.id
  }

}
resource "aws_route_table_association" "app" {
  subnet_id      = aws_subnet.app.id
  route_table_id = aws_route_table.app.id
}
resource "aws_security_group" "app" {

  name_prefix = "company-rag-${var.customer}-"
  vpc_id      = aws_vpc.app.id
  # Public HTTPS application; no SSH, database, management, or collector ingress.
  dynamic "ingress" {

    for_each = [80, 443]
    content {
      from_port   = ingress.value
      to_port     = ingress.value
      protocol    = "tcp"
      cidr_blocks = var.ingress_cidrs
    }

  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

}
resource "aws_iam_role" "app" {

  name_prefix = "company-rag-${var.customer}-"
  assume_role_policy = jsonencode({
    Version = "2012-10-17", Statement = [{
      Effect = "Allow", Action = "sts:AssumeRole", Principal = {
        Service = "ec2.amazonaws.com"
      }
    }]
  })

}
resource "aws_iam_role_policy_attachment" "ssm" {

  role       = aws_iam_role.app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"

}
resource "aws_iam_role_policy" "app" {

  role = aws_iam_role.app.id
  policy = jsonencode({
    Version = "2012-10-17", Statement = concat([
      {
        Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = var.secret_arn
      },
      {
        Effect = "Allow", Action = ["s3:GetObject"], Resource = "arn:aws:s3:::${var.bucket}/releases/*"
      },
      {
        Effect = "Allow", Action = ["s3:PutObject", "s3:GetObject"], Resource = "arn:aws:s3:::${var.bucket}/backups/*"
      },
      {
        Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*"
      },
      {
        Effect = "Allow", Action = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"], Resource = "arn:aws:ecr:${var.region}:${data.aws_caller_identity.current.account_id}:repository/company-rag/${var.customer}/*"
      }
      ], flatten([for source in var.source_locations : [
        {
          Effect = "Allow", Action = ["s3:ListBucket"], Resource = "arn:aws:s3:::${source.bucket}", Condition = {
            StringLike = {
              "s3:prefix" = ["${source.prefix}*"]
            }
          }
        },
        {
          Effect = "Allow", Action = ["s3:GetObject"], Resource = "arn:aws:s3:::${source.bucket}/${source.prefix}*"
        }
    ]]))
  })

}
resource "aws_iam_instance_profile" "app" {
  role = aws_iam_role.app.name
}
resource "aws_s3_object" "release" {

  bucket      = var.bucket
  key         = "releases/${var.release_sha256}.tar.gz"
  source      = var.release_archive
  source_hash = var.release_sha256

}
locals {

  deployment = jsonencode({
    region = var.region, secret_arn = var.secret_arn, bucket = var.bucket, environment = merge(var.environment, {

      PUBLIC_URL        = "https://${var.domain}", SITE_ADDRESS = var.domain, COOKIE_SECURE = "true", HTTP_PORT = "80", HTTPS_PORT = "443", ENVIRONMENT = "production", DATA_ROOT = "/srv/company-rag/data", AWS_REGION = var.region,
      IMAGE_PULL_POLICY = "always", BACKEND_IMAGE = var.backend_image, WEB_IMAGE = var.web_image,
      S3_ALLOWLIST      = join(",", [for s in var.source_locations : "${s.bucket}/${s.prefix}"])

    })
  })

}
resource "aws_launch_template" "app" {

  name_prefix   = "company-rag-${var.customer}-"
  image_id      = data.aws_ssm_parameter.ami.value
  instance_type = var.instance_type
  iam_instance_profile {
    name = aws_iam_instance_profile.app.name
  }
  vpc_security_group_ids = [aws_security_group.app.id]
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }
  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      volume_size           = 30
      volume_type           = "gp3"
      encrypted             = true
      delete_on_termination = true
    }
  }
  credit_specification {
    cpu_credits = "standard"
  }
  user_data = base64encode(templatefile("${path.module}/cloud-init.sh.tftpl", {

    region        = var.region, bucket = var.bucket, release_key = aws_s3_object.release.key, checksum = var.release_sha256,
    volume_serial = replace(var.data_volume_id, "-", ""), deployment_b64 = base64encode(local.deployment)

  }))

}
resource "aws_instance" "app" {

  subnet_id = aws_subnet.app.id
  launch_template {
    id      = aws_launch_template.app.id
    version = aws_launch_template.app.latest_version
  }
  tags = {
    Name = "company-rag-${var.customer}"
  }
  depends_on = [aws_route_table_association.app, aws_iam_role_policy.app]

}
resource "aws_volume_attachment" "data" {

  device_name                    = "/dev/sdf"
  volume_id                      = var.data_volume_id
  instance_id                    = aws_instance.app.id
  stop_instance_before_detaching = true

}
resource "aws_eip" "app" {
  domain   = "vpc"
  instance = aws_instance.app.id
}
output "instance_id" {
  value = aws_instance.app.id
}
output "public_ip" {
  value = aws_eip.app.public_ip
}
output "url" {
  value = "https://${var.domain}"
}
output "customer" {
  value = var.customer
}
output "region" {
  value = var.region
}

variable "zone_id" {
  description = "Optional existing Route 53 hosted zone. Only the assistant record is managed."
  type        = string
  default     = null
}
resource "aws_route53_record" "assistant" {
  count           = var.zone_id == null ? 0 : 1
  zone_id         = var.zone_id
  name            = var.domain
  type            = "A"
  ttl             = 60
  records         = [aws_eip.app.public_ip]
  allow_overwrite = false
}
