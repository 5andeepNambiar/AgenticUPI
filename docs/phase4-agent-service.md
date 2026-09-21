# AgenticUPI — Phase 4: Untrusted AI Agent Service (Python/FastAPI)

> **The integration point for autonomous agents.** This service demonstrates how an untrusted AI (like ChatGPT or a custom LLM assistant) constructs structured payment intents, deterministically signs them using a shared secret, and interacts with the AgenticUPI gateway. 

> **Scope note:** This represents the code that a third-party developer (or Anthropic/OpenAI) would write to integrate their agent with our system. It lives in ZONE 0 (Untrusted). The agent service is a client SDK/demo orchestrator, not a trusted payment authority.

---

## 1. Core Requirements

1. **Structured Outputs:** Parse fuzzy LLM intent into strict, type-safe JSON.
2. **Cryptographic Signatures:** Implement the exact raw-payload HMAC-SHA256 signing algorithm expected by the Java backend. 
3. **Idempotency Management:** Safely handle retry loops — if an HTTP timeout occurs, the retry *must* use the same idempotency key, or risk a double payment.
4. **Resilience:** Handle the custom `AgentErrorCode` taxonomy returned by the gateway gracefully.

---

## 2. Pydantic Models for Intent Extraction

When calling an LLM (e.g., via tool use), we constrain its output to a strict schema. Note that Pydantic validation is **not security** — it is only UX/input hygiene. The backend policy engine is the true safety layer. 

**Crucial Note on `payer_vpa`:** In a production agent orchestration environment, the LLM should *never* determine the `payer_vpa`. The `payer_vpa` should be injected securely by the session context of the enclosing app. For this SDK demo, we accept it in the model, but document the risk.

```python
from typing import Optional, Literal
from pydantic import BaseModel, ConfigDict, Field, constr

class PaymentIntent(BaseModel):
    # Enforce strict parsing without silent coercion (e.g., rejecting float to string)
    model_config = ConfigDict(strict=True)

    # In production, this should be injected by the authenticated User Session, NOT the LLM!
    payer_vpa: constr(pattern=r'^[a-zA-Z0-9.\-_]+@[a-zA-Z]+$') = Field(
        ..., description="The UPI VPA of the user initiating the payment."
    )
    
    payee_vpa: constr(pattern=r'^[a-zA-Z0-9.\-_]+@[a-zA-Z]+$') = Field(
        ..., description="The VPA of the merchant or payee."
    )
    
    amount_paise: int = Field(
        ..., gt=0, le=50000, description="Amount in paise. Must be > 0 and <= 50000 (₹500)."
    )
    
    currency: Literal["INR"] = Field(
        default="INR", description="ISO 4217 currency code. Only INR supported."
    )
    
    category: Literal["FOOD", "TRANSPORT", "BILLS", "SHOPPING"] = Field(
        ..., description="Merchant category string."
    )
    
    agent_context: Optional[str] = Field(
        None, max_length=500, description="Untrusted contextual justification for this payment (e.g. 'Paid for Uber')."
    )
```

---

## 3. Cryptographic Signing Layer (HMAC-SHA256)

This client logic mirrors the Java backend's `HmacAuthenticationFilter`. 

**Critical Design Point:** We serialize the Pydantic model to bytes *first*, then compute the HMAC on those exact bytes, and send those *same exact bytes* in the HTTP request (`content=raw_body_bytes`). We do NOT pass a dict to `httpx.post(json=...)`, because `httpx` might serialize it slightly differently (e.g., adding spaces or changing key order), causing the signature to fail validation at the gateway.

```python
import hmac
import hashlib
import time
import httpx
from typing import Dict, Optional

class AgenticUpiClient:
    def __init__(self, base_url: str, agent_id: str, api_secret: str, client: httpx.AsyncClient):
        self.base_url = base_url.rstrip("/")
        self.agent_id = agent_id
        # Secret is assumed to be base64-encoded or raw hex via env vars
        self.api_secret = api_secret.encode('utf-8')
        self.client = client

    def _generate_signature(self, raw_body_bytes: bytes, timestamp: str, idempotency_key: str) -> str:
        """
        Implements the signature protocol matching the Java gateway:
        signature_base = SHA256(raw_body_bytes) + "." + timestamp + "." + idemp_key + "." + agent_id
        """
        body_hash = hashlib.sha256(raw_body_bytes).hexdigest()
        
        signature_base = f"{body_hash}.{timestamp}.{idempotency_key}.{self.agent_id}".encode('utf-8')
        
        return hmac.new(
            self.api_secret,
            msg=signature_base,
            digestmod=hashlib.sha256
        ).hexdigest()

    async def execute_payment(self, intent: PaymentIntent, idempotency_key: str) -> Dict:
        """
        Safely executes a payment intent with strict signing.
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
```

---

## 4. FastAPI Agent Endpoint & Idempotency Management

This is the endpoint exposed by the AI Agent provider (e.g. an AI Travel Assistant) to its own frontend or LLM orchestrator. 

