# Subnet group spanning both private availability zones
resource "aws_db_subnet_group" "rds" {
  name       = "agenticupi-db-subnet-group"
  subnet_ids = [aws_subnet.private_1.id, aws_subnet.private_2.id]

  tags = {
    Name = "agenticupi-db-subnet-group"
  }
}

# Security Group: Only allows incoming traffic from ECS Fargate containers on port 5432
resource "aws_security_group" "rds" {
  name        = "agenticupi-rds-sg"
  description = "Controls access to the PostgreSQL database"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "PostgreSQL from ECS tasks"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs_tasks.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "agenticupi-rds-sg"
  }
}

# Production RDS PostgreSQL 16 Instance
resource "aws_db_instance" "postgres" {
  identifier        = "agenticupi-postgres"
  engine            = "postgres"
  engine_version    = "16.1"
  instance_class    = "db.t4g.medium" # ARM Graviton2 for high cost-efficiency
  allocated_storage = 20
  max_allocated_storage = 100 # Auto-scaling storage up to 100GB

  db_name  = "agenticupi"
  username = "api_user"
  password = var.db_password # Injected securely via variable or AWS Secrets Manager

  db_subnet_group_name   = aws_db_subnet_group.rds.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  skip_final_snapshot    = true
  publicly_accessible    = false # STRICT: Never expose database to public internet

  storage_encrypted = true
  multi_az          = var.environment == "prod" ? true : false

  backup_retention_period = 7
  deletion_protection     = false

  tags = {
    Name = "agenticupi-rds-postgres"
  }
}
