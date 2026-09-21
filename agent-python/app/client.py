import hmac
import hashlib
import time
import httpx
from typing import Dict
from .models import PaymentIntent

class AgenticUpiClient:
    def __init__(self, base_url: str, agent_id: str, api_secret: str, client: httpx.AsyncClient):
        self.base_url = base_url.rstrip("/")
        self.agent_id = agent_id
        # Secret is assumed to be base64-encoded or raw hex via env vars
        self.api_secret = api_secret.encode('utf-8')
        self.client = client

    def _generate_signature(self, raw_body_bytes: bytes, timestamp: str, idempotency_key: str) -> str:
        """
        Implements exactly the Java HmacAuthenticationFilter protocol:
        signature_base = SHA256(raw_body_bytes) + "." + timestamp + "." + idemp_key + "." + agent_id
        """
        body_hash = hashlib.sha256(raw_body_bytes).hexdigest()

        # Notice exact structural match with Java backend
        signature_base = f"{body_hash}.{timestamp}.{idempotency_key}.{self.agent_id}".encode('utf-8')

        return hmac.new(
            self.api_secret,
            msg=signature_base,
            digestmod=hashlib.sha256
        ).hexdigest()

    async def execute_payment(self, intent: PaymentIntent, idempotency_key: str) -> Dict:
        """
        Safely executes a payment intent with strict raw-byte signing.
        Notice that idempotency_key MUST be managed by the caller for safe retries!
        """
        # 1. Exact raw bytes that will be sent on the wire
        raw_body_bytes = intent.model_dump_json(exclude_none=True).encode('utf-8')

        # 2. Replay window attributes
        timestamp_ms = str(int(time.time() * 1000))

        # 3. Compute signature over the hashes/attributes
        signature = self._generate_signature(raw_body_bytes, timestamp_ms, idempotency_key)

        # 4. Construct Headers
        headers = {
            "Content-Type": "application/json",
            "X-Agent-Id": self.agent_id,
            "X-Timestamp": timestamp_ms,
            "X-Idempotency-Key": idempotency_key,
            "X-Signature": signature
        }

        # 5. Execute. Note `content=raw_body_bytes`, NOT `json=intent.dict()`
        try:
            response = await self.client.post(
                f"{self.base_url}/v1/agent/execute-payment",
                content=raw_body_bytes,
                headers=headers
            )

            response.raise_for_status()
            return response.json()

        except httpx.ReadTimeout:
            # Re-raise explicit timeout to the caller so they know to reuse the idempotency_key
            raise TimeoutError("Gateway Timeout. Must retry with same idempotency key.")