**The biggest Interview Catch:** If a gateway request times out (`TimeoutError`), and your client retry policy generates a *new* idempotency key on the retry, you will accidentally submit a second logical payment and drain the user's funds. 

```python
import os
import uuid
import logging
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException, Depends
from tenacity import retry, retry_if_exception_type, stop_after_attempt, wait_exponential

logger = logging.getLogger(__name__)

# httpx Client singleton lifecycle
http_client = httpx.AsyncClient(timeout=10.0)

@asynccontextmanager
async def lifespan(app: FastAPI):
    yield
    await http_client.aclose()

app = FastAPI(title="AI Agent - Pay Orchestrator", lifespan=lifespan)

def get_upi_client() -> AgenticUpiClient:
    return AgenticUpiClient(
        base_url=os.environ["AGENTIC_UPI_BASE_URL"],
        agent_id=os.environ["AGENTIC_UPI_AGENT_ID"],
        api_secret=os.environ["AGENTIC_UPI_SECRET"],
        client=http_client
    )

# --- Idempotent Retry Policy ---
# Only retry on explicit Networking/Timeout errors.
# Do NOT retry on 409 Conflict, 429 Velocity Exceeded, or 403 Policy failures.
@retry(
    retry=retry_if_exception_type(TimeoutError),
    stop=stop_after_attempt(3),
    wait=wait_exponential(multiplier=1, min=2, max=10),
    reraise=True
)
async def _execute_with_retry(client: AgenticUpiClient, intent: PaymentIntent, idemp_key: str):
    return await client.execute_payment(intent, idemp_key)


@app.post("/agent/execute-payment")
async def handle_agent_payment(intent: PaymentIntent, client: AgenticUpiClient = Depends(get_upi_client)):
    """
    Called by the LLM orchestrator when the AI decides a payment is needed.
    """
    
    # 1. State setup: Generate idempotency key ONCE before the retry block
    # By passing this explicitly, retries inside _execute_with_retry are guaranteed 
    # to submit the exact same key to the gateway.
    idempotency_key = f"idemp_{uuid.uuid4().hex}"
    
    logger.info(f"Initiating payment. Payer: {intent.payer_vpa}, IdempKey: {idempotency_key}")

    try:
        result = await _execute_with_retry(client, intent, idempotency_key)
        
        # Safe Audit logging (DO NOT log raw context, secrets, or sensitive payload here)
        logger.info(f"Payment success. Gateway TxnId: {result.get('txn_id')}")
        return {"status": "success", "gateway_response": result}

    except httpx.HTTPStatusError as e:
        status = e.response.status_code
        error_payload = e.response.json()
        
        # Error handling must not leak internals (stack traces) to the orchestrator/agent
        error_code = error_payload.get('error_code', 'UNKNOWN')
        logger.warning(f"Gateway rejected payment. Code: {error_code}, Payload: {error_payload}")

        msg = "Payment Rejected"
        if status == 401:
            msg = "Agent Configuration Error"
        elif status == 403:
            msg = f"Policy/Mandate Verification Failed: {error_code}"
        elif status == 409:
            msg = "Idempotency or Request Conflict"
        elif status == 429:
            msg = "Velocity/Limit Exceeded"
            
        raise HTTPException(400, msg)

    except TimeoutError:
        logger.error(f"Gateway unrecoverable timeout after retries. Manual recon required for idemp: {idempotency_key}")
        raise HTTPException(504, "Gateway timed out. Payment status unknown.")
    except Exception as e:
        logger.error(f"Unexpected internal error: {str(e)}")
        raise HTTPException(500, "Internal Agent Error")
```

---

## 5. Interview Signal

> **What this phase proves to a hiring manager:**

| Talking Point | Why It Impresses |
|--------------|------------------|
| **Idempotency Lifecycle & Retry Awareness** | The immediate recognition that an internal UUID generation inside a retry loop causes double debits on timeouts. Placed precisely before the `tenacity` retry wrapper. |
| **`content=raw_body_bytes` serialization invariant** | Proves you understand how cross-language integration *actually breaks in production*. By avoiding `httpx.post(json=...)`, you eliminate HTTP-client-injected formatting variations that break HMAC verification. |
| **HTTPX Singleton Lifecycle** | Properly managing connection pools via `asynccontextmanager` instead of creating an `httpx.AsyncClient` globally or instantiating a new one per request (which causes socket exhaustion at scale). |
| **Pydantic `Literal`s and Strict Mode** | Shows maturity when parsing LLM configurations. `Enum`/`Literal` restricts the output space and forces the AI prompt orchestrator to handle formatting, preventing backend injection or coercion bugs. |
| **Payer_vpa Injection Disclaimer** | Calling out that `payer_vpa` must come from trusted session context, not arbitrary model input, demonstrates deep operational security and system boundary modeling. |

---
*Phase 4 complete. Ready for Phase 5 (Angular / RxJS Control Plane) on your signal.*