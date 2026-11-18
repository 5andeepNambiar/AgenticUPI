# S3 Bucket for Immutable Transaction Receipts and Mandate PDFs
resource "aws_s3_bucket" "receipts" {
  bucket = "agenticupi-receipts-${var.environment}"

  tags = {
    Name        = "agenticupi-receipts"
    Purpose     = "Audit and Transaction Receipts Storage"
  }
}

# 1. Enable Versioning (Immutable audit trail)
resource "aws_s3_bucket_versioning" "receipts" {
  bucket = aws_s3_bucket.receipts.id
  versioning_configuration {
    status = "Enabled"
  }
}

# 2. Server-Side Encryption (AES256 / KMS)
resource "aws_s3_bucket_server_side_encryption_configuration" "receipts" {
  bucket = aws_s3_bucket.receipts.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 3. Block All Public Access (Fintech compliance requirement)
resource "aws_s3_bucket_public_access_block" "receipts" {
  bucket = aws_s3_bucket.receipts.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 4. Lifecycle Rule: Archive older receipts to S3 Glacier for cost optimization
resource "aws_s3_bucket_lifecycle_configuration" "receipts" {
  bucket = aws_s3_bucket.receipts.id

  rule {
    id     = "archive-old-receipts"
    status = "Enabled"

    transition {
      days          = 90
      storage_class = "STANDARD_IA"
    }

    transition {
      days          = 365
      storage_class = "GLACIER"
    }
  }
}
