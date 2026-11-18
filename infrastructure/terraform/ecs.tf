# ECS Cluster definition
resource "aws_ecs_cluster" "main" {
  name = "agenticupi-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled" # Live AWS CloudWatch performance monitoring
  }
}

# Security group for ECS Fargate Tasks
resource "aws_security_group" "ecs_tasks" {
  name        = "agenticupi-ecs-tasks-sg"
  description = "Controls inbound and outbound traffic for ECS Tasks"
  vpc_id      = aws_vpc.main.id

  # Ingress from ALB (Port 8080 for Java Backend, 8000 for Python Agent)
  ingress {
    protocol    = "tcp"
    from_port   = 8080
    to_port     = 8080
    cidr_blocks = ["10.0.0.0/16"]
  }

  ingress {
    protocol    = "tcp"
    from_port   = 8000
    to_port     = 8000
    cidr_blocks = ["10.0.0.0/16"]
  }

  ingress {
    protocol    = "tcp"
    from_port   = 50051
    to_port     = 50051
    cidr_blocks = ["10.0.0.0/16"] # gRPC internal communication
  }

  egress {
    protocol    = "-1"
    from_port   = 0
    to_port     = 0
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "agenticupi-ecs-tasks-sg"
  }
}

# 1. Java Backend Task Definition (Fargate)
resource "aws_ecs_task_definition" "backend" {
  family                   = "agenticupi-backend"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.ecs_execution_role.arn
  task_role_arn            = aws_iam_role.ecs_task_role.arn

  container_definitions = jsonencode([
    {
      name      = "backend-java"
      image     = "${var.ecr_repository_url}:backend-latest"
      essential = true
      portMappings = [
        {
          containerPort = 8080
          hostPort      = 8080
        }
      ]
      environment = [
        { name = "DB_HOST", value = aws_db_instance.postgres.address },
        { name = "DB_NAME", value = "agenticupi" },
        { name = "SPRING_PROFILES_ACTIVE", value = "prod" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = "/ecs/agenticupi-backend"
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }
    }
  ])
}

# 2. Python Agent Task Definition (Fargate)
resource "aws_ecs_task_definition" "agent" {
  family                   = "agenticupi-agent"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.ecs_execution_role.arn
  task_role_arn            = aws_iam_role.ecs_task_role.arn

  container_definitions = jsonencode([
    {
      name      = "python-agent"
      image     = "${var.ecr_repository_url}:agent-latest"
      essential = true
      portMappings = [
        {
          containerPort = 8000
          hostPort      = 8000
        },
        {
          containerPort = 50051
          hostPort      = 50051
        }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = "/ecs/agenticupi-agent"
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }
    }
  ])
}

# Fargate Service: Maintains desired count of running tasks
resource "aws_ecs_service" "backend" {
  name            = "backend-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.backend.arn
  desired_count   = 2 # High Availability
  launch_type     = "FARGATE"

  network_configuration {
    security_groups  = [aws_security_group.ecs_tasks.id]
    subnets          = [aws_subnet.private_1.id, aws_subnet.private_2.id]
    assign_public_ip = false
  }
}
