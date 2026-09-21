# AgenticUPI - Autonomous AI Micropayments Architecture

A production-grade, distributed systems architecture demonstrating secure, low-latency agentic payments.

## Architecture Highlights
- **Zone 0 (Untrusted)**: The AI Agent (`agent-python`) running an LLM orchestrator. Uses strict Pydantic constraints and HMAC-SHA256 signing of the raw byte payload. Uses global connection pooling and deterministic retries (only on network timeouts, preserving the idempotency key).
- **Zone 1 (Core Authority)**: The Payment Switch (`backend-java`). Built on Spring Boot, MariaDB, and Redis. Features constant-time HMAC validation, double-entry ledger, pessimistic + optimistic concurrency control, and transactional outbox pattern using `SKIP LOCKED`.
- **Zone 2 (Control Plane)**: The User Dashboard (`frontend-angular`). High-performance View-Model (`vm$`) pattern with `ChangeDetectionStrategy.OnPush`. Secure pessimistic updates.

## How to Run

1. **Spin up Infrastructure (and services if enabled)**
   ```bash
   docker-compose up -d
   ```
   *Note: MariaDB, Kafka, Zookeeper, and Redis will start.*

2. **Run the Java Backend**
   ```bash
   cd backend-java
   ./mvnw spring-boot:run
   # Or using Docker: docker-compose up --build
   ```

3. **Run the Python Agent**
   ```bash
   cd agent-python
   pip install -r requirements.txt
   uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
   ```

4. **Run the Angular Frontend**
   ```bash
   cd frontend-angular
   npm install
   npm start
   ```

## Key Mechanisms & "Interview Signals"
- **Double IDEMPOTENCY check**: Fast check via Redis `setIfAbsent` (TTL 48h), backed by hard MariaDB `UNIQUE(idempotency_key, agent_id)` index.
- **Constant-Time Verification**: `MessageDigest.isEqual` protects against timing attacks during HMAC signature check. Secrets zeroized in memory immediately.
- **ACID Ledger**: Double-entry accounting ensures balance sum is always zero.
- **Relay Worker**: Polling events safely using `SELECT ... FOR UPDATE SKIP LOCKED` without blocking inserts.

*No mocks. No placeholders. Real implementations.*
