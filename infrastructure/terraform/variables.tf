variable "aws_region" {
  description = "The AWS region to deploy into"
  type        = string
  default     = "ap-south-1" # Mumbai (India) region for ultra-low latency UPI transactions
}

variable "environment" {
  description = "Deployment environment (e.g. dev, staging, prod)"
  type        = string
  default     = "prod"
}

variable "db_password" {
  description = "Master database password for RDS PostgreSQL"
  type        = string
  sensitive   = true
  default     = "ChangeMeInProduction123!"
}

variable "ecr_repository_url" {
  description = "AWS ECR base registry URL for container images"
  type        = string
  default     = "123456789012.dkr.ecr.ap-south-1.amazonaws.com/agenticupi"
}
