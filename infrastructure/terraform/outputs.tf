output "rds_endpoint" {
  description = "The connection endpoint for the AWS RDS PostgreSQL database"
  value       = aws_db_instance.postgres.endpoint
}

output "s3_bucket_name" {
  description = "The S3 bucket for storing transaction receipts"
  value       = aws_s3_bucket.receipts.bucket
}

output "ecs_cluster_name" {
  description = "The ECS Cluster name"
  value       = aws_ecs_cluster.main.name
}
