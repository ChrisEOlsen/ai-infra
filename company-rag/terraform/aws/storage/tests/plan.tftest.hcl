mock_provider "aws" {}
variables {
  region            = "us-east-1"
  customer          = "test-customer"
  availability_zone = "us-east-1a"
}
run "retained_storage" {
  command = plan
  assert {
    condition     = aws_ebs_volume.data.encrypted
    error_message = "Customer data volume must be encrypted."
  }
  assert {
    condition     = !aws_s3_bucket.owned.force_destroy
    error_message = "Terraform must not silently empty backups."
  }
  assert {
    condition     = aws_s3_bucket_public_access_block.owned.block_public_policy
    error_message = "Backups must remain private."
  }
  assert {
    condition     = aws_s3_bucket_versioning.owned.versioning_configuration[0].status == "Enabled"
    error_message = "Backup objects must be versioned."
  }
}
